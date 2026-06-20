package com.pibase.pibase_api.service;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.pibase.pibase_api.config.PiBaseProperties;
import com.pibase.pibase_api.dto.request.LoginRequest;
import com.pibase.pibase_api.dto.request.RegisterRequest;
import com.pibase.pibase_api.dto.response.AuthResponse;
import com.pibase.pibase_api.entity.RefreshToken;
import com.pibase.pibase_api.entity.User;
import com.pibase.pibase_api.exception.DuplicateResourceException;
import com.pibase.pibase_api.repository.RefreshTokenRepository;
import com.pibase.pibase_api.repository.UserRepository;
import com.pibase.pibase_api.security.JwtTokenProvider;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final PiBaseProperties piBaseProperties;
    private final RefreshTokenRepository refreshTokenRepository;


    public AuthResponse login(@Valid LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid email or password");
        }

        if (!user.isActive()) {
            throw new BadCredentialsException("Account is disabled");
        }

        log.info("User logged in: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse register(@Valid RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered");
        }

        String userId = NanoIdUtils.randomNanoId();

        User user = User.builder()
                .id(userId)
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .build();

        userRepository.save(user);

        log.info("User registered: {} ({})", user.getEmail(), userId);

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshAccessToken(String rawRefreshToken) {
        // 1. Validate the JWT itself
        if (!jwtTokenProvider.isTokenValid(rawRefreshToken)) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        // 2. Ensure it's a refresh token, not an access token
        if (!"refresh".equals(jwtTokenProvider.getTokenType(rawRefreshToken))) {
            throw new BadCredentialsException("Invalid token type");
        }

        // 3. Look up the hashed token in DB
        String tokenHash = hashToken(rawRefreshToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Refresh token not found"));

        // 4. Check it revoked
        if (refreshToken.isRevoked()) {
            // Possible token theft - revoke ALL tokens for this user
            log.warn("Reuse of revoked refresh token detected for user {}", refreshToken.getUserId());
            refreshTokenRepository.revokeAllByUserId(refreshToken.getUserId());
            throw new BadCredentialsException("Refresh token has been revoked");
        }

        // 5. Revoke the used token (rotation) and save
        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        // 6. Issue new token pair
        User user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(() -> new BadCredentialsException("User not found"));

        log.info("Token refreshed for user: {}", user);
        return buildAuthResponse(user);
    }

    @Transactional
    public void logout(String rawRefereshToken) {
        if (rawRefereshToken == null || rawRefereshToken.isBlank()) return;

        String tokenHash = hashToken(rawRefereshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                    log.info("User {} logged out, refresh token revoked", token.getUserId());
                });
    }

    private AuthResponse buildAuthResponse(User user) {

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String rawRefreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        // save refreshToken hash in DB
        RefreshToken refreshToken = RefreshToken.builder()
                .id(NanoIdUtils.randomNanoId())
                .userId(user.getId())
                .tokenHash(hashToken(rawRefreshToken))
                .expiresAt(Instant.now().plus(piBaseProperties.getJwt().getRefreshTokenExpiry()))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getDisplayName())
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
