package com.trading.scanner.service.provider;

import java.time.LocalDate;

public record DailyBarDto(
    String symbol,
    LocalDate tradingDate,
    Double open,
    Double high,
    Double low,
    Double close,
    Double adjustedClose,
    Long volume,
    String source
) {

}
