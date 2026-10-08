# Mithron: Personal Systematic Trading & Market Analysis Engine

Last audited: 2026-09-29  
Status: **Version 1.0 Complete** (Data & Market Lifecycle Foundation — 320 unit tests passing).

---

## 1. What is Mithron?

**Mithron is a personal, rules-based market analysis, strategy research, and trading decision-support engine for Indian equities.**

Its purpose is to turn raw market data and trading hypotheses into **persistent, measurable, and auditable evidence**, and filter the **Nifty 500 universe** into a **small, structured, explainable set of qualified setups** with explicit risk controls.

Mithron is designed to answer:
> *"Given everything known about the market up to this exact point in time, which stocks deserve attention, why do they deserve it, what would invalidate the setup, where is the risk, and what does the historical evidence show?"*

### What Mithron is NOT:
- **Not an autonomous black-box trading bot** (the human operator is the decision authority).
- **Not an AI stock predictor** or forecasting model.
- **Not a replacement for TradingView** (visual charting remains a companion tool).
- **Not a collection of 50 random indicators** or manually maintained spreadsheets.
- **Not a strategy factory** (adding more unproven rules is not progress).

---

## 2. Core Philosophy & Operating Principles

1. **Process Over Prediction:** The goal is not to guess tomorrow's price, but to apply the exact same validated process every single day.
2. **Exclusion Before Selection:** First determine: *"Which stocks should I NOT consider?"* Only after low-quality, illiquid, erratic, or high-risk candidates are eliminated does Mithron rank the qualified remainder.
3. **Rules Must Be Explicit:** Every rule is mathematical and deterministic (`INPUT → FORMULA → OUTPUT`). No subjective interpretations, no "looks bullish", no unquantified intuition.
4. **Strategies Identify; Shared Engines Decide and Act:** A strategy evaluates canonical facts and emits signals/proposals only. Shared validation, portfolio/trade-gating, risk, position-management, and execution engines decide whether to act and manage the resulting position. Strategies never call broker order APIs.
5. **Strategies Never Fetch or Calculate Indicators:** Strategies must never fetch market data, call broker APIs, calculate RSI/VWAP, or build candles. They strictly consume the canonical shared facts calculated once in Layer 2.
6. **Historical Simulation as Fast-Forward Paper Trading:** Local historical simulation runs the **exact same paper trading workflow, strategy rules, risk engine, and execution adapters** as live paper trading. The only difference is the clock: live paper trading is driven by live market ticks, whereas historical simulation feeds finalized historical bars in fast-forward. Both write to the identical `simulated_*` schema, ensuring 100% zero implementation drift between local backtesting and live market execution.
7. **Early Universe Expansion (Nifty 500 Data Accumulation):** Right after V1.0 live validation, the universe expands to Nifty 500 so Mithron silently accumulates 30+ days of clean 1M candles and volume baselines in the background while V2, V3, and V4 are built.
8. **Explainability Over Black Boxes:** Whenever Mithron qualifies or rejects a stock, it records the exact reasons:
   ```text
   HINDUNILVR
   Setup:        VWAP Pullback
   Why:          ✓ Above VWAP | ✓ RSI 57.3 | ✓ VOL_X 1.84 | ✓ Trend Aligned
   Invalidation: Price closes below ₹2,480.00
   Risk:         0.85% (₹21.00/share)
   Target:       ₹2,545.00 (2.5R)
   Status:       APPROVED
   ```
9. **Point-in-Time Correctness:** At minute $T$, the engine must strictly only know what was knowable at minute $T$. No lookahead bias or future-leakage is permitted in live or historical replay.
10. **Zero Code Drift (Same Strategy & Position Code for Replay and Live):** The exact same strategy class, quality gate, position handler, and risk checks evaluate live market feeds and historical simulation datasets. No separate "backtester" code exists.
11. **Immutability of Research Evidence:** Generated signals and forward outcomes are permanent research records. Rule modifications increment `rule_version` rather than rewriting history.
12. **Simplicity Before Scale:** Built as a clean, modular monolith maintainable by a single engineer. Complexity must earn its place.
13. **Fail Closed and Recover Deterministically:** Untrusted or incomplete data blocks new application decisions for the affected symbol. Recovery reconciles canonical market data and broker position/order state before position decisions resume. Broker-accepted protective orders remain active during application-side pauses.

