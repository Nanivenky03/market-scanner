package com.trading.scanner.service.provider.angelone.dto;

public record AngelOneCandleRequest(
        String exchange,
        String symboltoken,
        String interval,
        String fromdate,
        String todate
) {
}