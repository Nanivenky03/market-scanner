package com.trading.scanner.repository;

import com.trading.scanner.model.StrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, Integer> {

    Optional<StrategyConfig> findByStrategyKey(String strategyKey);

    Optional<StrategyConfig> findByStrategyKeyAndIsActiveTrue(String strategyKey);
}
