package com.trading.scanner.service.provider.angelone.dto;

public record AngelOneSessionTokens(
        String jwtToken,
        String refreshToken,
        String feedToken
) {
}