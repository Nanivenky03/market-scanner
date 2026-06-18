package com.trading.scanner.repository;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketCandleRepository extends JpaRepository<MarketCandle, Integer> {

    Optional<MarketCandle> findBySymbolAndExchangeAndTimeframeAndCandleTime(
            String symbol,
            String exchange,
            CandleTimeframe timeframe,
            LocalDateTime candleTime
    );

    List<MarketCandle> findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
            String symbol,
            String exchange,
            CandleTimeframe timeframe,
            LocalDateTime from,
            LocalDateTime to
    );
}