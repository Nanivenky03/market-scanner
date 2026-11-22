# Mithron Evidence Log

Dated record of what has been verified about the system, what was decided, what each release changed, and what is
still open. The other md files describe the intended design; this file records what was actually observed. When this
file and another md file disagree, the other file is stale and must be corrected in the same change.

Rule for this file: facts only, each with its evidence source. Update it in every release.

---

## 1. Why this file exists (2026-11-22)

FCHB was tuned on a short sample and an earlier replay (63 trades, +Rs 1,284) was used to judge it. That replay was
not equivalent to live: it used its own volX formula, bypassed Gate 8, kept closed trades in risk state, and read
candles that live could not have had. The result is void as evidence. The current job is to rebuild the evidence base:
replay through the production code, backfill ~78 more sessions of 1M history, then re-run the UNCHANGED strategy.
No strategy rule changes are allowed until that is done.

## 2. Verified facts about the live system (as of v2.5.9, read from code and the production DB)

| # | Fact | Evidence |
|---|------|----------|
| 1 | Live `volX` was never a real volume ratio. `live_feed_state.cumulative_volume_today` is NULL on every session checked (Nov 4-20) and `market_minute_snapshot` has no rows after Sep 30, so the snapshot path returned null and `StockContextCache` fell back to `5M volume x 3 / first-candle volume`, or a default of 2.5. | `live_feed_state`, `market_minute_snapshot` queries; `StockContextCache` (v2.5.9) |
| 2 | The Nov 13 baseline was computed at 08:00:09-08:02:47, before the open. "Baseline job ran after the open" is false. | `volume_time_window_baseline.computed_at`, `workflow_status` |
| 3 | The Nov 12 baseline stage reported SUCCESS with `processedSymbols=0` (Nov 11 has no `daily_stock_context`), so Nov 12 had no baselines at all and live-start still proceeded. | `workflow_status` row for `premarket-morning-reference` |
| 4 | Live Nifty "VWAP" is a TWAP: Nifty candles have volume 0 and VWAP null on every 1M/5M bar, and `MasterMarketFilterService` silently substitutes a session TWAP of 5M candles (1M if 5M is stale). | `market_candles` NIFTY rows; `MasterMarketFilterService.java` |
| 5 | Gate 8 (upper-circuit room) never ran live: `LiveStrategyExecutionConductor` calls `evaluate(..., emptyMap())`. The old replay injected a made-up `prevClose x 1.20` band. | conductor source; old replay |
| 6 | Live wind-down is unconditional at 14:45 (conductor), while the spec and the handler say a breakeven-protected runner holds to 14:55. 6 of 7 paper trades exited via `INTRADAY_WIND_DOWN_1445`. | conductor `:161`; `simulated_positions` |
| 7 | "5M" invalidation Rules 5A/5B are evaluated when the minute of a 1M candle's START is a multiple of 5, i.e. on the close of the FIRST 1M bar of each 5M block, not on a completed 5M close. TITAN's exit at 10:11:05 is the close of the 10:10 1M candle. Rule 5B reads the 1M candle's VWAP. Rule 5C never runs (no `ema9`/`ema21` parameters are passed). | `FchbPositionHandler.java:105-139`; `simulated_positions` id 4 |
| 8 | The entry window is 5M candle START 09:30-10:15, so the last decision is the candle closing at 10:20. Docs said 10:30. | `FchbStrategy.ENTRY_WINDOW_END` |
| 9 | Paper charges used Angel One's model: `BrokerChargesCalculator.activeBroker` defaulted to ANGEL_ONE and the `trading.broker.paper=KOTAK_NEO` setting was never read. Paper trades carried Rs 54-65 of charges. | `BrokerChargesCalculator`, `simulated_positions.statutory_charges` |
| 10 | In the 09:15-10:20 window on Nov 13-20 only ~10% of 1M candles are `LIVE_WEBSOCKET`; ~89% were written by `EOD_RECONCILIATION`. On Oct 15 the live share was 72%. What live saw at decision time is not recoverable: the nightly dumps are taken after reconciliation. | `market_candles.source` by date |
| 11 | The 7 live paper trades never reached Target 1 (max MFE 0.55R). | `simulated_positions.mfe_r` |
| 12 | Corporate actions exist in stored data: JSWDULUX (-89.9% gap Oct 22/23, prev close stale at 2994.8), CONCORDBIO (-50% Nov 4), TTML (+27.6% Oct 23, possibly genuine). Angel One historical data is split-adjusted; stored candles are not, so the join at the Oct 5/6 boundary breaks for such symbols. | `daily_stock_context.gap_pct` |
| 13 | Baselines recompute correctly: for Nov 13 session minute 19, 485 of 498 symbols match a recomputation from current candles exactly; 13 differ by >1% (all with a different sample-day count), none by >10%. | recomputation SQL |
| 14 | Day volume vs the sum of 1M volume differs: for Nov 13 the median ratio is 0.999 but 200 of 499 symbols are >2% above and the mean is 1.09. The cause (API vs reconciliation vs live) is not established. The two series are therefore stored independently and never used to correct each other. | `stock_prices` vs `market_candles` |
| 15 | The 1M candle at 09:08 holds the pre-open volume (BAJFINANCE Nov 13: 47,832) separately from the 09:15 candle (246,302). The day open used for the gap is the 09:08 close; `firstCandleOpen` is the 09:15 candle open. | `market_candles`, `DailyStockContextService` |
| 16 | Housekeeping runs from the 07:00 `premarket-housekeeping` stage on trading days (and on restart catch-up / the admin endpoint). The 05:30 `housekeeping.time` setting is not wired to any scheduler. | `RuntimeHousekeepingService`, logs |
| 17 | `DatabaseBackupService` named dumps after the Spring profile, so prod dumps were called `mithron_dev_*`. They contain `mithron_prod` data. | `/backups/db-backups/` listing; code |
| 18 | The repo-root log files are from the local DEV instance (`ANGELONE_ENABLED=false`, `mithron_dev`); `mvn test` also writes into them. They are not production evidence. Production runs from `D:\projects\mithron-prod`. | `.env`, `mithron-prod.env` |

