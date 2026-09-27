# Mithron: verified project reference

Last audited: 2026-09-25  
Evidence boundary: this document reflects the checked-out source tree, project guidance in `project_rules.md` and `project_workflow.md`, the authoritative Common Engine Validation Guide, and local unit-test output.

## Executive conclusion

Mithron Version 1 is a Spring Boot 3.2 / Java 21 safety-first NSE market data ingestion, candle processing, indicator calculation, EOD reconciliation, raw broker archiving, and session automation engine. The source implements robust data pipelines, fail-closed scheduling, WebSocket streaming with post-market quiescence handling, continuous rate-limited queue worker backfill, raw broker REST archiving to disk, 375-minute candle reconciliation, high-priority alerting, and daily session teardown. It does **not** contain a live-order placement or position-management implementation; it must therefore not be described as a complete automated live-trading system.

Version 1 encompasses 5 core phases:
- **Phase 1:** Application Bootstrapping & Startup Readiness (6 fail-closed stages)
- **Phase 2:** Daily Pre-Market Pipeline (07:00 AM maintenance, 08:00 AM data preparation, 08:55 AM live start gate)
- **Phase 3:** Live Market Ingestion & Intraday Gap Repair (08:55 AM to post-market quiescence, continuous 400ms rate-limited queue worker)
- **Phase 4:** Shared Calculation & Common Indicator Engine (VWAP, Wilder's RSI-14, ATR-14, VOL_X, Baselines, Candle Structure)
- **Phase 5:** Post-Market Shutdown, Raw Broker Archiving, 375-Minute EOD Reconciliation, Alerting & Session Teardown

Trading strategy execution (VWAP Pullback, FCHB, ORB), scanner state machines, and the web user interface are designated for **Version 2**.

The application test suite is fully verified: **314 unit tests passing** (0 failures, 0 errors, 1 skipped).

## What Mithron is for

### Product definition

Mithron is a personal, safety-first intraday market-analysis and trading-support application for NSE equities. Its job is to turn provider market data into trustworthy, explainable trading facts and then reconcile and archive all session data deterministically.

It is not a general investment platform, portfolio manager, tip generator, high-frequency system, or a promise of profitable trades. It is a deterministic decision-support and automation system for a deliberately small, curated stock universe.

### Intended use case

The intended operator is the project owner running a small NSE intraday workflow. Before, during, and after each trading session, Mithron:

1. Establishes a known-good universe and mapping of symbols to Angel One instruments, including NIFTY as market context.
2. Acquires historical and live market data, builds canonical one-minute candles, and derives higher timeframes.
3. Rejects incomplete, stale, malformed, un-mapped, or otherwise untrustworthy data rather than filling gaps or guessing.
4. Calculates shared facts once: VWAP, RSI-14 (Wilder's smoothing), ATR-14 (Wilder's smoothing), time-normalized volume baselines (375 session minutes), VOL_X, candle structure, daily context, and opening references.
5. Ingests live WebSocket feeds past 15:30 until post-16:00 quiescence (disconnecting only after $\ge 3$ minutes of silence).
6. Fetches official historical candles from Angel One REST API post-17:00, saves raw JSON responses to disk (`data/raw-eod/YYYY-MM-DD/SYMBOL.json`), reconciles all 375 minute bars, and triggers high-priority alerts on missing data.
7. Performs daily session teardown, invalidates broker session tokens, and marks the daily cycle complete.

### Desired end-state workflow (Version 1)

```text
Angel One catalog / historical REST / live WebSocket
                    ↓
Canonical instruments and reconciled 1-minute candles
                    ↓
Derived 5m/15m candles, indicators, volume baselines, structure, market context
                    ↓
Post-market quiescence disconnect & raw API disk archiving
                    ↓
375-minute EOD reconciliation & high-priority alerting
                    ↓
Broker session token invalidation & daily cycle finalization
```

---

## Phase 4: Common Engine Validation Standards

All calculation engines adhere to the following exact specifications:

### 1. RSI-14 (Wilder's Smoothing)
- **Stored on:** `market_candles.rsi_14` (1M, 5M, 15M mandatory).
- **Prerequisite Gate:** Requires $\ge 42$ consecutive clean 1M candles of today. If $< 42 \to$ returns `null` (never `0.0`).
- **Seeding:** Simple average for first 14 candles (`avg_gain = sum(gains)/14`, `avg_loss = sum(losses)/14`).
- **Smoothing:** Wilder's smoothing for subsequent candles (`avg_gain = (prev_avg_gain * 13 + current_gain) / 14`).
- **Edge Cases:** All gains $\to 100.0$, all losses $\to 0.0$, zero change $\to 50.0$.
- **Tolerance:** Within 0.1 points vs TradingView RSI(14).

### 2. ATR-14 (Wilder's Smoothing)
- **Stored on:** `market_candles.atr_14` (1M, 5M, 15M mandatory).
- **Prerequisite Gate:** Requires $\ge 14$ consecutive clean 1M candles of today **AND** authoritative `prevDayClose` from `stock_prices`. If $< 14$ or `prevDayClose == null` $\to$ returns `null`.
- **True Range:** `TR = max(high - low, |high - prev_close|, |low - prev_close|)`.
  - **Previous Close:** For the first candle of the trading day, previous close comes from `stock_prices` (authoritative daily close). For subsequent candles, previous close comes from previous same-timeframe candle close.
- **Smoothing:** Simple average for first 14 TR values, then Wilder's smoothing (`ATR = (prev_ATR * 13 + current_TR) / 14`).
- **Tolerance:** Within Rs. 0.05 vs TradingView ATR(14).

### 3. VWAP & Derived Metrics
- **Stored on:** `market_candles.vwap` (1M calculated, carried forward to 5M/15M).
- **Prerequisite Gate:** Requires $\ge 1$ candle today with volume $> 0$.
- **Formula:** `TP = (high + low + close) / 3`, `VWAP = cumulative(TP * volume) / cumulative(volume)`.
- **Reset:** Resets strictly at 09:15 AM every trading day.
- **Runtime Derivations:**
  - `niftyAboveVwap` = `nifty_close > nifty_vwap` (master long switch).
  - `niftyVwapDirection`: RISING (> +0.05%), FALLING (< -0.05%), FLAT (-0.05% to +0.05%), UNKNOWN (< 4 points).
  - `vwapProximityPct` = `(stock_close - stock_vwap) / stock_vwap * 100`.
- **Tolerance:** Within Rs. 0.10 vs Angel One chart VWAP.

### 4. Volume Engine & Baselines
- **Active-From Market Days Gate:** Evaluates `TradingCalendar.tradingDaysBetween(active_from, today)`. If completed trading days $< 20 \to$ ADV-20, runtime $\text{VOL\_X}$, and participation ratio return `null` (Fail-Closed).
- **20-Day ADV:** Average daily volume across last 20 trading days from clean `stock_prices.volume` (`volume_daily_baseline.avg_daily_volume_20`).
- **Time-Normalized Baselines (375 minutes):** Average cumulative volume across last 20 trading days for every minute $0 \dots 374$ from `market_candles` (`volume_time_window_baseline`).
- **Runtime VOL_X:** `currentCumulativeVolume / baselineCumulativeVolume(currentSessionMinute)`.

### 5. Candle Structure & Guards
- **Zero Range Guard:** If `high - low <= 0` or `low <= 0`, all ratios are `null`, `strongBullish = false`, `strongBearish = false`.
- **Ratios:** `bodyRatio = |close - open| / range`, `upperWickRatio = (high - max(open, close)) / range`, `lowerWickRatio = (min(open, close) - low) / range`, `rangePct = (high - low) / close * 100`.
- **Strong Bullish:** `direction == BULLISH && bodyRatio >= 0.60 && upperWickRatio <= 0.25 && rangePct >= 0.15%`.
- **Strong Bearish:** `direction == BEARISH && bodyRatio >= 0.60 && lowerWickRatio <= 0.25 && rangePct >= 0.15%`.

---

## Phase 5: Post-Market Shutdown, Archiving, EOD Reconciliation & Teardown

- **Quiescence Disconnect (16:00 to 17:00):** Evaluated every 5 minutes post-16:00. Disconnects WebSocket only when idle $\ge 3$ minutes. Marks `market-hours` stage `SUCCESS`.
- **Hourly EOD Reconciliation, 4-Test Provider Integrity Verification & Raw Disk Archiving (17:00 to 23:00):** Executes hourly at 17:00, 18:00, 19:00, 20:00, 21:00, 22:00, and 23:00 (`0 0 17,18,19,20,21,22,23 * * MON-FRI`). Fetches official 1-minute historical candles from SmartAPI, archiving clean JSON responses (`RawBrokerCandleArchive`) to `<runtime.eod.raw-archive-dir>/<YYYY-MM-DD>/<SYMBOL>.json`. Validates provider historical feed integrity using 4 rigorous checks: (1) Session boundary (`09:15` start to `15:29` close), (2) Official Market Quote Day OHLC matching (Open, High, Low, LTP/Close), (3) Total volume ceiling check ($\sum \text{1M Volume} \le \text{quote.tradeVolume}$), and (4) Benchmark index handling. When integrity passes, any absent minute in the provider feed is confirmed as genuine `NO_TRADE_CONFIRMED` immediately on Attempt 1 (17:01 PM), recomputing indicators and completing EOD same-day without unnecessary waiting.
- **Incremental & Deferred Resolution:** Skips already `COMPLETE`/`REPAIRED` symbols on subsequent attempts. Retains `PARTIAL` status and logs `INFO` without sending false alert emails during intermediate hourly runs (17:00-22:00) if provider data is temporarily lagging or truncated; strictly on the final $\ge$ 23:00 attempt, unresolvable symbols fail the workflow and trigger high-priority alerts. Automatically resolves `runtime.eod.reconciliation_incomplete.<date>` alert when partial count reaches 0.
- **375-Minute Candle Reconciliation:** Matches local candles against broker API data, repairs missing bars into `market_candles`, updates `eod_data_entry`, and recomputes derived indicators.
- **Pre-Market Auto-Catchup (08:00 AM):** `PreMarketWorkflowService` automatically executes `reconcilePreviousTradingDay()` before universe sync to catch up any prior-day omissions.
- **Feed Health & Alert Auto-Resolution:** `FeedHealthService` evaluates tick staleness strictly during live feed window (08:55 - 15:30) on trading days, resolving `runtime.feed_health` outside market hours. Startup recovery alerts auto-resolve once bootstrap is ready.
- **Session Teardown (23:45):** Scheduled broker session clear to 23:45 (11:45 PM) post-reconciliation, invalidates active broker tokens, and marks `daily-cycle-complete` stage `SUCCESS`.

---

## Outage & Startup Auto-Catchup Sequencing

When the application or network experiences downtime during any part of the day, Mithron automatically catches up missed workflow stages upon restart or network recovery in strict chronological order:

1. **Pre-Market Outages (07:00 – 08:55 AM):**
   - `StartupRecoveryService` triggers `catchUpPreMarketWorkflowsIfDue()`.
   - If offline at 07:00 AM and started at 08:15 AM: runs `runMorningMaintenance()` (trading-day-init, housekeeping) immediately, followed by `runPreMarketDataPipeline()` (catalog-sync, universe-sync, morning-reference).
   - If started at 09:00 AM (during market): runs the full pre-market chain in sequence, connects WebSocket, and marks live start `SUCCESS`.
2. **Live Market Outages & Network Disconnections (09:15 – 15:30):**
   - WebSocket auto-reconnect runs every 60 seconds (`scheduledRecoverLiveRuntime`).
   - Closed-minute gap detection and in-memory tick sequence checks scan universe symbols for missing 1-minute bars and enqueue them into `BackfillQueueService`.
   - Continuous rate-limited queue worker (`backfill-queue-worker` daemon thread) paces REST historical candle fetches at **400ms (2.5 RPS / 150 RPM)**, catching `AB1021` in-memory and immediately resolving un-traded minutes as `NO_TRADE_CONFIRMED` on Attempt 1. All 51 universe symbols drain in **~20.4 seconds flat**.
   - Recomputes VWAP, RSI, ATR, and volume baselines seamlessly without dropping data.
3. **Post-Market / EOD Outages (17:00 – 23:45):**
   - Retries run hourly at 17:00, 18:00, 19:00, 20:00, 21:00, 22:00, and 23:00.
   - If offline during evening attempts and restarted later that night (e.g., 23:30): `StartupRecoveryService` immediately executes `reconcileTradingDay(today)` on startup.
   - If offline overnight until the next morning: `PreMarketWorkflowService` executes `reconcilePreviousTradingDay()` at 08:00 AM before evaluating universe tradability.

---

## Operating Principles and Safety Posture

- Correctness, safety, reliability, and simplicity take priority over trade frequency or performance.
- Database (`holiday_calendar`, `runtime_setting`, `workflow_status`) is the authoritative source of truth.
- `TradingCalendar` is the single source of truth for trading day arithmetic (skipping weekends and official holidays).
- Data quality is an execution gate, not a warning. Unknown must result in fail-closed safety.
- Secrets and tokens must never be committed or written to logs.
- The system is restart-safe, auditable, and idempotent.

## Verified Application Shape

- Root package and entry point: `com.trading.scanner.ScannerApplication`.
- Build: Maven; Spring Boot parent `3.2.0`; Java 21.
- Persistence: PostgreSQL + Flyway migrations (`V001` through `V033`).
- Runtime Developer Tools: Swagger UI OpenAPI inspector (`/swagger-ui/index.html`), SmartAPI authenticated proxy (`POST /admin/runtime/broker/smartapi/proxy`), on-demand EOD reconciliation (`POST /admin/runtime/eod/reconcile`), session lifecycle admin (`/admin/runtime/broker/*`).
- Test suite: **314 unit tests passing**, 0 failures, 0 errors, 1 skipped.
