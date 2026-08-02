package com.trading.scanner.repository;

import com.trading.scanner.model.VolumeTimeWindowBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolumeTimeWindowBaselineRepository extends JpaRepository<VolumeTimeWindowBaseline, Integer> {

    Optional<VolumeTimeWindowBaseline> findBySymbolAndExchangeAndTradingDateAndSessionMinute(
            String symbol,
            String exchange,
            LocalDate tradingDate,
            Integer sessionMinute);

    List<VolumeTimeWindowBaseline> findByTradingDateAndSessionMinuteOrderBySymbolAsc(
            LocalDate tradingDate,
            Integer sessionMinute);

    long deleteByTradingDateBefore(LocalDate tradingDate);
}