## 3. Decisions (by the owner)

- The replay must call production classes; any behaviour change is made in production code so live and replay stay identical.
- No strategy rule changes while the evidence base is rebuilt.
- Angel One is the DATA provider; Kotak Neo is the EXECUTION broker (Rs 0 brokerage on API orders). Kotak is the default.
- Retention is counted in trading sessions (150), not calendar days.
- A pre-market stage that processes zero symbols must NOT fail (a failed stage blocks the websocket feed). It logs ERROR, raises a HIGH alert, and trading is blocked per symbol because a missing volX rejects the candidate.
- 1M candles and daily bars are stored as delivered and are independent of each other.
- Historical pulls are one session per call per symbol; time taken is not a concern; rate limits are.
- Every release: bump `pom.xml`, `mvn clean test`, update these md files, then commit.
- NIFTYBEES (token 10576) is registered as a benchmark series so it is subscribed live from v2.6.0 and pulled historically. Gate 7 is NOT changed yet (still the Nifty-index TWAP); NIFTYBEES is research data for a later, evidence-based change.

## 4. Release v2.6.0 (branch `release/v2.6.0-evidence-rebuild`)

Production changes:
- `StockContextCache`: volX = cumulative volume of today's finalized 1M candles (kept per session minute, not limited to the 30-candle window) / stored baseline for the same session minute. No fallback and no default; null makes Gate 2 reject. `LiveVolumeMetricsService` and its snapshot path were removed.
- Retention in sessions: `retention.candles.sessions` (default 150, Flyway V038). Applies to candles, contexts, data status, summaries and baselines.
- `premarket-morning-reference` with zero processed symbols: stage SUCCESS, ERROR log, HIGH alert `runtime.premarket.volume_baselines_empty.<date>`.
- `BrokerChargesCalculator` defaults to KOTAK_NEO and `RuntimeSettingService` applies `trading.broker.paper` at startup.
- Backups are named after the database (`mithron_prod_YYYY-MM-DD.dump`).
- `MarketIndexRegistry`: NIFTYBEES added.
- `LiveMarketCandleService`: a recompute/repair of closed candles no longer feeds the live cache or publishes strategy events; only today's recompute touches the cache. `DailyStockContextService` updates the live cache only for today.
- Historical backfill (new): `HistoricalBackfillService` + `HistoricalBackfillController`; `AngelOneMarketDataProvider.fetchHistoricalOneMinuteForBackfill` (shares the row parser with the live/EOD fetch); historical endpoint paced at 500 ms.

Test/replay changes:
- `StrategyPerformanceReplayRunner` now drives the real `LiveStrategyExecutionConductor` (`onCandleFinalized`, `processIncomingTick`), real `StockContextCache`, real `MasterMarketFilterService`, `FchbStrategy`, handler, RiskEngine, PositionManagerService and OrderExecutionService. Only the clock, candle source and in-memory persistence are replaced. Candles finalize in the production order (1M, 5M, 15M per symbol; NIFTY first). Intrabar order is a parameter (`ADVERSE_FIRST` default, `FAVORABLE_FIRST`). Trades are read from the position records production writes.
- `S0DiagnosticReplayTest` observes decisions through the runner instead of re-implementing them.
- Tests that assert Angel One fee amounts now pin that model explicitly.

## 5. Historical backfill runbook

