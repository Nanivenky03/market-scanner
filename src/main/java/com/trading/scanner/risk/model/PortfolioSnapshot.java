package com.trading.scanner.risk.model;

import com.trading.scanner.strategy.model.PositionSnapshot;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Immutable point-in-time snapshot of the complete portfolio financial and risk state.
 * Eliminates race conditions and state tearing across concurrent reader threads.
 */
public record PortfolioSnapshot(
        PortfolioMode mode,
        LocalDate tradingDate,
        double initialCapital,
        double currentCapital,
        double grossRealizedPnl,
        double accumulatedFriction,
        double netRealizedPnl,
        double totalCommittedMargin,
        double totalOpenDownsideRisk,
        double totalReservedExitFriction,
        double availableMargin,
        int dailyTradeCount,
        Map<String, PositionSnapshot> openPositions,
        Set<String> tradedSymbolsToday
) {}

