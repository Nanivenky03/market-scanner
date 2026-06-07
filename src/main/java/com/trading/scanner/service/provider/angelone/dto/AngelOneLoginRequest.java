package com.trading.scanner.service.provider.angelone.dto;

public record AngelOneLoginRequest(
        String clientcode,
        String password,
        String totp
) {
}