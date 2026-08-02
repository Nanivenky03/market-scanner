package com.trading.scanner.config;

import jakarta.validation.constraints.NotNull;

public record BreakoutRuleProperties(
        @NotNull Integer lookbackWindow,
        @NotNull Integer rsiPeriod,
        @NotNull Integer smaShortPeriod,
        @NotNull Integer smaMediumPeriod,
        @NotNull Integer smaLongPeriod,
        @NotNull Double rsiThresholdMatch,
        @NotNull Double volumeMultiplierMatch,
        @NotNull Double rsiThresholdConfidence,
        @NotNull Double volumeMultiplierConfidence,
        @NotNull Double baseConfidence,
        @NotNull Double confidenceIncrement,
        @NotNull Double maxConfidenceCap,
        @NotNull Double maxGap,

        Double firstCandleVolumeMultiplierMin,
        Double openingRangeParticipationMin,
        String requiredRecentVolumeDirection) {
}
