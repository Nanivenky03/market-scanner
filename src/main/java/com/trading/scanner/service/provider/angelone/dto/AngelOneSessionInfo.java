package com.trading.scanner.service.provider.angelone.dto;

public record AngelOneSessionInfo(
        boolean success,
        String message,
        boolean hasJwtToken,
        boolean hasRefreshToken,
        boolean hasFeedToken,
        String jwtTokenPreview,
        String refreshTokenPreview,
        String feedTokenPreview
) {
}