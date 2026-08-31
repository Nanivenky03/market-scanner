package com.trading.scanner.repository;

import com.trading.scanner.model.DailyCandleSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailyCandleSummaryRepository
        extends JpaRepository<DailyCandleSummary, Integer> {

    Optional<DailyCandleSummary> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    long deleteByTradingDateBefore(LocalDate tradingDate);
}