---

## 3. The 5-Layer Mental Model

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        LAYER 5: USER / UI (V4)                         │
│   Market Dashboard • Setup Buckets • Explainability Cards • Analytics  │
├────────────────────────────────────────────────────────────────────────┤
│                 LAYER 4: DECISION SUPPORT & RISK (V2/V3)               │
│   Scanner Engine → Strategy Validator → Risk Engine → Signal Audit     │
├────────────────────────────────────────────────────────────────────────┤
│                        LAYER 3: STRATEGIES (V2)                        │
│               VWAP Pullback  │  Breakout (FCHB)  │  ORB                │
├────────────────────────────────────────────────────────────────────────┤
│             LAYER 2: MARKET UNDERSTANDING & DATA (V1 COMPLETE)         │
│     1M/5M/15M Candles • VWAP, RSI, ATR, Baselines • 4-Test EOD Archiving │
├────────────────────────────────────────────────────────────────────────┤
│                    LAYER 1: INFRASTRUCTURE (V1 COMPLETE)               │
│        PostgreSQL DB • TradingCalendar • 400ms Backfill Queue • Alerts │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Current State: Version 1.0 (Data & Market Lifecycle Foundation) `[COMPLETE]`

Version 1.0 is the production-grade market data, candle materialization, indicator calculation, EOD reconciliation, raw archiving, and automation engine.

### Verified Capabilities:
- **Phase 1 (Startup Bootstrap):** 6 fail-closed stages verifying catalog, active NIFTY index, universe seed, runtime settings, and previous-day EOD readiness.
- **Phase 2 (Pre-Market Pipeline):** 07:00 AM morning maintenance, 08:00 AM catalog & token sync, universe validation, reference baseline calculation, 08:50 AM EOD catchup cutoff guardrail, and 09:10 AM pre-market uncrossed price locking ($\text{Open} = \text{High} = \text{Low} = \text{Close} = \text{last uncrossed tick}$).
- **Phase 3 (Live Streaming & Ingestion):** Binary SmartStream WebSocket parser (filtering pre-09:08 ticks), tick deduplication, 1M candle accumulation, and **450ms paced background queue worker** intercepting `AB1021` in-memory across the active 500-stock universe.
- **Phase 4 (Shared Indicators):** Wilder's RSI-14 ($\ge 42$ clean 1M candles), Wilder's ATR-14 ($\ge 14$ clean 1M candles + `prevDayClose`), Session VWAP (09:15 reset), 375-minute volume baseline curves, runtime $\text{VOL\_X}$, and candle structure ratios.
- **Phase 5 (Quiescence, Archiving & EOD):** Post-16:00 quiescence disconnect ($\ge 3$ min silence), **Upfront batch raw historical 1M candle archival (`data/raw-eod/YYYY-MM-DD/SYMBOL.json`) & Market Quotes**, **DB-first authoritative reconciliation against Market Quote**, upsert of official `MarketQuote` into `stock_prices` for historical reference, in-memory candidate repair gating, bi-hourly retry deferrals (17:00, 19:00, 21:00), 23:00 final attempt fallback alignment with quote, and 23:45 session token teardown.
- **Runtime Admin & Developer Tools:** Swagger UI OpenAPI inspector (`/swagger-ui/index.html`), authenticated SmartAPI proxy (`POST /admin/runtime/broker/smartapi/proxy`), and on-demand EOD reconciliation (`/admin/runtime/eod/reconcile`).
- **Test Baseline:** **321 unit tests passing** (0 failures, 0 errors, 1 skipped).

---

## 5. Master Version Roadmap

