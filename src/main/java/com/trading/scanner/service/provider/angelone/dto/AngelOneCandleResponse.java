package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AngelOneCandleResponse(
        Boolean status,
        String message,
        String errorcode,
        List<List<Object>> data
) {
}