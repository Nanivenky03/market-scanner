package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AngelOneLoginResponse(
        Boolean status,
        String message,
        String errorcode,
        AngelOneLoginData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AngelOneLoginData(
            String jwtToken,
            String refreshToken,
            String feedToken
    ) {
    }
}