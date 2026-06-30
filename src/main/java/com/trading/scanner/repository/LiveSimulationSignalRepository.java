package com.trading.scanner.repository;

import com.trading.scanner.model.LiveSimulationSignal;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LiveSimulationSignalRepository extends JpaRepository<LiveSimulationSignal, Integer> {

    Optional<LiveSimulationSignal> findByStrategyIdAndSymbolAndTimeframeAndCandleTime(
            String strategyId,
            String symbol,
            String timeframe,
            LocalDateTime candleTime);

    Optional<LiveSimulationSignal> findTopByStrategyIdAndSymbolAndTimeframeAndLifecycleStatusOrderByCandleTimeDesc(
            String strategyId,
            String symbol,
            String timeframe,
            String lifecycleStatus);

    List<LiveSimulationSignal> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long deleteBySignalDateBefore(LocalDate signalDate);
}