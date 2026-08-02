package com.trading.scanner.repository;

import com.trading.scanner.model.VolumeDailyBaseline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface VolumeDailyBaselineRepository extends JpaRepository<VolumeDailyBaseline, Integer> {

    Optional<VolumeDailyBaseline> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    List<VolumeDailyBaseline> findByTradingDateOrderBySymbolAsc(LocalDate tradingDate);

    long deleteByTradingDateBefore(LocalDate tradingDate);
}
