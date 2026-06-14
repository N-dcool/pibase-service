package com.pibase.pibase_api.dto.response;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserInfo user
) {
    public record UserInfo(
            String id,
            String email,
            String displayName
    ) {}
}
