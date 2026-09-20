# Mithron: verified project reference

Last audited: 2026-09-20  
Evidence boundary: this document reflects the checked-out source tree, project guidance in `project_rules.md` and `project_workflow.md`, the supplied historical notes, and local unit-test output. It is not evidence that a real broker session, PostgreSQL database, or live market feed was exercised in this audit.

## Executive conclusion

Mithron is a Spring Boot 3.2 / Java 21 application for NSE market-data ingestion, candle processing, market context, scanning, simulation, and Angel One connectivity. The source implements substantial data, workflow, WebSocket, alerting, and simulation infrastructure. It does **not** contain a live-order placement or position-management implementation; it must therefore not be described as a complete automated live-trading system.

The repository is currently a work in progress with substantial pre-existing uncommitted changes. This audit did not modify any of them. The workflow document is the intended target state, while several current code paths still follow the earlier bootstrap design. Treat the discrepancies below as blocking review items before enabling unattended live operation.

## What Mithron is for

### Product definition

Mithron is a personal, safety-first intraday market-analysis and trading-support application for NSE equities. Its job is to turn provider market data into trustworthy, explainable trading facts and then use those facts to scan for defined intraday opportunities. It is being built in stages: first reliable data and simulation, then validated signal generation, and only later an explicitly protected paper/live execution boundary.

It is not a general investment platform, portfolio manager, tip generator, high-frequency system, or a promise of profitable trades. It is a deterministic decision-support and automation system for a deliberately small, curated stock universe.

### Intended use case

The intended operator is the project owner running a small NSE intraday workflow. Before and during each trading session, Mithron should:

1. Establish a known-good universe and mapping of symbols to Angel One instruments, including NIFTY as market context.
2. Acquire historical and live market data, build canonical one-minute candles, and derive higher timeframes.
3. Reject incomplete, stale, malformed, un-mapped, or otherwise untrustworthy data rather than filling gaps or guessing.
4. Calculate shared facts once: VWAP, RSI, ATR, volume behavior, candle structure, daily context, opening references, and NIFTY/market state.
5. Evaluate approved intraday strategies against those facts and persist the decision plus its reason.
6. Support simulation and operational monitoring. A future, separately verified execution boundary may translate eligible signals into orders.

### Desired end-state workflow

```text
Angel One catalog / historical REST / live WebSocket
                    ↓
Canonical instruments and reconciled 1-minute candles
                    ↓
Derived 5m/15m candles, indicators, volume, structure, market context
                    ↓
Strategy decisions and hard safety filters
                    ↓
Simulation, audit trail, alerts, and (future) guarded execution
```

The central design principle is that strategy code consumes canonical facts; it does not fetch provider data, manage sessions, recreate indicators, or place orders directly.

### Trading scope and planned strategies

The supplied requirements define three long-side intraday strategies for NSE equities:

- **VWAP Pullback:** identifies a strong morning breakout, watches for a controlled pullback to VWAP, and requires a confirmed rebound before entry.
- **First Candle High Breakout (FCHB):** trades a qualifying break above the high of the first five-minute candle after opening-quality, volume, RSI, ATR, candle, and NIFTY checks.
- **Opening Range Breakout (ORB):** trades a qualifying break from the 09:15-09:30 range after range-quality, participation, volume, candle, RSI/ATR, and NIFTY checks.

All strategies are intended to use hard exclusions such as bad or missing data, invalid market state, F&O ban/corporate-action/event conditions, excessive gaps or volatility, overextended price, and duplicate/late entries. A missed opportunity is preferred to a bad decision.

The Phase 3 requirements also specify a future risk/execution model: 1.5% capital risk per trade, minimum quantity of two, charge-aware reward/risk validation, partial exit at 1R, a non-decreasing ATR trail, abnormal adverse-move exit, and enforced end-of-day closure. These are **planned requirements**, not implemented functionality in the reviewed source.

### Operating principles and non-goals

