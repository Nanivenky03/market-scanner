package com.trading.scanner.repository;

import com.trading.scanner.model.LiveSimulationSignal;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    default java.util.List<LiveSimulationSignal> findRecent(int limit) {
        java.util.List<LiveSimulationSignal> all = findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}