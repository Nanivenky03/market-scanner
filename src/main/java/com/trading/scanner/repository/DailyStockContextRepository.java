package com.trading.scanner.repository;

import com.trading.scanner.model.DailyStockContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyStockContextRepository extends JpaRepository<DailyStockContext, Integer> {

    Optional<DailyStockContext> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    List<DailyStockContext> findByTradingDateOrderBySymbolAsc(LocalDate tradingDate);

    long deleteByTradingDateBefore(LocalDate tradingDate);
}