- Correctness, safety, reliability, and simplicity take priority over trade frequency or performance.
- Data quality is an execution gate, not a warning. Unknown must result in no trade.
- Historical, simulated, and live paths should share decision logic to avoid drift.
- SIMULATION and LIVE must remain explicitly separate; no mode ambiguity may reach execution.
- Secrets, tokens, credentials, and authorization headers must never be committed or written to diagnostics.
- The system should be small, understandable, restart-safe, auditable, and suitable for one owner to operate.

### What “done” means

Mithron becomes ready for a given stage only when that stage can be reproduced and audited: its inputs are complete, its failures are persisted and fail closed, restart behavior is deterministic, focused tests pass, and operations expose enough status to diagnose a block. Live trading is not the default destination; it is the final gated stage after data quality, simulation parity, risk management, order idempotency, and real-environment validation are all demonstrated.

## Verified application shape

- Root package and entry point: `com.trading.scanner.ScannerApplication`.
- Build: Maven; declared version `1.3.9`; Spring Boot parent `3.2.0`.
- Default configuration: PostgreSQL, Flyway migration validation, exchange zone `Asia/Kolkata`, NSE session `09:15–15:30`, server port `8080` (all overridable by environment variables).
- Provider: Angel One REST/session/WebSocket support; instrument-master URL, token/session settings, rate limits, retry limits, and raw-capture settings are configuration-backed.
- Persistence includes instrument master/universe, market candles, minute snapshots, feed state, data-quality/reconciliation, backfill, EOD, alerts, simulation/scanner data, runtime settings, and workflow status. Source migrations currently reach `V033`.
- HTTP operational and developer endpoints exist under the runtime, candle, Angel One, and base-data controllers. Swagger is configured at `/swagger-ui.html`.

## Implemented and source-verified capabilities

| Area | Evidence and current behavior |
|---|---|
| Instrument catalog and universe | `AngelOneInstrumentCatalogSyncService`, `InstrumentMasterSyncService`, and `StockUniverseSeeder` exist. The seeder reads the configured 50-symbol CSV, resolves each active NSE equity from `instrument_master`, creates non-tradable universe rows, and fails if a configured instrument is absent/inactive/not equity. |
| Startup workflow record | `workflow_status` exists with `READY`, `RUNNING`, `SUCCESS`, `FAILED`, and `SKIPPED`. `WorkflowStatusService` persists, retries, and checks dependencies; current startup code uses it only for `instrument-master-sync`. |
| Historical and repair data path | Historical bootstrap, one-minute backfill, quality/reconciliation services, backfill queueing, daily status, candle release barriers, and EOD services exist. Tests demonstrate that an incomplete 361/375 response fails closed. |
| Live data path | Angel One session, WebSocket, tick parser, active-universe subscription, live snapshot, live candle service, feed health, gap detection/repair queueing, and flush/disconnect paths exist. |
| Shared market facts | Market candles support 1m/5m/15m; indicator, volume, candle-structure, market-state, daily-context, and breakout-evaluation services exist with focused tests. |
| Simulation/scanning | Simulation cycle/management and scanner/rule services exist. Strategy catalog/scoring YAML is present for breakout strategies. |
| Operational safety | Readiness checks active instruments and NIFTY broker tokens, bootstrap/EOD status, candle counts, and feed/WebSocket state. Runtime alerts, scheduled-job alerts, housekeeping, recovery, calendar handling, and protected base-data reset endpoints exist. |

## What is not verified or not implemented

- No class or endpoint found for broker order placement, order reconciliation, a real position state machine, quantity/risk calculation, charges/net R:R, partial exits, trailing stops, or EOD order closure. The supplied Phase 3 specifications for VWAP Pullback, FCHB, ORB, sizing, and exits are requirements, not proof of implementation.
- No `VwapPullbackSignalGenerator`, `FirstCandleBreakoutSignalGenerator`, or `OpeningRangeBreakoutSignalGenerator` was found. Existing `BreakoutSignalGenerator`/evaluator must not be assumed to satisfy those detailed rules without a targeted requirements review.
- Live provider, credentials, broker authentication, real PostgreSQL/Flyway run, production profile, WebSocket market session, and real historical provider response were not run in this audit.
- The supplied older runtime facts (port 8090, `mithron_prod`, Flyway V032, 375-candle provider failures) are historical observations, not current defaults. Current source defaults are port 8080, `mithron_dev`, and V033.