```text
MITHRON MASTER ROADMAP

V1.0 — Market Data & Daily Session Foundation          [COMPLETE ✅]
  ✓ SmartStream WebSocket ingestion (Nifty 500 active universe), 1M/5M/15M candles, 450ms queue
  ✓ Shared Indicators (VWAP, RSI-14, ATR-14, VOL_X, 375m Baselines, Structure)
  ✓ 4-Test EOD integrity verification, raw JSON disk archiving & stock_prices daily upsert
  ✓ 321 passing unit tests, Swagger SmartAPI inspector proxy, and Nifty 500 daily accumulation

V2.0 — Strategy Intelligence & First Strategy Vertical Slice     [NEXT MILESTONE]
  - Decision Language: Canonical MarketContext (MarketState + StockContext)
    • Master Market Filter: Binary Nifty VWAP switch evaluated on 5M candle closes (Nifty 5M close > VWAP: active; < VWAP: suspended).
    • Mid-Session Cross Recovery: Recoverable if Nifty 5M candle closes back above VWAP before 01:00 PM (1M/tick crosses treated as noise).
    • Macro Regimes (TRENDING_UP, RANGE, etc.): Explicitly deferred until post-simulation empirical discovery.
  - Long vs. Short Strategy Decoupling:
    • Separate classes for Long (FchbStrategy) and Short (FclbStrategy). Intraday shorting has asymmetric market physics and strict MIS 03:15 PM auto-square-off rules.
  - Strategy Contract & Architecture: StrategyProposal (Proposer) vs StrategyValidator (Gatekeeper)
    • Pure decision functions: Strategies never fetch data or calculate indicators.
    • Decoupled Position Handler: FchbStrategy evaluates entry signals; FchbPositionHandler evaluates exits.
    • Explainability: First-class domain model capturing passedConditions and failedConditions.
  - First Strategy Proving Ground: FCHB (First Candle High Breakout)
    • Opening Reference (09:15–09:19 AM): 5 1M candles define FCH, FCL, close, volume, range (0.15%–3.0%), and bullishness (close > open).
    • Volume Normalization: Standardized on time-normalized VOL_X >= 1.8 (rigid Rs. 1 Crore floor and 2.0x opening volume multiplier removed to avoid penalizing mid-caps or block-trade opens).
    • Signal Detection (5M closes, 09:30–01:00 PM): Close > FCH, BO_% <= 0.8%, VOL_X >= 1.8, 5M RSI 55–72, Close > VWAP, 15M Close > 15M VWAP, strong bullish candle (body >= 0.60, upper wick <= 0.25).
    • Traffic-Light Quality Gate: Red / Yellow / Green matrix replaces opaque 0–100 scoring. Trades 1–3 permit up to 2 YELLOWs (0 RED). Trades 4–5 demand ALL GREEN (0 RED, 0 YELLOW).
    • Execution Model: Immediate Market Order on 5M signal confirmation (entry price = next 1M candle open price).
    • Single-Order State Machine: Resting STOPLOSS_MARKET order placed on broker exchange at firstCandleLow × 0.995. Trailing stops adjusted in-place via modifyOrder(orderId, newTriggerPrice). Early structural exits (1M close inside first candle) convert resting order via modifyOrder(orderId, orderType="MARKET"), completely eliminating reverse-position race condition.
    • Targets: Target 1 = +1R (50% exit), Breakeven move at +0.8R, Target 2 = ATR trailing stop on remaining 50%, 02:45 PM conditional exit, 03:00 PM hard close.
    • Pre-Market Day Open Resilience: Day Open locked at 09:08 uncrossed price. If network outage occurs during 09:00–09:14, pre-market pipeline queries SmartAPI Market Quote API (never falls back to continuous 09:15 open).
  - Multi-Strategy Prioritization & Inline Arbitration:
    • Priority Order: Priority 1 = ORB, Priority 2 = FCHB, Priority 3 = VWAP Pullback.
    • Orchestrator resolves symbol priority in-line: winning strategy owns the symbol through position exit; losing signal recorded as SYMBOL_ALREADY_TRADED_TODAY. Zero extra classes needed.
  - Trade / Position Gate & Dual Portfolio Lifecycle:
    • Dual Portfolio State Separation: PortfolioState(PAPER) vs PortfolioState(LIVE) operate independently so paper trading never consumes or blocks the 5-trade daily quota or risk budget of real-money trading.
    • Dynamic Capital & Margin Management: Leverages SmartAPI Margin Calculator API (/margin/v1/batch) and Brokerage Estimate Charges API (/brokerage/v1/estimateCharges). Dynamic Net R:R after charges >= 1.5 enforced.
    • Strictly one ENTRY per stock per day across all strategies combined in that portfolio (unfilled orders do not lock symbol).
    • Global Account Limits: Maximum 5 executed trades per day and 3 simultaneously open positions per portfolio.
  - Unified In-Memory Architecture (Zero V1 Changes):
    • LiveMarketSnapshotService holds real-time ticks/LTP in RAM for 0 ms exit checks.
    • StockContextCache maintains sliding windows of 1M, 5M, 15M candles for 500 stocks (< 35 MB RAM) with lock-free atomic reads.
    • Zero database queries during live market strategy evaluation.
  - Parameter Management & Immutability:
    • Version-controlled YAML parameter baselines synced to strategy_rule_value DB table on startup.
    • Runtime DB overrides without code redeployment. Immutable rule_context JSON audit snapshots on every signal.
  - Implementation Progress:
    • V2.0 Legacy Retirement & Baseline: COMPLETED ✅ (Cleaned obsolete prototype classes/YAMLs, decoupled services; 318 unit tests passing).
    • V2.0 Schema Architecture: COMPLETED ✅ (V034 dropped obsolete tables, V035 established 8 core strategy/order/position tables with zero SQL defaults, entities & repositories created).
    • V2.1 Evaluation Infrastructure: COMPLETED ✅ (MarketState, StockContext, Strategy interface, MasterMarketFilterService, lock-free StockContextCache with < 35 MB RAM footprint and 0 DB backfill queries; 326 unit tests passing).
    • V2.1-FIX Production Reliability Fixes: COMPLETED ✅ (WebSocket timestamp reset on connect/disconnect & premarket-live-start transition order; 327 unit tests passing).
    • V2.2 FCHB Strategy Module: PLANNED (Signal evaluation & position handler).
    • V2.3 Portfolio Risk Engine & Position Manager: PLANNED (Dual paper/live portfolio states, dynamic margin/charges, 1M position loop).
    • V2.4 Master Workflow Orchestrator & Adapters: PLANNED (Conductor & simulated execution adapter).

V3.0 - Advanced Risk & Research Laboratory  [RISK + RESEARCH]
  - Extend the V2 baseline risk and position management with advanced portfolio controls and risk policies
  - Expand point-in-time simulation analytics: win/loss rate, profit factor, max drawdown, MFE/MAE, expectancy
  - Improve failure analysis and compare versioned strategy behavior over larger historical samples

V4.0 — Professional Web UI & Trader Dashboard           [DEDICATED UI VERSION]
  - Live Market State & Nifty 500 Universe Overview
  - Strategy Setup Buckets & Live Signal Cards with "Why is this stock here?" explainability
  - Interactive Backtest Explorer & Strategy Analytics Charts
  - Trade Plan Visualizer (Entry, Stop, Target, Risk %)
  - Stock Search & History Timeline

V5.0 — Trading Engine & Order Execution (SmartAPI)     [ORDER EXECUTION]
  - SmartAPI Order Service: placeOrder, modifyOrder, cancelOrder
  - Order Book, Trade Book, and Position Book live synchronization
  - Live execution integration with Risk Engine (Hard Daily Loss cutoff, Max Exposure)
  - Emergency Kill Switch (Cancel all pending orders & square-off)
  - Order Lifecycle Audit Trail (PROPOSED → SUBMITTED → FILLED → CLOSED)

V6.0 — Small-Capital Live Trading & Paper/Live Toggle
  - Paper Mode vs Live Mode toggle
  - Live execution with small real capital on proven strategies
  - Production vs Backtest validation
```

