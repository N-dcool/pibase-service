package com.pibase.pibase_api.service;

import com.aventrix.jnanoid.jnanoid.NanoIdUtils;
import com.pibase.pibase_api.dto.request.LoginRequest;
import com.pibase.pibase_api.dto.request.RegisterRequest;
import com.pibase.pibase_api.dto.response.AuthResponse;
import com.pibase.pibase_api.entity.User;
import com.pibase.pibase_api.exception.DuplicateResourceException;
import com.pibase.pibase_api.repository.UserRepository;
import com.pibase.pibase_api.security.JwtTokenProvider;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;


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

    private AuthResponse buildAuthResponse(User user) {

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                new AuthResponse.UserInfo(user.getId(), user.getEmail(), user.getDisplayName())
        );
    }
}
