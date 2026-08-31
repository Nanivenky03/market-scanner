package com.trading.scanner.repository;

import com.trading.scanner.model.EodDataEntry;
import com.trading.scanner.model.EodDataStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EodDataEntryRepository
        extends JpaRepository<EodDataEntry, Integer> {

    Optional<EodDataEntry> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    List<EodDataEntry> findBySymbolAndExchangeOrderByTradingDateDesc(
            String symbol,
            String exchange);

    List<EodDataEntry> findByTradingDateOrderBySymbolAsc(LocalDate tradingDate);

    boolean existsBySymbolAndExchangeAndTradingDateAndStatusIn(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            List<EodDataStatus> statuses);
}
