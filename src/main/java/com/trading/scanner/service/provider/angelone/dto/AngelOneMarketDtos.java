package com.trading.scanner.service.provider.angelone.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

public final class AngelOneMarketDtos {

    private AngelOneMarketDtos() {
    }

    public record AngelOneCandleRequest(
            String exchange,
            String symboltoken,
            String interval,
            String fromdate,
            String todate) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AngelOneCandleResponse(
            Boolean status,
            String message,
            String errorcode,
            List<List<Object>> data) {
    }

    public record AngelOneSearchScripRequest(
            String exchange,
            String searchscrip) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AngelOneSearchScripResponse(
            Boolean status,
            String message,
            String errorcode,
            List<AngelOneSearchScripItem> data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AngelOneSearchScripItem(
                String exchange,
                String tradingsymbol,
                String symboltoken) {
        }
    }
}