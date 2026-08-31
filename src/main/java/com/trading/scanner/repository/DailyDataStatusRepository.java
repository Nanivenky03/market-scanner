package com.trading.scanner.repository;

import com.trading.scanner.model.DailyDataStatus;
import com.trading.scanner.model.DataStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyDataStatusRepository extends JpaRepository<DailyDataStatus, Integer> {

    Optional<DailyDataStatus> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    List<DailyDataStatus> findByTradingDateOrderBySymbolAsc(LocalDate tradingDate);

    List<DailyDataStatus> findByTradingDateAndStatus(
            LocalDate tradingDate,
            DataStatus status);

    long deleteByTradingDateBefore(LocalDate tradingDate);
}