---

## 6. Strategy Lifecycle and Promotion Pipeline

Each versioned strategy advances through explicit operating stages:

```text
Historical simulation on copied production data (development database)
        -> evidence review and rule/version decision
Live-market paper trading (no real orders)
        -> operational and behavior verification
Production trading (real broker orders)
```

The strategy logic and risk rules are shared across stages; only the execution adapter and database differ. Production stores paper trades and real-money trades in separate tables. Live stop/target decisions are recalculated at each minute close. Each trade receives an internal immutable ID linked to broker order identifiers; rejected order modifications are retried while the last broker-accepted protective order remains active. Promotion focuses on identifying and eliminating incorrect trades and understanding failure causes, rather than requiring a profit threshold alone. A live strategy's rules are not changed during market hours. Post-market changes create a new immutable version/effective date and retain prior signal, trade, and simulation evidence.

---

### Shared Trade and Position Management Rules

- `stock_universe.is_tradable` is the current data-integrity/export eligibility flag. Pre-market logic should set it true only when the symbol has complete EOD data and `stock_prices` coverage for at least the previous 20 official trading days; an incomplete previous trading day sets it false. Newly seeded symbols start false.
- Production-to-development data copy runs separately from production trading and copies data through the prior official trading day (never today), selecting symbols whose current `is_tradable` is true. Each copy/simulation run should record its date range and included-symbol set. This is a current-eligibility sample, not a reconstruction of historical index membership.
- Strategy evaluation is limited to the eligible symbol set. Shared validation, arbitration, global limits, and risk decisions are not strategy-specific.
- Signals for the same symbol and event are deduplicated into one candidate. Unique configured strategy implementation priority chooses the winner; that strategy owns entry and position-exit decisions until the position closes.
- Global account limits are at most 5 executed trades per trading day and at most 3 simultaneously open positions. A trade counts when entry quantity is executed; the daily count remains after close. Rejected or cancelled unfilled orders do not count. The portfolio/trade gate enforces these limits across strategies.
- Each trade has an application-generated immutable trade/position ID linked to broker order IDs and execution references. A position has a versioned trade plan. At every minute close, recalculate stop/target under the owning strategy's rules and submit modifications when values change. Poll broker order/position details regularly and reconcile them with local state. If a modification is rejected, retry under a defined policy while the previously accepted protective order remains active.
- During per-symbol backfill/recovery, application-side derived calculations and exit decisions pause. Broker-accepted protective orders remain active. After repair, reconcile candles, then broker positions/orders/fills, then evaluate the current exit plan and submit any required order changes. Live WebSocket updates and backfill must be reconciled in timestamp order so older data cannot overwrite newer canonical candles.
- In historical simulation, an entry signal on a candle close fills at the next minute candle open. In live processing, use the next available market tick/price after the signal. Simulation uses a local execution adapter and never calls real order APIs. Fees, slippage, gaps, and partial-fill assumptions remain explicit run metadata and must be agreed before relying on performance results.

---

## 7. The Core Mental Transition

```text
Version 1.0 Answers:
"What happened in the market, and can I trust the data?"

Version 2.0 Answers:
"What is happening now, and which predefined setups qualify?"

Version 3.0 Answers:
"What failed in historical simulation, why did it fail, and did the versioned rules address the cause?"

Version 4.0 Answers:
"How can I clearly visualize, inspect, and monitor setups and historical performance?"

Version 5.0 Answers:
"How do I submit, modify, cancel, and manage live orders safely with strict risk guardrails?"

Version 6.0 Answers:
"Does live execution with small real capital match our simulated evidence?"
```

The human remains in the loop. Mithron's job is to make every decision **systematic, evidence-based, disciplined, and reproducible**.
