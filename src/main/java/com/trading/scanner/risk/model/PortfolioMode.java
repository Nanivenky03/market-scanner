package com.trading.scanner.risk.model;

/**
 * Distinguishes Paper Simulation Portfolios from Live Broker Portfolios.
 * Ensures complete operational separation so simulated orders never consume
 * or distort real-money daily trade limits or risk budgets.
 */
public enum PortfolioMode {
    PAPER,
    LIVE
}
