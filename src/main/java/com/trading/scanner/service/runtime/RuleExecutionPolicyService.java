package com.trading.scanner.service.runtime;

import com.trading.scanner.strategy.StrategyStatus;
import com.trading.scanner.strategy.StrategyYamlDefinition;
import org.springframework.stereotype.Service;

@Service
public class RuleExecutionPolicyService {

    public boolean allowHistoricalSimulation(StrategyYamlDefinition strategy) {
        if (strategy == null || strategy.status() == null) {
            return false;
        }

        return switch (strategy.status()) {
            case SIM, LIVESIM, REAL -> true;
            case DRAFT -> false;
        };
    }

    public boolean allowLiveSignalGeneration(StrategyYamlDefinition strategy) {
        if (strategy == null || strategy.status() == null) {
            return false;
        }

        return switch (strategy.status()) {
            case LIVESIM, REAL -> true;
            case DRAFT, SIM -> false;
        };
    }

    public boolean allowRealExecution(StrategyYamlDefinition strategy) {
        if (strategy == null || strategy.status() == null) {
            return false;
        }

        return strategy.status() == StrategyStatus.REAL;
    }

    public boolean allowPaperTradeLifecycle(StrategyYamlDefinition strategy) {
        if (strategy == null || strategy.status() == null) {
            return false;
        }

        return switch (strategy.status()) {
            case LIVESIM, REAL -> true;
            case DRAFT, SIM -> false;
        };
    }
}