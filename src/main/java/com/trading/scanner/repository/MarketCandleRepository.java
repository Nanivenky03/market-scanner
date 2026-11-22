package com.trading.scanner.repository;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.MarketCandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketCandleRepository
                extends JpaRepository<MarketCandle, Integer> {

        Optional<MarketCandle> findBySymbolAndExchangeAndTimeframeAndCandleTime(
                        String symbol,
                        String exchange,
                        CandleTimeframe timeframe,
                        LocalDateTime candleTime);

        Optional<MarketCandle> findTopBySymbolAndExchangeAndTimeframeOrderByCandleTimeDesc(
                        String symbol,
                        String exchange,
                        CandleTimeframe timeframe);

        List<MarketCandle> findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        String symbol,
                        String exchange,
                        CandleTimeframe timeframe,
                        LocalDateTime from,
                        LocalDateTime to);

        List<MarketCandle> findTop100BySymbolAndExchangeAndTimeframeAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
                        String symbol,
                        String exchange,
                        CandleTimeframe timeframe,
                        LocalDateTime candleTime);

        long countBySymbolAndExchangeAndTimeframeAndCandleTimeBetween(
                        String symbol,
                        String exchange,
                        CandleTimeframe timeframe,
                        LocalDateTime from,
                        LocalDateTime to);

        long countByTimeframe(CandleTimeframe timeframe);

        @Query("select min(c.candleTime) from MarketCandle c where c.symbol = :symbol and c.timeframe = :timeframe")
        LocalDateTime findEarliestCandleTime(
                        @Param("symbol") String symbol,
                        @Param("timeframe") CandleTimeframe timeframe);

        @Query("select distinct c.symbol from MarketCandle c where c.timeframe = :timeframe "
                        + "and c.candleTime >= :from and c.candleTime < :to")
        List<String> findDistinctSymbolsByTimeframeAndCandleTimeRange(
                        @Param("timeframe") CandleTimeframe timeframe,
                        @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

        long countByTimeframeAndSource(
                        CandleTimeframe timeframe,
                        String source);

        long countByTimeframeAndCandleTime(
                        CandleTimeframe timeframe,
                        LocalDateTime candleTime);

        long deleteByTimeframeAndCandleTimeBefore(
                        CandleTimeframe timeframe,
                        LocalDateTime candleTime);

        long deleteByCandleTimeBetween(
                        LocalDateTime from,
                        LocalDateTime to);
}
