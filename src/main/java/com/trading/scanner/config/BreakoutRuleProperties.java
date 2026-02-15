package com.trading.scanner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "rules.breakout")
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
    @NotNull Double maxGap
) {}
