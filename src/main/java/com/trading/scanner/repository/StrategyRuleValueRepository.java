package com.trading.scanner.repository;

import com.trading.scanner.model.StrategyRuleValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StrategyRuleValueRepository extends JpaRepository<StrategyRuleValue, Integer> {

    List<StrategyRuleValue> findByStrategyId(Integer strategyId);

    Optional<StrategyRuleValue> findByStrategyIdAndRuleKey(Integer strategyId, String ruleKey);
}
