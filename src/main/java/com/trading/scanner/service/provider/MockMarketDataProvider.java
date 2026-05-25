package com.trading.scanner.service.provider;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Component
@Primary
@Profile("simulation")
public class MockMarketDataProvider implements MarketDataProvider {

    private static final LocalDate SERIES_START = LocalDate.of(2023, 1, 2);

    @Override
    public ProviderType getProviderType() {
        return ProviderType.MOCK;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<DailyBarDto> fetchDailyBars(LocalDate tradingDate, List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }

        return symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .sorted()
                .map(symbol -> buildBar(symbol, tradingDate))
                .toList();
    }

    @Override
    public List<DailyBarDto> fetchHistoricalBars(String symbol, LocalDate from, LocalDate to) {
        if (symbol == null || symbol.isBlank() || from == null || to == null || from.isAfter(to)) {
            return List.of();
        }

        long days = ChronoUnit.DAYS.between(from, to);

        return java.util.stream.LongStream.rangeClosed(0, days)
                .mapToObj(from::plusDays)
                .sorted(Comparator.naturalOrder())
                .map(date -> buildBar(symbol, date))
                .toList();
    }

    private DailyBarDto buildBar(String symbol, LocalDate tradingDate) {
        long seed = positiveSeed(symbol);
        long dayIndex = Math.max(0, ChronoUnit.DAYS.between(SERIES_START, tradingDate));

        double basePrice = 80.0 + (seed % 420);
        double trend = dayIndex * (0.18 + ((seed % 7) * 0.01));
        double seasonal = Math.sin((dayIndex + (seed % 31)) / 6.0) * 3.5;
        double noise = ((seed + dayIndex) % 9) * 0.12;

        double close = round2(basePrice + trend + seasonal + noise);
        double open = round2(close - 0.8 + (((seed + dayIndex) % 5) * 0.2));
        double high = round2(Math.max(open, close) + 1.2 + (((seed + dayIndex) % 7) * 0.25));
        double low = round2(Math.min(open, close) - 1.0 - (((seed + dayIndex) % 5) * 0.20));

        long volume = 200_000L + (seed % 500_000L) + (dayIndex * 1_500L);

        boolean breakoutDay = dayIndex > 30 && Math.floorMod((int) (dayIndex + (seed % 11)), 23) == 0;
        if (breakoutDay) {
            close = round2(close * 1.045);
            open = round2(close * 0.985);
            high = round2(close * 1.015);
            low = round2(open * 0.99);
            volume = volume * 3;
        }

        if (low <= 0) {
            low = round2(Math.min(open, close) * 0.98);
        }

        return new DailyBarDto(
                symbol,
                tradingDate,
                open,
                high,
                low,
                close,
                close,
                volume,
                "MOCK"
        );
    }

    private long positiveSeed(String symbol) {
        return Integer.toUnsignedLong(symbol.hashCode());
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}