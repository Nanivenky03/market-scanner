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
4. **Strategies Propose, Validator Approves:** Strategies never execute trades directly. A strategy proposes a candidate signal; the independent **Strategy Validator** verifies market state, data quality, trading window, duplicate checks, and risk limits.
5. **Strategies Never Fetch or Calculate Indicators:** Strategies must never fetch market data, call broker APIs, calculate RSI/VWAP, or build candles. They strictly consume the canonical shared facts calculated once in Layer 2.
6. **Risk Engine Precedes Backtesting:** A backtest without risk rules is meaningless. The **Risk Engine** (entry zones, stop loss, risk distance %, position sizing, R:R targets, max daily loss) is integrated directly into the backtest engine in **Version 3.0** so strategies are tested as complete, realistic trades.
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
10. **Same Strategy Code for Live & Replay:** The exact same strategy and risk code must evaluate live market feeds and historical simulation datasets to eliminate implementation drift.
11. **Immutability of Research Evidence:** Generated signals and forward outcomes are permanent research records. Rule modifications increment `rule_version` rather than rewriting history.
12. **Simplicity Before Scale:** Built as a clean, modular monolith maintainable by a single engineer. Complexity must earn its place.

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
- **Phase 2 (Pre-Market Pipeline):** 07:00 AM morning maintenance, 08:00 AM catalog & token sync, universe validation, reference baseline calculation, and 09:10 AM pre-market uncrossed price locking ($\text{Open} = \text{High} = \text{Low} = \text{Close} = \text{last uncrossed tick}$).
- **Phase 3 (Live Streaming & Ingestion):** Binary SmartStream WebSocket parser (filtering pre-09:08 ticks), tick deduplication, 1M candle accumulation, and **400ms (2.5 RPS / 150 RPM) paced background queue worker** intercepting `AB1021` in-memory.
- **Phase 4 (Shared Indicators):** Wilder's RSI-14 ($\ge 42$ clean 1M candles), Wilder's ATR-14 ($\ge 14$ clean 1M candles + `prevDayClose`), Session VWAP (09:15 reset), 375-minute volume baseline curves, runtime $\text{VOL\_X}$, and candle structure ratios.
- **Phase 5 (Quiescence, Archiving & EOD):** Post-16:00 quiescence disconnect ($\ge 3$ min silence), **Upfront batch raw historical 1M candle archival (`data/raw-eod/YYYY-MM-DD/SYMBOL.json`) & Market Quotes**, **DB-first authoritative reconciliation against Market Quote**, upsert of official `MarketQuote` into `stock_prices` for historical reference, in-memory candidate repair gating, bi-hourly retry deferrals (17:00, 19:00, 21:00), 23:00 final attempt fallback alignment with quote, and 23:45 session token teardown.
- **Runtime Admin & Developer Tools:** Swagger UI OpenAPI inspector (`/swagger-ui/index.html`), authenticated SmartAPI proxy (`POST /admin/runtime/broker/smartapi/proxy`), and on-demand EOD reconciliation (`/admin/runtime/eod/reconcile`).
- **Test Baseline:** **320 unit tests passing** (0 failures, 0 errors, 1 skipped).

---

## 5. Master Version Roadmap

```text
MITHRON MASTER ROADMAP

V1.0 — Market Data & Daily Session Foundation          [COMPLETE ✅]
  ✓ SmartStream WebSocket ingestion, 1M/5M/15M candles, 450ms paced historical queue
  ✓ Shared Indicators (VWAP, RSI-14, ATR-14, VOL_X, 375m Baselines, Structure)
  ✓ 4-Test EOD integrity verification, raw JSON disk archiving & stock_prices daily upsert
  ✓ 320 passing unit tests & Swagger SmartAPI inspector proxy

  └─► IMMEDIATE POST-V1 ACTION:
      Expand Universe to Nifty 500 (Starts daily 500-stock data accumulation)

V2.0 — Strategy Intelligence & Real-Time Signals        [NEXT MILESTONE]
  - Market State Scanner (TRENDING_UP, TRENDING_DOWN, RANGE, BREAKOUT_ENV)
  - Strategy Framework & Interfaces (Strategies propose; Validator approves)
  - Core Strategies: VWAP Pullback, FCHB (First Candle High Breakout), ORB (Opening Range Breakout)
  - Strategy Validator & Signal Audit Trail (Explicit passed/failed reasons)

V3.0 — Risk Engine & Historical Backtesting Laboratory  [RISK + RESEARCH]
  - Risk & Trade-Plan Engine:
    • Structural Stop Loss & Invalidation Level
    • Risk Distance (%) & Extension Guards (Max risk per trade)
    • Position Sizing & Reward-to-Risk (R:R) targets
    • Max Daily Loss & Trailing Stop rules
  - Historical Backtest Engine:
    • Point-in-time replay using the EXACT same strategy + risk code
    • Evaluates complete trades across accumulated Nifty 500 dataset
    • Performance Metrics: Win/Loss rate, Profit Factor, Max Drawdown, MFE/MAE, Expectancy

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

## 6. The Strategy Promotion Pipeline

A strategy never jumps directly into live trading:

```text
Strategy Development (V2.0)
        ↓
Historical Backtest with Risk Engine (V3.0)
        ↓
Visual Inspection & Dashboard Analysis (V4.0)
        ↓
Trading Engine Integration & Order Simulation (V5.0)
        ↓
Small-Capital Live Trading (V6.0)
```
*(If live behavior deviates materially from simulation, the strategy is immediately demoted.)*

---

## 7. The Core Mental Transition

```text
Version 1.0 Answers:
"What happened in the market, and can I trust the data?"

Version 2.0 Answers:
"What is happening now, and which predefined setups qualify?"

Version 3.0 Answers:
"With full risk rules (stops, targets, sizing), would this setup have made money historically?"

Version 4.0 Answers:
"How can I clearly visualize, inspect, and monitor setups and historical performance?"

Version 5.0 Answers:
"How do I submit, modify, cancel, and manage live orders safely with strict risk guardrails?"

Version 6.0 Answers:
"Does live execution with small real capital match our simulated evidence?"
```

The human remains in the loop. Mithron's job is to make every decision **systematic, evidence-based, disciplined, and reproducible**.
