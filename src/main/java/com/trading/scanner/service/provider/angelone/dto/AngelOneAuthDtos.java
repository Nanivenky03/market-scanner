package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

public final class AngelOneAuthDtos {

    private AngelOneAuthDtos() {
    }

    public record AngelOneLoginRequest(
            String clientcode,
            String password,
            String totp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AngelOneLoginResponse(
            Boolean status,
            String message,
            String errorcode,
            AngelOneLoginData data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AngelOneLoginData(
                String jwtToken,
                String refreshToken,
                String feedToken) {
        }
    }

    public record AngelOneSessionInfo(
            boolean success,
            String message,
            boolean hasJwtToken,
            boolean hasRefreshToken,
            boolean hasFeedToken,
            String jwtTokenPreview,
            String refreshTokenPreview,
            String feedTokenPreview) {
    }

    public record AngelOneSessionTokens(
            String jwtToken,
            String refreshToken,
            String feedToken) {
    }
}