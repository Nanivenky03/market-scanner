package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AngelOneSearchScripResponse(
        Boolean status,
        String message,
        String errorcode,
        List<AngelOneSearchScripItem> data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AngelOneSearchScripItem(
            String exchange,
            String tradingsymbol,
            String symboltoken
    ) {
    }
}