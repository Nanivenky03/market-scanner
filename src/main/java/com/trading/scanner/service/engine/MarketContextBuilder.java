package com.trading.scanner.service.engine;

import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MarketContextBuilder {

    private final MarketCandleRepository marketCandleRepository;
    private final StockPriceRepository stockPriceRepository;

    public MarketContext buildMarketContext(MarketCandle triggerCandle) {
        return new MarketContext(
                triggerCandle.getCandleTime().toLocalDate(),
                triggerCandle.getCandleTime(),
                null,
                MarketContext.DayType.UNKNOWN,
                false);
    }

    public SymbolContext buildSymbolContext(MarketCandle triggerCandle) {
        List<MarketCandle> recentCandles = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        triggerCandle.getSymbol(),
                        triggerCandle.getExchange(),
                        triggerCandle.getTimeframe(),
                        triggerCandle.getCandleTime().minusDays(10),
                        triggerCandle.getCandleTime());

        List<StockPrice> dailyPrices = stockPriceRepository.findBySymbolAndDateBetweenOrderByDateAsc(
                triggerCandle.getSymbol(),
                triggerCandle.getCandleTime().toLocalDate().minusDays(10),
                triggerCandle.getCandleTime().toLocalDate());

        return new SymbolContext(
                triggerCandle.getSymbol(),
                triggerCandle.getExchange(),
                triggerCandle.getTimeframe().name(),
                triggerCandle,
                recentCandles,
                dailyPrices);
    }
}