## Material discrepancies requiring resolution

1. **Workflow versus current startup code — blocking.** `project_workflow.md` says historical bootstrap, `syncActiveUniverse()`, and active token synchronization were removed from startup. Current `RuntimeBootstrapService.bootstrapIfNeeded()` still calls `syncActiveUniverse()`, `syncAngelOneTokens(true)`, and `HistoricalBootstrapService.bootstrapActiveUniverse()`; `PreMarketWorkflowService` also calls reverse universe sync and token mutation. The implementation and workflow must be reconciled before treating the documented pipeline as true.
2. **Workflow pipeline is incomplete.** The stated five startup nodes (`instrument-master-sync` → `market-reference-setup` → `stock-universe-seed` → `eod-data-readiness` → `runtime-bootstrap-complete`) are not all represented as `workflow_status` startup records. Only the first is wired in the reviewed bootstrap service; pre-market stage tracking still uses `runtime_setting` keys.
3. **Historical bootstrap conflict — blocking.** The workflow explicitly removes historical data as a startup prerequisite. Current readiness requires historical one-minute candles and a completed bootstrap state, so startup/live readiness can still be blocked by historical coverage.
4. **Event ordering remains unsafe.** `AngelOneHistoricalRawResponseCapture` and `RuntimeAutomationService` both listen for `ApplicationReadyEvent`. The supplied notes correctly warn this does not establish an explicit foundation-ready dependency. No `FoundationReadyEvent` was found.
5. **Diagnostic code remains present.** Raw-response capture is disabled by default, which is good, but it is still an application-ready startup side effect when enabled. Keep it temporary, redact output, and remove it after the evidence capture.
6. **Readiness needs an integration test.** Unit tests are green, but the skipped schema contract test and absence of a clean PostgreSQL/Flyway integration run mean persistence and migration compatibility are unverified.
7. **Duplicate migration/artifact context.** Source has V001–V033 while unpacked `BOOT-INF` artifacts are untracked and stop at older migration content. Do not infer deployed behavior from these unpacked artifacts; establish a clean build/deployment artifact before release.

## Safety posture

The project rules are appropriately strict: fail closed on stale/missing/ambiguous data, invalid indicators, calendar/session uncertainty, broker failure, malformed provider responses, and unknown mode. The current source has meaningful evidence of this approach in historical completeness validation, mapping/readiness checks, and failure-path tests. It is still unsuitable to authorize live trading until the discrepancies above are closed and the missing execution/risk boundary is deliberately designed, implemented, and tested.

## Validation performed

- Read `project_rules.md` and `project_workflow.md` and inspected the relevant startup, readiness, provider, instrument, workflow, data-engine, controller, configuration, migration, and test paths.
- Ran `mvn -q test` successfully after allowing Maven Central access: **289 tests**, **51 reports**, **0 failures**, **0 errors**, **1 skipped** (`SchemaContractTest`). Expected negative-path test logs include exceptions and incomplete historical responses; Maven exited successfully.
- `mvnw.cmd` itself is broken in this environment (`Cannot start maven from wrapper`); Maven was invoked directly.
- No source, configuration, migration, test, runtime state, or user-authored file was changed by this audit. This file is the only created artifact.

## Recommended next order of work

1. Decide whether `project_workflow.md` is the intended authoritative startup design; then align the existing bootstrap/pre-market/readiness code to it in one focused change set.
2. Add the missing workflow-status nodes and integration tests for dependency ordering, restart/idempotency, missing NIFTY/token, and EOD failure.
3. Run a clean PostgreSQL + Flyway integration validation and an explicitly gated Angel One diagnostic capture; retain only redacted evidence.
4. Separately implement and test the Phase 3 strategy requirements using canonical facts. Do not add live execution until strategy, risk, position state, order idempotency, and SIMULATION/LIVE guards have been traced end-to-end.

## Source documents

- `project_rules.md`: non-negotiable development and trading-safety rules.
- `project_workflow.md`: intended workflow status and roadmap; it is not fully synchronized with source.
- User-supplied pasted project notes: useful historical context and requirements, but several entries are expressly proposed/unverified and must not override checked source.