Endpoints (admin, same convention as the other `/admin/runtime` endpoints; note the app listens on 0.0.0.0 without authentication):
- `POST /admin/runtime/historical-backfill/start` body (all optional): `{"sessions":78,"toDate":"2026-10-03","symbols":["TCS"],"maxDates":2,"dryRun":true,"retryNoData":false}`
- `GET /admin/runtime/historical-backfill/status`
- `POST /admin/runtime/historical-backfill/stop`

Behaviour:
- Refuses to start on a trading day between 06:55 and 23:50 (live pipeline owns the Angel One session, the live cache and the DB) and stops itself if that window begins during a run. Resume by starting again: progress is checkpointed per symbol-day in `eod_data_entry` (source `HISTORICAL_API_BACKFILL`).
- Targets: active stock universe + all registered indices (including NIFTYBEES). One API call per symbol per session for 1M candles. Daily bars: one range call per symbol into `stock_prices`.
- Insert-only: symbol-days that already have candles and daily rows that already exist are never touched, so live/EOD data is preserved. NIFTYBEES gets its history for the already-stored sessions too.
- Candles are stored with source `HISTORICAL_API_BACKFILL`, quality `RECONCILED`, exactly as delivered. Empty responses are recorded as no-data, never filled.
- Derived data, through the production code: indicators + 5M/15M candles (`LiveMarketCandleService.recomputeDerivedData`), daily contexts (`DailyStockContextService`), `daily_data_status`, and volume baselines for sessions with at least 20 prior sessions of candles (`VolumeBaselineService`). Existing full baselines are never rewritten, and baselines for the next not-yet-happened session are left to the 08:00 job.
- Not written for backfilled days: `daily_candle_summary` (it records expected-vs-actual against a 375-minute expectation, which does not apply to quiet symbols) and `live_minute_resolution` (no-trade minutes are never inferred). `daily_data_status` and `eod_data_entry` are written as RECONCILED/COMPLETE with expected = actual, meaning "stored as delivered".
- Raw responses are archived to `<backups>/historical-raw/<runId>/<date>/<SYMBOL>.json` (daily bars under `daily/`) with a `summary.json`.
- Aborts after 25 consecutive API failures; refreshes the Angel One session once on an expired-token response.
- Always do a dry run first: `{"dryRun":true,"symbols":["TCS","RELIANCE","NIFTYBEES"],"maxDates":2,"sessions":78}` and inspect `dryRunSamples` in `summary.json` (first/last candle, volumes).

Expected effort: ~41,000 1M calls at 2 per second is about 6-9 hours; derived-data computation runs in parallel and baselines add up to ~3.5 hours.

## 6. Open items and known issues (not fixed in v2.6.0)

1. Rule 5A/5B fire on a 1M-close sampled every 5 minutes, not on a completed 5M close (live and replay agree; the spec does not).
2. Live wind-down exits everything at 14:45; the handler's conditional rule is only reached in replay when the conductor is bypassed. Decide which is intended.
3. Gate 8 is not fed live (no circuit data path since the snapshot feed is dead). Decide once for live and replay.
4. Why `market_minute_snapshot` / `live_feed_state` stopped being populated around Oct 1 is unknown.
5. The baseline averages cumulative volume only over days that have a candle at that exact session minute and skips others, which biases thin symbols' baselines. Not changed (strategy input).
6. Gate 7 uses a TWAP for an index with no volume. Evaluate NIFTYBEES VWAP in replay before changing.
7. Corporate actions: run a join-continuity check at the Oct 5/6 boundary after the backfill; affected symbols must be excluded or re-pulled.
8. `DatabaseMarketReplayLoader.getTopStockUniverse` applies static filters (current `is_tradable`, `prev_day_close BETWEEN 50 AND 10000`, a hard-coded symbol exclusion): survivorship bias. Replace with point-in-time rules.
9. Candidate ordering within one 5M boundary (alphabetical in replay) and intrabar tick order are assumptions; run sensitivity scenarios.
10. Paper fills use signal-candle close + 1 tick, not the next candle open; slippage tiers are untested.
11. Admin endpoints are unauthenticated on 0.0.0.0.
12. The legacy fixture replay tests (`@Tag("replay")`: FchbForensicAnalysisTest, FchbPerformanceReplayTest, forced/golden parity) were written against the old runner and the 8 fixture days have no volume baselines; they run only with `mvn -Preplay` and their numbers are not evidence.

## 7. Replay: how to run

- Default `mvn clean test` excludes the `replay` tag (these tests read `mithron_prod` read-only via `REPLAY_DB_URL`).
- Diagnostic over all stored sessions: `mvn -o -Preplay clean test -Dtest=S0DiagnosticReplayTest` (about 5-6 minutes for 32 sessions).
- Always use `mvn clean test`.
