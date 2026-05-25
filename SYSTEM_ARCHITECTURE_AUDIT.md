# SYSTEM ARCHITECTURE AUDIT

Audit date: 2026-05-19  
Repository: `d:\projects\market-scanner`  
Scope: Read-only structural audit of the current filesystem and source tree.  
Important boundary: this report describes the current repository state. It does not treat deleted-but-git-tracked files as present source, although it notes them where they affect architecture. Compiled classes in `target/` are considered generated artifacts, not authoritative source.

==================================================
1️⃣ SYSTEM OVERVIEW
===================

## What This Application Currently Is

Market Scanner is a Spring Boot 3.2.0 / Java 21 trading research engine for NSE-style equity scanning. Its current design intent is a deterministic, replay-safe market scanner that can run in two different runtime modes:

- production/default mode, using the real exchange-zone clock and a production SQLite database path.
- simulation mode, using a persisted simulation state table and calendar-aware trading-day offsets to advance time manually through REST endpoints.

The Maven project identifies itself as:

- group: `com.trading`
- artifact: `market-scanner`
- version: `1.8.0`
- Java version: `21`
- Spring Boot parent: `3.2.0`

The current source tree, however, is incomplete relative to the stated architecture in `README.md`. Several classes described by the docs and referenced by runtime services are not present in `src/main/java`, most importantly `com.trading.scanner.service.data.DataIngestionService`. Because `SimulationCycleService` imports and injects that missing class, the current source tree cannot be treated as a complete buildable application without restoring or replacing that missing source. The compiled `target/` artifact still contains old `service/data` classes, but those are generated/stale artifacts and not current source.

## Core Purpose

The core purpose is to research trading signals deterministically:

1. Maintain a stock universe.
2. Store OHLCV price history.
3. Advance a trading date in simulation.
4. Ingest or synthesize price data for that date.
5. Run scanner rules over all active stocks.
6. Persist generated scan results as signals.
7. Compute forward returns for eligible historical signals after fixed trading-day horizons.
8. Persist signal outcomes separately from signal generation.

The system is not a broker, execution engine, order manager, or live trading bot. It is research infrastructure.

## Architectural Philosophy

The architecture favors:

- explicit state over implicit process memory.
- SQLite-backed state for replayability.
- calendar-aware trading-day arithmetic instead of raw calendar-day advancement.
- fixed rule parameter snapshots on each signal.
- unique constraints for idempotency.
- serialized simulation advancement.
- separation between signal generation and outcome measurement.
- profile-gated simulation endpoints.
- a central exchange clock abstraction instead of ad hoc calls to `LocalDate.now()`.

The current implementation expresses this philosophy clearly in the simulation, calendar, rule, repository, and entity layers. The missing ingestion/provider source weakens the actual executable system.

## Determinism Goals

The determinism goals are:

- The same simulation base date, trading offset, stock universe, price records, rule parameters, and calendar should produce the same signals.
- Simulation time should come from `simulation_state`, not the wall clock.
- Rule parameters should be serialized into `scan_results.parameter_snapshot` so signal identity can be interpreted later.
- Forward outcomes should be computed only when enough future trading days are available.
- Forward outcomes should be append-only and unique by `(signal_id, horizon_days)`.
- Trading-day arithmetic should use `TradingCalendar`, not `plusDays`.

## Replay-Safe Principles

Replay safety is mainly implemented through:

- `simulation_state` single-row persisted offset.
- `SimulationCycleService.advanceSimulation()` transaction with `Isolation.SERIALIZABLE`.
- `advanceLock` synchronized block to serialize process-local simulation advancement.
- `scan_execution_state.trading_date` unique constraint.
- `stock_prices(symbol, date)` uniqueness in `scripts/init_db.sql`.
- `scan_results(symbol, scan_date, rule_name)` unique index in `scripts/init_db.sql`.
- `signal_outcomes(signal_id, horizon_days)` unique constraint in `scripts/init_db.sql` and JPA entity metadata.
- deterministic sorting of breakout rule parameter snapshots via `TreeMap`.
- single Hikari connection pool size in configuration, reducing SQLite write concurrency.

These protections are partly schema-dependent. The checked runtime simulation database currently inspected at `data/market_scanner_sim.db` was created by Hibernate and does not show all indexes from `scripts/init_db.sql`, including the `idx_signal_identity` unique index on `scan_results` and the unique `(signal_id, horizon_days)` declaration on `signal_outcomes`. That matters: the intended constraints exist in SQL and some JPA annotations, but the live DB schema may not fully enforce the SQL script's uniqueness model.

## Difference Between Simulation And Production Modes

Production/default mode:

- Uses `application.properties`.
- Uses `jdbc:sqlite:data/market_scanner.db`.
- Uses `ProductionClockConfig`.
- `ExchangeClock` wraps a system clock in the exchange timezone.
- Dashboard, status, and health endpoints are active.
- Scheduling is enabled globally with `@EnableScheduling`, but the source file `DailyScanScheduler.java` is absent in the current filesystem.
- The current source tree has no POST controller endpoints for ingestion or scan execution, despite dashboard JavaScript trying to call them.

Simulation mode:

- Uses `application-simulation.properties`.
- Uses `jdbc:sqlite:data/market_scanner_sim.db`.
- Uses `SimulationClockConfig`.
- Initializes or reads a single `simulation_state` row.
- Uses `simulation.baseDate=2024-02-19`.
- Exposes `/simulation/status`, `/simulation/advance`, and `/simulation/reset`.
- Seeds 50 NSE stocks through `SimulationUniverseSeeder` if the universe table is empty.
- The scheduler is intended to be disabled by profile, but no scheduler source file currently exists.
- Simulation advancement calls missing `DataIngestionService.ingestSimulatedDailyData(...)`, then scanner, then forward return engine.

## Current Runtime Model

Runtime is a monolithic Spring Boot web application:

- Embedded Tomcat serves controllers and Thymeleaf dashboard.
- Spring Data JPA maps entities to SQLite tables.
- SQLite is the persistence engine.
- HikariCP is configured with a single connection.
- State is stored in local `data/*.db` files.
- Logs are written under `data/logs/`.
- Simulation is manually advanced by POSTing a number of trading days.

## Current Limitations

Current source-level limitations:

- Missing `DataIngestionService` source prevents the simulation execution path from compiling.
- Missing provider classes (`MarketDataProvider`, `YahooFinanceProvider`, retry/circuit-breaker classes) remove live data acquisition from source.
- Missing production scheduler source removes automated production execution.
- Missing ingestion and scan POST controllers make dashboard action buttons point at non-existent endpoints.
- `SimulationProperties` is a `@Component` without `@Profile("simulation")`; past logs show production startup failed because `simulation.baseDate` was absent.
- `SimulationProviderGuardAspect` uses AspectJ annotations, but `pom.xml` does not include `spring-boot-starter-aop`; current source may not compile unless AspectJ classes are otherwise available.
- The configured rule properties in `application-simulation.properties` are incomplete/mismatched: it sets `rules.breakout.maxGap=0.05`, while the bound record expects kebab-case keys for all fields such as `rules.breakout.lookback-window`. Production config has the complete kebab-case rule set.
- Runtime DB schema created by Hibernate does not fully match `scripts/init_db.sql`.
- No `src/test` tree is present.
- Logs and DB files are present in the workspace even though `.gitignore` ignores them.

## What The System Can Do

With the current source tree alone, the system can define:

- stock universe metadata.
- price rows.
- scan execution state.
- scan result/signal rows.
- scanner run summaries.
- simulation state.
- signal outcomes.
- emergency closures.
- trading calendar behavior for 2023-2026 known NSE holidays.
- dashboard/status/health reads.
- deterministic indicator calculations.
- one breakout scanner rule.
- forward return computation from existing prices and existing signals.

With missing source restored, the intended system can also ingest data and run full simulation cycles.

## What The System Intentionally Does NOT Do

The current architecture does not implement:

- trade execution.
- broker integration.
- position sizing.
- portfolio accounting.
- risk management.
- live order placement.
- real-time intraday streaming.
- multi-rule research framework beyond one active rule.
- web-based backtest visualization.
- authentication/authorization.
- distributed execution.

## Current Version State

There is version drift:

- `pom.xml`: `1.8.0`
- `application.properties`: comment says `1.2.0-PRODUCTION`
- `README.md`: says `V1.2-PRODUCTION`
- schema comment: `v1.3-STABILIZATION`
- `signal_outcomes` comment: `v1.9 Forward Return Engine`
- `ForwardReturnEngine` comment: `v1.9`
- built jar: `target/market-scanner-1.8.0.jar`

The source and documentation are not aligned. The source appears to be partway through a simulation/determinism refactor after the v1.2 production/provider architecture.

## Major Capabilities

- Spring Boot web runtime.
- JPA/SQLite persistence.
- NSE trading calendar.
- Simulation clock and persisted timeline.
- Simulation REST control.
- Dashboard read UI.
- Active universe seeding in simulation.
- Rule parameter binding.
- Indicator calculation.
- Breakout signal generation.
- Forward outcome measurement.
- Global exception mapping.

## Execution Model

The intended primary execution path is:

`POST /simulation/advance?days=N` -> `SimulationController` -> `SimulationCycleService` -> `DataIngestionService` -> `ExecutionStateService` -> `ScannerEngine` -> `ForwardReturnEngine` -> repositories -> SQLite.

The currently complete source path starts at the controller but breaks at the missing ingestion class.

## Database Role

SQLite is the system of record for:

- price data.
- universe data.
- per-day ingestion/scan execution state.
- generated signals.
- run summaries.
- forward outcomes.
- simulation timeline.
- emergency market closures.

It is not just a cache. Determinism depends on DB contents.

## Simulation Role

Simulation mode is the core research mode. It replaces wall-clock time with:

- `simulation_state.base_date`
- `simulation_state.trading_offset`
- `TradingCalendar.addTradingDays(baseDate, tradingOffset)`

The simulation clock returns the current simulated date and a fixed simulated time of `09:15`.

## Signal Lifecycle

1. `ScannerEngine` selects active stocks.
2. It loads all prices for each symbol up to the scan date.
3. It calculates indicators.
4. It applies all `ScannerRule` beans.
5. If a rule matches, it creates a `ScanResult`.
6. It stores rule name, rule version, scanner version, confidence, metadata, and parameter snapshot.
7. It saves all results and records a `ScannerRun`.
8. `ExecutionStateService` marks scan complete with signal count.

## Outcome Lifecycle

1. `ForwardReturnEngine.computeEligibleOutcomes(currentSimulationDate)` runs after scanning.
2. For each horizon in `{5, 10, 20}`, it calculates a cutoff date by subtracting trading days.
3. It queries signal IDs with `scanDate <= cutoffDate` and no existing outcome for that horizon.
4. It loads entry price on the signal date.
5. It loads exit price on `signalDate + horizon trading days`.
6. It computes `(exit - entry) / entry`.
7. It saves a `SignalOutcome`.

==================================================
2️⃣ HIGH LEVEL EXECUTION FLOW
=============================

## Text Flow Diagram

Intended simulation flow:

```text
HTTP POST /simulation/advance?days=N
  ↓
SimulationController.advanceCycles(days)
  ↓
SimulationCycleService.advanceSimulation(days)
  ↓
TradingCalendar.nextTradingDay(...)
  ↓
SimulationCycleService.runSingleCycleInMemory(cycleDate, targetOffset)
  ↓
DataIngestionService.ingestSimulatedDailyData(cycleDate, MANUAL)
  ↓
ExecutionStateService.getOrCreateState(cycleDate)
  ↓
ScannerEngine.executeScanForDate(cycleDate)
  ↓
StockUniverseRepository.findByIsActiveTrue()
  ↓
StockPriceRepository.findBySymbolAndDateLessThanEqualOrderByDateAsc(...)
  ↓
IndicatorService.calculateIndicators(...)
  ↓
BreakoutConfirmedRule.matches(...)
  ↓
ScanResultRepository.saveAll(...)
  ↓
ScannerRunRepository.save(...)
  ↓
ExecutionStateService.completeScanForDate(...)
  ↓
ForwardReturnEngine.computeEligibleOutcomes(cycleDate)
  ↓
SignalOutcomeRepository.findEligibleSignalIds(...)
  ↓
ScanResultRepository / StockPriceRepository / SignalOutcomeRepository
  ↓
simulation_state.trading_offset += days
  ↓
SQLite DB
```

Production/dashboard read flow:

```text
HTTP GET /
  ↓
DashboardController.dashboard(...)
  ↓
ExchangeConfiguration.getTodayInExchangeZone()
  ↓
ExchangeClock.today()
  ↓
ExecutionStateService.canIngestToday() / canScanToday()
  ↓
StockUniverseRepository / StockPriceRepository / ScanResultRepository
  ↓
Thymeleaf dashboard.html
```

Status/health flow:

```text
HTTP GET /status or /health
  ↓
DashboardController
  ↓
Repositories and AppInfo
  ↓
JSON response
```

## Stage-by-Stage Explanation

### `SimulationController`

Responsibilities:

- Exposes simulation-only REST API.
- Reads current simulation status.
- Advances simulation by a requested number of trading days.
- Resets simulation offset to base date.

Inputs:

- `GET /simulation/status`
- `POST /simulation/advance?days=N`
- `POST /simulation/reset`

Outputs:

- status map with `baseDate`, `tradingOffset`, `currentDate`
- `SimulationBatchResult`
- reset response map

Side effects:

- `advance` delegates all mutation to `SimulationCycleService`.
- `reset` mutates `simulation_state` through `SimulationCycleService.resetSimulation()` and invalidates the `ExchangeClock` simulation cache.

Tables touched:

- Directly reads `simulation_state`.
- Indirectly touches all simulation cycle tables through `SimulationCycleService`.

Transaction boundaries:

- Controller itself is not transactional.
- Transaction begins in `SimulationCycleService`.

Determinism dependency:

- Only active under `@Profile("simulation")`.
- Uses `ExchangeClock.today()` for current simulated date.

### `SimulationCycleService`

Responsibilities:

- Validates requested day count.
- Serializes simulation advancement using `advanceLock`.
- Runs N complete trading-day cycles inside one transaction.
- Computes the next trading day using `TradingCalendar`.
- Runs ingestion, scan, and forward outcome computation.
- Updates `simulation_state.trading_offset` only after all cycles succeed.

Inputs:

- `days` from controller.
- current `SimulationState` row.
- trading calendar.

Outputs:

- `SimulationBatchResult` containing per-cycle results.

Side effects:

- Intended: writes stock prices and execution state through missing `DataIngestionService`.
- Writes `scan_execution_state`, `scan_results`, `scanner_runs`, `signal_outcomes`, and `simulation_state`.

Tables touched:

- `simulation_state`
- `scan_execution_state`
- `stock_prices` through ingestion
- `stock_universe`
- `scan_results`
- `scanner_runs`
- `signal_outcomes`
- possibly `emergency_closure` indirectly through calendar checks

Transaction boundaries:

- `advanceSimulation()` is `@Transactional(isolation = Isolation.SERIALIZABLE)`.
- The whole batch runs in one transaction.
- `resetSimulation()` is also serializable.
- `runSingleCycleInMemory()` is private and participates in caller transaction.

Determinism dependency:

- All days in a batch commit or roll back together.
- The offset is advanced only after all cycles succeed.
- The process-local synchronized lock prevents two threads in one JVM from advancing simultaneously.
- SQLite single connection further reduces concurrent write exposure.

Current gap:

- The class depends on `DataIngestionService`, which is missing from current source.

### `DataIngestionService`

Responsibilities:

- Intended to ingest daily or simulated daily price data.
- Intended method used by simulation: `ingestSimulatedDailyData(LocalDate, ExecutionMode)`.

Inputs:

- cycle date.
- execution mode.
- active stock universe.
- provider or deterministic simulation source.

Outputs:

- price rows.
- scan execution state updates.

Side effects:

- Writes `stock_prices`.
- Updates `scan_execution_state` ingestion fields.

Tables touched:

- `stock_prices`
- `scan_execution_state`
- likely `stock_universe`

Transaction boundaries:

- Unknown in current source because file is absent.
- Because it is called inside `SimulationCycleService.advanceSimulation()`, it would participate in the batch transaction if not starting a separate transaction.

Determinism dependency:

- This is the most important missing piece. If it calls live providers in simulation, replay determinism is broken. The presence of `@SimulationExit` suggests live provider exits were intended to be guarded, but the current source no longer contains provider classes.

### `ExecutionStateService`

Responsibilities:

- Creates or loads per-date execution state.
- Determines whether ingestion or scan may run.
- Marks ingestion and scan start/complete/failure states.
- Supplies legacy today-based methods using `ExchangeConfiguration`.

Inputs:

- trading date.
- execution mode.
- counts and source status.
- time from `TimeProvider`.

Outputs:

- `ScanExecutionState`.
- boolean gates for can-ingest/can-scan.

Side effects:

- Writes `scan_execution_state`.

Tables touched:

- `scan_execution_state`.

Transaction boundaries:

- Some legacy today methods are individually `@Transactional`.
- Date-specific methods are not annotated and participate in caller transactions if invoked inside one.
- In simulation, calls inside `SimulationCycleService` participate in the serializable batch transaction.

Determinism dependency:

- Uses injected `TimeProvider` instead of direct system time.
- `canScanForDate` requires successful ingestion and positive `stocksIngested`.

### `ScannerEngine`

Responsibilities:

- Orchestrates scanning for a date.
- Gets active universe.
- Loads price histories.
- Computes indicators.
- Applies all rule beans.
- Persists scan results and run summary.
- Marks execution state scan complete.

Inputs:

- scan date.
- active stocks.
- price rows up to scan date.
- rule properties.
- scanner version.

Outputs:

- `ScanResult` rows.
- `ScannerRun` row.
- updated `scan_execution_state`.

Side effects:

- Writes `scan_results`.
- Writes `scanner_runs`.
- Updates `scan_execution_state`.

Tables touched:

- reads `stock_universe`
- reads `stock_prices`
- writes `scan_results`
- writes `scanner_runs`
- reads/writes `scan_execution_state`

Transaction boundaries:

- `executeDailyScan()` is `@Transactional`.
- `executeScanForDate()` is intentionally not annotated and expects caller transaction, but it simply calls private logic. In simulation it participates in `SimulationCycleService` transaction.

Determinism dependency:

- Active stocks are returned by repository without explicit sort, so ordering depends on DB/provider ordering.
- Price histories are ordered by date ascending.
- Rule list ordering is Spring bean list order; currently there is one rule.
- Parameter snapshots are deterministic via rule's `TreeMap`.

### `IndicatorService`

Responsibilities:

- Calculates RSI, SMA20/50/200, average volume, ATR.
- Returns `IndicatorBundle`.

Inputs:

- ordered list of `StockPrice`.
- `IndicatorParameters`.

Outputs:

- indicator values and boolean above-SMA flags.

Side effects:

- none except logging.

Tables touched:

- none directly.

Transaction boundaries:

- none.

Determinism dependency:

- Pure calculation over provided price list.
- Deterministic if input order is deterministic.

### `BreakoutConfirmedRule`

Responsibilities:

- Implements the single scanner rule.
- Detects a price close above recent high with volume, RSI, SMA, and gap constraints.
- Computes confidence.
- Emits metadata and parameter snapshot.

Inputs:

- symbol.
- ordered price list.
- indicator bundle.
- `BreakoutRuleProperties`.

Outputs:

- boolean match.
- confidence.
- metadata string.
- JSON parameter snapshot string.

Side effects:

- logging only.

Tables touched:

- none directly.

Transaction boundaries:

- none.

Determinism dependency:

- Uses bound immutable configuration.
- Uses `TreeMap` for stable parameter snapshot key ordering.
- Metadata currently uses `HashMap.toString()`, which is less ideal for deterministic string ordering.

### `ForwardReturnEngine`

Responsibilities:

- Computes outcomes for eligible signals at fixed horizons.
- Uses trading-day arithmetic for horizons.
- Persists immutable outcome rows.

Inputs:

- current simulation date.
- signals eligible by cutoff.
- stock prices on entry and exit dates.

Outputs:

- `SignalOutcome` rows.

Side effects:

- Writes `signal_outcomes`.

Tables touched:

- reads `scan_results`
- reads `stock_prices`
- reads/writes `signal_outcomes`

Transaction boundaries:

- no annotation; intended to participate in simulation transaction.
- errors per signal are caught and logged, not propagated, so a failed single outcome does not roll back the whole cycle.

Determinism dependency:

- Horizons are hardcoded `{5, 10, 20}`.
- Eligibility excludes already-computed outcomes through repository query.
- Unique outcome identity is intended at `(signal_id, horizon_days)`.

### Repositories

Responsibilities:

- Thin Spring Data JPA persistence adapters.

Inputs:

- entity objects.
- derived query parameters.

Outputs:

- entity objects, lists, counts, optional rows.

Side effects:

- database reads/writes.

Transaction boundaries:

- participate in caller transactions unless repository method starts one implicitly.

Determinism dependency:

- Derived query ordering must be explicit where order matters.
- Some queries have explicit order, some do not.

### SQLite DB

Responsibilities:

- Persistent system of record.
- Enforces some uniqueness.
- Stores simulation state and all research artifacts.

Side effects:

- durable local state.

Transaction boundaries:

- managed by Spring transactions over JDBC/Hibernate.
- single Hikari connection configured.

==================================================
3️⃣ FULL FILE INVENTORY & CLASS AUDIT
=====================================

Estimated current file count from `rg --files`: 78.  
Estimated current Java source file count: 50.  
Estimated essential runtime source/config file count: about 45, but this excludes missing ingestion/provider/scheduler source required by intended runtime.  
Estimated removable or legacy artifact count: about 25-35, mostly `archive/*.ps1`, stale result logs, generated `target/`, and old docs. No deletion is recommended in this phase.

## Build And Root Files

### `pom.xml`

- Class/file name: Maven project descriptor.
- Responsibility: Declares Spring Boot app, Java 21, dependencies, build plugin, version `1.8.0`.
- Why it exists: Build and package configuration.
- Classification: ESSENTIAL.
- Dependents: Maven, IDE, runtime packaging.
- Runtime participation: Indirect.
- Test/certification only: No.
- Notes: Missing `spring-boot-starter-aop` despite source importing AspectJ annotations. Contains Jackson comment referencing Yahoo Finance although provider source is absent.

### `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`

- Responsibility: Maven wrapper scripts/properties.
- Why they exist: Reproducible Maven invocation.
- Classification: SUPPORT.
- Dependents: developers/CI.
- Runtime participation: No.
- Test/certification only: No.

### `.gitignore`

- Responsibility: Ignores target, logs, DB files, IDE files, data.
- Classification: SUPPORT.
- Runtime participation: No.
- Notes: Current workspace contains ignored artifacts such as `data`, `target`, and logs.

### `.vscode/settings.json`

- Responsibility: VS Code Java settings and terminal auto-approval.
- Classification: SUPPORT.
- Runtime participation: No.

### `README.md`

- Responsibility: Older broad system documentation.
- Classification: LEGACY.
- Runtime participation: No.
- Notes: Claims files and endpoints that no longer exist in current source, including provider classes, ingestion services, scheduler, and POST endpoints.

### `QUICKSTART.md`

- Responsibility: Older setup guide.
- Classification: LEGACY.
- Runtime participation: No.
- Notes: References old jar names, missing configs, and dashboard actions that current controllers do not support.

### `run2_results.txt`, `run3_results.txt`, `run4_results.txt`

- Responsibility: Captured run outputs.
- Classification: CANDIDATE_FOR_REMOVAL.
- Runtime participation: No.
- Test/certification only: Yes.
- Notes: Current contents show repeated date/status-like output with zero signals and pending state.

### `app.log`, `recovery_test.log`

- Responsibility: Workspace log artifacts.
- Classification: CANDIDATE_FOR_REMOVAL.
- Runtime participation: No.
- Notes: Logs are ignored by `.gitignore` and should not be source of truth.

## Application Entry

### `src/main/java/com/trading/scanner/ScannerApplication.java`

- Responsibility: Spring Boot entry point; enables scheduling and configuration properties scanning.
- Why it exists: Starts application context.
- Classification: ESSENTIAL.
- Dependents: runtime.
- Runtime participation: Yes.
- Test/certification only: No.

## Controllers

### `src/main/java/com/trading/scanner/controller/DashboardController.java`

- Responsibility: Serves dashboard, `/status`, and `/health`.
- Why it exists: Read-facing UI/API.
- Classification: ESSENTIAL for web read interface.
- Depends on: `ExecutionStateService`, universe/price/result repositories, `ExchangeConfiguration`, `AppInfo`.
- Runtime participation: Yes.
- Notes: Does not expose the POST endpoints used by `dashboard.html`.

### `src/main/java/com/trading/scanner/controller/SimulationController.java`

- Responsibility: Simulation-only API for status, advance, reset.
- Classification: ESSENTIAL for simulation.
- Depends on: `SimulationStateRepository`, `SimulationCycleService`, `ExchangeClock`.
- Runtime participation: Yes, only under `simulation` profile.

### `src/main/java/com/trading/scanner/controller/GlobalExceptionHandler.java`

- Responsibility: Converts exceptions to JSON HTTP responses.
- Classification: SUPPORT.
- Depends on: Spring MVC.
- Runtime participation: Yes.
- Notes: Applies globally, including non-API requests such as missing `favicon.ico`, causing logged stack traces.

## Simulation Services

### `src/main/java/com/trading/scanner/service/simulation/SimulationCycleService.java`

- Responsibility: Orchestrates deterministic simulation cycles.
- Classification: ESSENTIAL but currently broken by missing dependency.
- Depends on: `SimulationStateRepository`, `TradingCalendar`, missing `DataIngestionService`, `ScannerEngine`, `ExecutionStateService`, `ForwardReturnEngine`.
- Runtime participation: Intended yes.
- Notes: Main transaction boundary for simulation. Uses serializable isolation and process lock.

### `src/main/java/com/trading/scanner/service/simulation/SimulationBatchResult.java`

- Responsibility: REST response record for batches.
- Classification: SUPPORT.
- Runtime participation: Yes in simulation API.

### `src/main/java/com/trading/scanner/service/simulation/SimulationCycleResult.java`

- Responsibility: Per-cycle result DTO.
- Classification: SUPPORT.
- Runtime participation: Yes in simulation API.

## Scanner Services

### `src/main/java/com/trading/scanner/service/scanner/ScannerEngine.java`

- Responsibility: Runs scans and persists signals.
- Classification: ESSENTIAL.
- Depends on: rule beans, repositories, indicator service, execution state service, exchange config, breakout properties.
- Runtime participation: Yes.
- Notes: Simulation entry is non-transactional by design, expecting caller transaction.

### `src/main/java/com/trading/scanner/service/scanner/rules/ScannerRule.java`

- Responsibility: Rule interface.
- Classification: ESSENTIAL.
- Dependents: `ScannerEngine`, `BreakoutConfirmedRule`.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/service/scanner/rules/BreakoutConfirmedRule.java`

- Responsibility: Single active scanner rule.
- Classification: ESSENTIAL.
- Depends on: `BreakoutRuleProperties`, `ObjectMapper`.
- Runtime participation: Yes.
- Notes: Uses deterministic parameter snapshot ordering, but metadata uses `HashMap.toString()`.

## Indicator Services

### `src/main/java/com/trading/scanner/service/indicators/IndicatorService.java`

- Responsibility: Computes RSI, SMA, average volume, ATR.
- Classification: ESSENTIAL.
- Depends on: `StockPrice`, `IndicatorParameters`, `IndicatorBundle`.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/service/indicators/IndicatorBundle.java`

- Responsibility: Data holder for indicators.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/service/indicators/parameters/IndicatorParameters.java`

- Responsibility: Immutable parameter carrier for indicator periods.
- Classification: SUPPORT.
- Runtime participation: Yes.

## Forward Return

### `src/main/java/com/trading/scanner/service/ForwardReturnEngine.java`

- Responsibility: Computes signal outcomes at 5/10/20 trading-day horizons.
- Classification: ESSENTIAL for research outcomes.
- Depends on: `TradingCalendar`, `ScanResultRepository`, `SignalOutcomeRepository`, `StockPriceRepository`.
- Runtime participation: Yes in simulation cycle.
- Notes: Comments say fixed horizons `{5, 10, 20}` but schema still has old forward return columns for 7/14/30 on `scan_results`.

## State Service

### `src/main/java/com/trading/scanner/service/state/ExecutionStateService.java`

- Responsibility: Per-date ingestion/scan state machine.
- Classification: ESSENTIAL.
- Depends on: `ScanExecutionStateRepository`, `ExchangeConfiguration`, `TimeProvider`.
- Runtime participation: Yes.
- Notes: Date-specific methods are transaction-neutral; legacy today methods are transactional.

## Calendar

### `src/main/java/com/trading/scanner/calendar/TradingCalendar.java`

- Responsibility: Interface for trading-day/session calculations.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/calendar/DefaultTradingCalendar.java`

- Responsibility: Calendar implementation over holidays, weekends, special sessions, emergency closures.
- Classification: ESSENTIAL.
- Depends on: `NseHolidayCalendar`.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/calendar/NseHolidayCalendar.java`

- Responsibility: Static NSE holidays for 2023-2026 and DB-backed emergency closures.
- Classification: ESSENTIAL.
- Depends on: `EmergencyClosureRepository`.
- Runtime participation: Yes.
- Notes: Holiday coverage ends at 2026; after that, weekends still work but exchange holidays do not.

### `src/main/java/com/trading/scanner/calendar/SessionType.java`

- Responsibility: Enum for trading/holiday/weekend/special/unexpected sessions.
- Classification: SUPPORT.
- Runtime participation: Yes.

## Configuration

### `src/main/java/com/trading/scanner/config/AppInfo.java`

- Responsibility: Exposes `app.name` and `app.version`.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/config/BreakoutRuleProperties.java`

- Responsibility: Binds and validates `rules.breakout.*`.
- Classification: ESSENTIAL for scanner startup.
- Runtime participation: Yes.
- Notes: Requires all fields; missing simulation rule properties will break binding if active.

### `src/main/java/com/trading/scanner/config/CalendarConfiguration.java`

- Responsibility: Creates `NseHolidayCalendar` and `TradingCalendar` beans.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/config/ExchangeClock.java`

- Responsibility: Central time provider for production and simulation.
- Classification: ESSENTIAL.
- Runtime participation: Yes.
- Notes: Simulation cache must be invalidated after offset changes; reset does this, advance currently does not call invalidation in controller.

### `src/main/java/com/trading/scanner/config/ExchangeConfiguration.java`

- Responsibility: Exposes exchange config, publish buffer, reload flags, validation/provider settings.
- Classification: SUPPORT / ESSENTIAL for time and state checks.
- Runtime participation: Yes.
- Notes: Several provider-related properties remain although provider source is absent.

### `src/main/java/com/trading/scanner/config/LocalDateConverter.java`

- Responsibility: Converts `LocalDate` to ISO text.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/config/LocalDateTimeConverter.java`

- Responsibility: Converts `LocalDateTime` to ISO text.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/config/ProductionClockConfig.java`

- Responsibility: Creates production/default `ExchangeClock`.
- Classification: ESSENTIAL for production/default startup.
- Runtime participation: Yes under `production` or default profile.

### `src/main/java/com/trading/scanner/config/SimulationClockConfig.java`

- Responsibility: Creates simulation `ExchangeClock`; initializes/validates simulation state.
- Classification: ESSENTIAL for simulation.
- Runtime participation: Yes under `simulation`.

### `src/main/java/com/trading/scanner/config/SimulationUniverseSeeder.java`

- Responsibility: Seeds 50 stocks in simulation if empty.
- Classification: SUPPORT.
- Runtime participation: Yes under `simulation`.
- Notes: Duplicates stock-universe seed data also present in `scripts/init_db.sql`.

### `src/main/java/com/trading/scanner/config/TimeProvider.java`

- Responsibility: Interface for central date/time.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/config/simulation/SimulationProperties.java`

- Responsibility: Binds `simulation.baseDate`.
- Classification: ESSENTIAL for simulation, risky in production.
- Runtime participation: Yes.
- Notes: It is a global `@Component`, so it may bind outside simulation. Existing production log shows startup failed when `simulation.baseDate` was absent.

## AOP Guard

### `src/main/java/com/trading/scanner/aop/SimulationExit.java`

- Responsibility: Marker annotation for methods forbidden in simulation.
- Classification: SUPPORT, intended determinism guard.
- Runtime participation: Only if annotated methods exist and AOP is active.
- Notes: No current source methods are annotated with it.

### `src/main/java/com/trading/scanner/aop/SimulationProviderGuardAspect.java`

- Responsibility: Throws if `@SimulationExit` method/class is called in simulation.
- Classification: SUPPORT, currently likely incomplete due missing AOP dependency and no annotated providers.
- Runtime participation: Intended yes.

## Entities

### `src/main/java/com/trading/scanner/model/StockPrice.java`

- Responsibility: OHLCV price entity.
- Classification: ESSENTIAL.
- Runtime participation: Yes.
- Tables: `stock_prices`.

### `src/main/java/com/trading/scanner/model/StockUniverse.java`

- Responsibility: Tradable universe entity.
- Classification: ESSENTIAL.
- Runtime participation: Yes.
- Tables: `stock_universe`.

### `src/main/java/com/trading/scanner/model/Exchange.java`

- Responsibility: Exchange enum, currently only `NSE`.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/model/ScanExecutionState.java`

- Responsibility: Per-trading-date state machine.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/model/ScanResult.java`

- Responsibility: Signal entity.
- Classification: ESSENTIAL.
- Runtime participation: Yes.
- Notes: Contains old forward return columns alongside newer `signal_outcomes` table.

### `src/main/java/com/trading/scanner/model/ScannerRun.java`

- Responsibility: Scan run summary.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/model/SimulationState.java`

- Responsibility: Single-row persisted simulation timeline.
- Classification: ESSENTIAL for simulation.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/model/SignalOutcome.java`

- Responsibility: Immutable forward return measurement.
- Classification: ESSENTIAL for outcomes.
- Runtime participation: Yes.

### `src/main/java/com/trading/scanner/model/EmergencyClosure.java`

- Responsibility: Dynamic exchange closure date.
- Classification: SUPPORT.
- Runtime participation: Yes through calendar.

## Repositories

### `EmergencyClosureRepository.java`

- Responsibility: CRUD and date existence/deletion for closures.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `ScanExecutionStateRepository.java`

- Responsibility: Find state by trading date.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `ScannerRunRepository.java`

- Responsibility: Run persistence and latest runs.
- Classification: SUPPORT.
- Runtime participation: Yes.

### `ScanResultRepository.java`

- Responsibility: Signal persistence and lookup by date/latest.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `SignalOutcomeRepository.java`

- Responsibility: Outcome persistence and eligibility query.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `SimulationStateRepository.java`

- Responsibility: Single-row simulation state persistence.
- Classification: ESSENTIAL for simulation.
- Runtime participation: Yes.

### `StockPriceRepository.java`

- Responsibility: Price persistence and ordered symbol/date queries.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

### `StockUniverseRepository.java`

- Responsibility: Universe persistence and active-symbol lookup.
- Classification: ESSENTIAL.
- Runtime participation: Yes.

## Resources

### `src/main/resources/application.properties`

- Responsibility: Production/default configuration.
- Classification: ESSENTIAL.
- Runtime participation: Yes.
- Notes: Defines complete breakout rule properties and production DB path.

### `src/main/resources/application-simulation.properties`

- Responsibility: Simulation profile configuration.
- Classification: ESSENTIAL but incomplete.
- Runtime participation: Yes under `simulation`.
- Notes: Missing most `rules.breakout.*` fields required by `BreakoutRuleProperties`.

### `src/main/resources/templates/dashboard.html`

- Responsibility: Thymeleaf dashboard UI.
- Classification: SUPPORT.
- Runtime participation: Yes.
- Notes: Calls `/ingest/historical`, `/ingest/daily`, and `/scan/execute`, which current controllers do not expose.

### `scripts/init_db.sql`

- Responsibility: SQL schema and seed data.
- Classification: ESSENTIAL/SUPPORT depending on whether DB is initialized manually or via Hibernate.
- Runtime participation: No direct runtime execution.
- Notes: Defines uniqueness and indexes not all visible in current simulation DB.

## Archive Scripts

Files:

- `archive/analyze_determinism.ps1`
- `archive/certification_matrix.ps1`
- `archive/cert_phases_b_to_e.ps1`
- `archive/cert_phase_a_raw.ps1`
- `archive/cert_tests.ps1`
- `archive/concurrency_test.ps1`
- `archive/final_determinism_analysis.ps1`
- `archive/generate_hostile_report.ps1`
- `archive/phase5_idempotency.ps1`
- `archive/phase6_concurrency.ps1`
- `archive/phase7_14_audit.ps1`
- `archive/phase9_persistence.ps1`
- `archive/query_baseline_state.ps1`
- `archive/run_252_days.ps1`
- `archive/test_ingest.ps1`
- `archive/test_scan.ps1`

Classification:

- CANDIDATE_FOR_REMOVAL or LEGACY.

Why they exist:

- Certification, determinism, replay, concurrency, and ingestion/scan tests from earlier phases.

Runtime participation:

- No.

Testing/certification:

- Yes.

Notes:

- Several scripts reference endpoints that current source does not expose.
- They may be useful historical evidence, but not runtime system components.

## Data And Generated Artifacts

### `data/market_scanner.db`, `data/market_scanner_sim.db`

- Responsibility: Local SQLite databases.
- Classification: SUPPORT runtime data, not source.
- Runtime participation: Yes when app is pointed at them.
- Notes: Should be treated as environment state, not architecture source.

### `data/logs/*`, `app.log`, `recovery_test.log`

- Responsibility: Runtime logs.
- Classification: CANDIDATE_FOR_REMOVAL from source control/workspace report perspective.
- Runtime participation: No as inputs.

### `target/*`

- Responsibility: Maven build outputs and jar.
- Classification: GENERATED / CANDIDATE_FOR_REMOVAL.
- Runtime participation: The jar can run, but it is not source.
- Notes: Contains compiled classes no longer present in source, including `service/data/*`. This is strong evidence of source/artifact drift.

==================================================
4️⃣ PACKAGE STRUCTURE ANALYSIS
==============================

## Current Package Organization

The package layout is broadly layered:

```text
com.trading.scanner
  aop
  calendar
  config
    simulation
  controller
  model
  repository
  service
    indicators
      parameters
    scanner
      rules
    simulation
    state
```

The intended missing package is:

```text
service.data
service.provider
scheduler
```

Compiled artifacts show `service.data` existed recently. Git status indicates `scheduler/DailyScanScheduler.java` and `service/provider/*` are deleted from the worktree.

## Layering Cleanliness

Layering is mostly clean:

- controllers delegate to services/repositories.
- services orchestrate repositories.
- repositories are Spring Data interfaces.
- models are JPA entities.
- calendar logic is isolated.
- time abstraction is centralized through `ExchangeClock`/`TimeProvider`.
- scanner rules are pluggable through `ScannerRule`.

The main layering issue is that `SimulationCycleService` depends directly on missing ingestion service and scanner/outcome services, making it a high-level orchestrator. That is acceptable for cycle orchestration but makes missing dependencies immediately fatal.

## Where Orchestration Exists

Primary orchestration:

- `SimulationCycleService`: full trading-day simulation cycle.
- `ScannerEngine`: per-date scan orchestration.
- `ExecutionStateService`: execution state transitions.
- `ForwardReturnEngine`: outcome computation orchestration.
- `SimulationClockConfig`: simulation startup state bootstrapping.

Secondary orchestration:

- `DashboardController`: read model assembly for UI.
- `CalendarConfiguration`: calendar bean wiring.

## Package Fragmentation

The current package split is reasonable for the intended architecture. It is not over-fragmented at the top level. The small DTO records under `service/simulation` are appropriate. The `indicators/parameters` subpackage is mildly granular for one record, but it documents the separation between indicator configuration and calculated bundles.

## Classes That Appear Unnecessarily Split

This audit does not recommend code changes, but structurally:

- `ExchangeConfiguration` and `ExchangeClock` are distinct for a good reason: properties vs time authority.
- `SimulationProperties` being both `@Component` and scanned by configuration properties may be redundant.
- `AppInfo` is small but useful for filtered Maven project metadata.
- `ScannerRun` and `ScanExecutionState` overlap conceptually but serve different purposes: audit summary vs state machine.
- `ScanResult` forward-return columns overlap with `SignalOutcome`; that is legacy schema residue.

==================================================
5️⃣ DATABASE ARCHITECTURE
=========================

## DB Engine

The system uses SQLite through:

- JDBC driver: `org.xerial:sqlite-jdbc:3.44.1.0`
- Hibernate dialect: `org.hibernate.community.dialect.SQLiteDialect`
- HikariCP single connection:
  - maximum pool size: 1
  - minimum idle: 1

Production DB:

- `jdbc:sqlite:data/market_scanner.db`

Simulation DB:

- `jdbc:sqlite:data/market_scanner_sim.db`

## Why SQLite Is Being Used

SQLite matches the current research-engine model:

- local deterministic persistence.
- easy reset/copy/compare of simulation databases.
- low operational overhead.
- suitable for single-process research workloads.
- works well with a single writer model.

## Current DB Limitations

- SQLite has limited write concurrency.
- Schema validation and Hibernate-generated schemas can diverge from `scripts/init_db.sql`.
- No migrations tool is present.
- No explicit WAL or busy timeout configuration is present.
- Foreign keys in SQLite require enforcement settings; the script declares a foreign key for `signal_outcomes`, but runtime enforcement is not proven here.
- Current simulation DB lacks some intended unique constraints/indexes visible in `scripts/init_db.sql`.
- Static holiday data ends at 2026, which affects future simulations.

## Transaction Model Implications

- Single Hikari connection helps avoid concurrent SQLite writers.
- `SimulationCycleService.advanceSimulation()` wraps an entire batch in one serializable transaction.
- A large `days=N` batch can become one large transaction.
- If any cycle returns failure, the service throws and rolls back the entire batch.
- `ForwardReturnEngine` catches per-outcome failures, so those failures do not roll back the batch.
- `ScannerEngine.executeDailyScan()` has its own transaction in production/daily mode.
- `ScannerEngine.executeScanForDate()` relies on caller transaction.

## Tables

### `stock_prices`

Purpose:

- Stores OHLCV daily price bars per symbol/date.

Columns from `scripts/init_db.sql`:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `symbol TEXT NOT NULL`
- `date TEXT NOT NULL`
- `open_price REAL`
- `high_price REAL`
- `low_price REAL`
- `close_price REAL`
- `adj_close REAL`
- `volume INTEGER`

Constraints:

- primary key: `id`
- unique: `(symbol, date)` in SQL script
- indexes: `idx_symbol_date(symbol, date)`, `idx_date(date)`

Runtime simulation DB observation:

- Hibernate-created table currently shows primary key but not the SQL-script unique/index declarations.

Writers:

- Intended: `DataIngestionService` (missing source).
- Possibly historical/manual SQL imports.

Readers:

- `ScannerEngine`
- `ForwardReturnEngine`
- `StockPriceRepository`
- dashboard count

Important column meanings:

- `symbol`: NSE stock symbol.
- `date`: trading date of the price bar, stored as ISO text.
- `open_price`: opening price.
- `high_price`: intraday high.
- `low_price`: intraday low.
- `close_price`: raw close.
- `adj_close`: adjusted close used by indicators and breakout logic.
- `volume`: daily traded volume.

Mutation model:

- Intended append/update-by-identity table. Idempotency depends on `(symbol, date)`.

### `scan_execution_state`

Purpose:

- Per-trading-date state machine for ingestion and scan execution.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `trading_date TEXT NOT NULL UNIQUE`
- `ingestion_status TEXT NOT NULL`
- `scan_status TEXT NOT NULL`
- `data_source_status TEXT`
- `execution_mode TEXT`
- `last_ingestion_time TEXT`
- `last_scan_time TEXT`
- `stocks_ingested INTEGER`
- `signals_generated INTEGER`
- `error_message TEXT`

Constraints:

- primary key: `id`
- unique: `trading_date`
- Hibernate runtime schema includes enum check constraints.

Writers:

- `ExecutionStateService`
- missing `DataIngestionService` likely through `ExecutionStateService`
- `ScannerEngine` through `ExecutionStateService`

Readers:

- `ExecutionStateService`
- `SimulationCycleService`
- `DashboardController`

Important column meanings:

- `trading_date`: the date whose workflow is tracked.
- `ingestion_status`: `PENDING`, `IN_PROGRESS`, `SUCCESS`, `SUCCESS_NO_DATA`, `FAILED`, `SKIPPED`.
- `scan_status`: same enum for scanner stage.
- `data_source_status`: `HEALTHY`, `NO_DATA`, `DEGRADED`, `UNAVAILABLE`, `UNKNOWN`.
- `execution_mode`: `MANUAL`, `SCHEDULED`, `API`.
- `last_ingestion_time`: authoritative time from `TimeProvider`.
- `last_scan_time`: authoritative time from `TimeProvider`.
- `stocks_ingested`: number of stocks for which data was ingested.
- `signals_generated`: number of signals produced on scan.
- `error_message`: failure or skip reason.

Mutation model:

- Mutable state row per trading date.

### `scanner_runs`

Purpose:

- Audit/log table summarizing each scanner execution.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `run_date TEXT NOT NULL`
- `stocks_scanned INTEGER`
- `stocks_flagged INTEGER`
- `status TEXT`
- `error_message TEXT`

Constraints:

- primary key: `id`
- no unique run-date constraint in script.

Writers:

- `ScannerEngine`

Readers:

- `ScannerRunRepository`
- currently no controller uses latest runs.

Important column meanings:

- `run_date`: scan date.
- `stocks_scanned`: number of active stocks with price history.
- `stocks_flagged`: number of matched signals.
- `status`: run outcome, currently `SUCCESS`.
- `error_message`: failure detail if used.

Mutation model:

- Append-only audit summary.

### `scan_results`

Purpose:

- Stores generated scanner signals.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `symbol TEXT NOT NULL`
- `scan_date TEXT NOT NULL`
- `rule_name TEXT NOT NULL`
- `confidence REAL`
- `scanner_version TEXT`
- `rule_version TEXT`
- `parameter_snapshot TEXT`
- `metadata TEXT`
- `forward_return_7d REAL`
- `forward_return_14d REAL`
- `forward_return_30d REAL`

Constraints:

- primary key: `id`
- intended unique index: `idx_signal_identity(symbol, scan_date, rule_name)`
- index: `idx_scan_date(scan_date)`

Runtime simulation DB observation:

- Current Hibernate-created table did not show `idx_signal_identity`.

Writers:

- `ScannerEngine`

Readers:

- `DashboardController`
- `ForwardReturnEngine`
- `SignalOutcomeRepository.findEligibleSignalIds`
- `ScanResultRepository`

Important column meanings:

- `symbol`: stock that matched.
- `scan_date`: signal generation date.
- `rule_name`: human-readable rule identity.
- `confidence`: confidence score from rule.
- `scanner_version`: application version at signal creation.
- `rule_version`: version of the rule implementation.
- `parameter_snapshot`: serialized rule parameters used for this signal; critical for later reproducibility.
- `metadata`: rule-specific data such as close, volume, RSI, SMA.
- `forward_return_7d`, `forward_return_14d`, `forward_return_30d`: legacy inline outcome columns; newer outcome path uses `signal_outcomes` with 5/10/20 horizons.

Mutation model:

- Intended append-only signal table, idempotent by `(symbol, scan_date, rule_name)`.

### `emergency_closure`

Purpose:

- Stores unexpected exchange closures that override normal trading sessions.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `date TEXT NOT NULL UNIQUE`
- `reason TEXT`
- `created_at TEXT NOT NULL`

Constraints:

- primary key: `id`
- unique: `date`

Writers:

- `NseHolidayCalendar.markEmergencyClosure`
- `EmergencyClosureRepository.deleteByDate`

Readers:

- `NseHolidayCalendar.isEmergencyClosure`
- `DefaultTradingCalendar`

Important column meanings:

- `date`: closed trading date.
- `reason`: human-entered closure reason.
- `created_at`: timestamp of closure marking.

Mutation model:

- Mutable reference table; closures can be added or cleared.

### `stock_universe`

Purpose:

- Defines tradable securities.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `symbol TEXT NOT NULL UNIQUE` in SQL script
- `exchange TEXT NOT NULL`
- `company_name TEXT`
- `sector TEXT`
- `is_active BOOLEAN DEFAULT 1`

JPA constraints:

- unique `(symbol, exchange)`.
- `company_name` non-null in entity.

Writers:

- `scripts/init_db.sql`
- `SimulationUniverseSeeder`
- possible manual admin changes.

Readers:

- `ScannerEngine`
- `DashboardController`
- missing ingestion service likely.

Important column meanings:

- `symbol`: stock ticker.
- `exchange`: enum, currently `NSE`.
- `company_name`: display/company identity.
- `sector`: broad classification.
- `is_active`: whether scanner includes the stock.

Mutation model:

- Mutable reference table.

### `simulation_state`

Purpose:

- Single-row persisted simulation timeline.

Columns:

- `id INTEGER PRIMARY KEY`
- `version INTEGER`
- `base_date TEXT NOT NULL`
- `trading_offset INTEGER NOT NULL DEFAULT 0`
- `is_cycling INTEGER NOT NULL DEFAULT 0`
- `cycling_started_at TEXT`

JPA current entity:

- `id`
- `version`
- `base_date`
- `trading_offset`

The SQL script still includes `is_cycling` and `cycling_started_at`; current entity does not. The comment in `SimulationCycleService.validateState()` says the `isCycling` flag was removed.

Constraints:

- primary key: `id`
- single-row convention: `id = 1`
- optimistic locking via `@Version` on `version`

Writers:

- `SimulationClockConfig` initializes if absent.
- `SimulationCycleService.advanceSimulation`
- `SimulationCycleService.resetSimulation`

Readers:

- `SimulationController`
- `SimulationCycleService`
- `ExchangeClock`
- `SimulationClockConfig`

Important column meanings:

- `id`: fixed singleton row.
- `version`: optimistic locking field.
- `base_date`: initial simulated trading date.
- `trading_offset`: number of trading days advanced from base date.
- `is_cycling`: legacy SQL field, no longer mapped.
- `cycling_started_at`: legacy SQL field, no longer mapped.

Mutation model:

- Mutable singleton state.

### `signal_outcomes`

Purpose:

- Stores immutable forward return measurements for generated signals.

Columns:

- `id INTEGER PRIMARY KEY AUTOINCREMENT`
- `signal_id INTEGER NOT NULL`
- `horizon_days INTEGER NOT NULL`
- `entry_price REAL NOT NULL`
- `exit_price REAL NOT NULL`
- `forward_return REAL NOT NULL`
- `mfe REAL`
- `mae REAL`
- `computed_at TEXT NOT NULL`

Constraints:

- primary key: `id`
- foreign key: `signal_id REFERENCES scan_results(id)` in SQL script
- unique: `(signal_id, horizon_days)` in SQL script and JPA entity
- indexes: `idx_signal_outcomes_signal_id`, `idx_signal_outcomes_horizon`

Runtime simulation DB observation:

- Current Hibernate-created table did not show the SQL-script unique constraint or indexes.

Writers:

- `ForwardReturnEngine`

Readers:

- `SignalOutcomeRepository`
- future analytics not present.

Important column meanings:

- `signal_id`: generated signal measured.
- `horizon_days`: trading-day horizon, currently 5, 10, or 20.
- `entry_price`: close price on signal date.
- `exit_price`: close price on exit date.
- `forward_return`: `(exit_price - entry_price) / entry_price`.
- `mfe`: maximum favorable excursion, reserved/not computed currently.
- `mae`: maximum adverse excursion, reserved/not computed currently.
- `computed_at`: simulated/current date-time when outcome was computed.

Mutation model:

- Intended append-only/immutable. Entity is annotated `@Immutable`.

==================================================
6️⃣ ONE FULL TRADING DAY TRACE
==============================

This trace describes the intended current simulation cycle, while explicitly noting the missing source file that prevents source-level execution.

## Starting Condition

Assume:

- app runs with `--spring.profiles.active=simulation`.
- DB path is `data/market_scanner_sim.db`.
- `simulation_state` has `id=1`, `base_date=2024-02-19`, `trading_offset=0`.
- `stock_universe` is seeded with 50 active NSE stocks.
- required price data source exists through missing `DataIngestionService`.
- breakout rule properties are successfully bound.

Current inspected simulation DB actually has:

- `stock_prices`: 0 rows
- `scan_execution_state`: 0 rows
- `scanner_runs`: 0 rows
- `scan_results`: 0 rows
- `signal_outcomes`: 0 rows
- `stock_universe`: 50 rows
- `simulation_state`: one row, `1|0|0|2024-02-19`

## Step 1: API Request

Request:

```text
POST /simulation/advance?days=1
```

Method:

```text
SimulationController.advanceCycles(int days)
```

Behavior:

- Spring routes the request only if the simulation profile is active.
- `days` defaults to 1 if not supplied.
- Controller calls `simulationCycleService.advanceSimulation(days)`.

Transaction:

- No transaction at controller.
- Transaction begins in service.

Failure behavior:

- Validation exceptions become HTTP 400 or 409 through `GlobalExceptionHandler`.
- Unexpected exceptions become HTTP 500 with generic JSON body and logged stack trace.

## Step 2: Batch Transaction Opens

Method:

```text
SimulationCycleService.advanceSimulation(1)
```

Annotations:

```text
@Transactional(isolation = Isolation.SERIALIZABLE)
```

Behavior:

1. Enters `synchronized (advanceLock)`.
2. Validates `days >= 0`.
3. Validates `days <= 2000`.
4. Loads `SimulationState` row id `1`.
5. Calls `validateState(state)`, currently empty.
6. Computes `lastDate = tradingCalendar.addTradingDays(baseDate, tradingOffset)`.

For base date 2024-02-19 and offset 0:

- `lastDate = 2024-02-19`.

DB reads:

- `simulation_state`
- `emergency_closure` may be queried by calendar.

Mutations:

- none yet.

Deterministic guarantees:

- Serializable transaction.
- JVM-level lock.
- calendar-aware current date.

## Step 3: Determine Next Trading Day

Method:

```text
TradingCalendar.nextTradingDay(lastDate)
```

Implementation:

```text
DefaultTradingCalendar.nextTradingDay(2024-02-19)
```

Behavior:

- Starts at `2024-02-20`.
- Checks emergency closure.
- Checks special session.
- Checks static holiday.
- Checks weekend.
- Returns first trading session.

For this trace:

- next cycle date is `2024-02-20`.
- target offset is `1`.

DB reads:

- `emergency_closure` for candidate dates.

Mutations:

- none.

Deterministic guarantee:

- static calendar plus DB emergency closures.

## Step 4: Cycle Start Logging

Method:

```text
runSingleCycleInMemory(2024-02-20, 1)
```

Behavior:

- captures `startTime = System.currentTimeMillis()`.
- generates `cycleId = UUID.randomUUID().toString().substring(0, 8)`.
- logs:

```text
CYCLE_START cycleId=<random> offset=1 date=2024-02-20
```

Determinism note:

- `cycleId` and duration are non-deterministic log/response fields.
- They do not affect DB business state except response duration.

## Step 5: Ingestion

Method call:

```text
dataIngestionService.ingestSimulatedDailyData(cycleDate, ExecutionMode.MANUAL)
```

Current source status:

- `DataIngestionService` source is missing.
- This call cannot be verified from current source.

Intended behavior:

1. Create or load `scan_execution_state` for `2024-02-20`.
2. Mark ingestion `IN_PROGRESS`.
3. Produce/load daily OHLCV rows for active stocks.
4. Save `stock_prices` rows.
5. Mark ingestion `SUCCESS` with `stocks_ingested > 0` and source status, or `SUCCESS_NO_DATA` if no data.

Tables expected to mutate:

- `scan_execution_state`
- `stock_prices`

Tables expected to read:

- `stock_universe`
- maybe existing `stock_prices`

Transaction:

- Should participate in outer serializable transaction unless it declares a separate propagation.

Rollback behavior:

- If it throws, the entire simulation batch rolls back.
- `simulation_state.trading_offset` is not advanced.
- inserted prices and state changes roll back.

Deterministic guarantee:

- Depends entirely on ingestion implementation.
- If simulation data is deterministic and live provider calls are blocked, replay is deterministic.
- If ingestion reads wall clock or external provider in simulation, replay is not deterministic.

## Step 6: Re-read Execution State After Ingestion

Method:

```text
executionStateService.getOrCreateState(cycleDate)
```

Behavior:

- Reads `scan_execution_state` by `trading_date`.
- If absent, creates a PENDING/PENDING row.
- Determines `ingestedCount = stocksIngested != null ? stocksIngested : 0`.
- Logs:

```text
CYCLE_INGEST_COMPLETE cycleId=<id> offset=1 ingested=<count>
```

Mutation risk:

- If ingestion failed silently and did not create state, this call creates a new PENDING state. Then scanner will not run because ingestion is not SUCCESS.

## Step 7: Scan Gate

Method:

```text
scannerEngine.executeScanForDate(cycleDate)
```

Internal method:

```text
executeScanLogic(scanDate)
```

First behavior:

```text
if (!executionStateService.canScanForDate(scanDate)) return;
```

`canScanForDate` requires:

- ingestion status is `SUCCESS`.
- `stocksIngested > 0`.
- scan status is `PENDING` or `FAILED`.

If gate fails:

- scanner logs "Cannot scan..."
- no signals or scanner run are written.
- cycle still continues to forward return computation.
- `SimulationCycleResult.success` remains true.

If gate passes:

- scanner proceeds.

## Step 8: Mark Scan In Progress

Method:

```text
executionStateService.startScanForDate(scanDate)
```

Behavior:

- loads state.
- sets `scan_status=IN_PROGRESS`.
- sets `last_scan_time=timeProvider.nowDateTime()`.

In simulation:

- `ExchangeClock.nowDateTime()` returns simulated date at `09:15`.

DB mutations:

- updates `scan_execution_state`.

## Step 9: Active Universe Load

Method:

```text
universeRepository.findByIsActiveTrue()
```

Behavior:

- loads active stocks.

DB reads:

- `stock_universe`.

Determinism note:

- No explicit ordering. Current DB insertion order likely controls iteration, but deterministic ordering would be stronger if explicit. This report only observes, not prescribes.

## Step 10: Build Indicator Parameters

Source:

```text
BreakoutRuleProperties
```

Behavior:

- creates `IndicatorParameters` with RSI/SMA windows.

Fields:

- `rsiPeriod`
- `smaShortPeriod`
- `smaMediumPeriod`
- `smaLongPeriod`

Determinism:

- Fully config-driven if property binding succeeds.

## Step 11: Per-Stock Price History

Method:

```text
priceRepository.findBySymbolAndDateLessThanEqualOrderByDateAsc(symbol, scanDate)
```

Behavior:

- loads all price bars up to and including scan date.
- sorted ascending by date.

DB reads:

- `stock_prices`.

If prices empty:

- logs debug.
- skips stock.

If prices non-empty:

- increments `scannedCount`.

## Step 12: Indicator Calculation

Method:

```text
indicatorService.calculateIndicators(prices, indicatorParameters)
```

Behavior:

- checks enough history.
- calculates RSI if enough data.
- calculates SMA20 and average volume if enough data.
- calculates SMA50/SMA200 if enough data.
- sets above-SMA booleans using latest adjusted close.

Side effects:

- logs debug/info messages.

DB:

- no direct DB access.

Determinism:

- pure over ordered price list and parameters.

## Step 13: Rule Matching

Method:

```text
BreakoutConfirmedRule.matches(symbol, prices, indicators)
```

Checks:

1. enough price history for `lookbackWindow`.
2. latest `adjClose` and `volume` are non-null.
3. RSI, SMA20, and average volume are present.
4. recent high is max high over lookback window excluding current day.
5. today's adjusted close is above recent high.
6. gap percent is below `maxGap`.
7. volume is above average volume times `volumeMultiplierMatch`.
8. RSI is above `rsiThresholdMatch`.
9. price is above SMA20.

If true:

- confidence is computed.
- metadata is built.
- signal object is created.

## Step 14: Signal Construction

Entity:

```text
ScanResult
```

Fields set:

- `symbol`
- `scanDate`
- `ruleName`
- `ruleVersion`
- `parameterSnapshot`
- `confidence`
- `scannerVersion`
- `metadata`

Business meaning:

- This is the immutable decision record for "this rule fired for this stock on this date under these parameters."

DB mutation:

- not saved immediately; added to local list.

## Step 15: Persist Signals

Method:

```text
resultRepository.saveAll(results)
```

DB mutations:

- inserts into `scan_results`.

Idempotency:

- Intended through unique index `(symbol, scan_date, rule_name)`.
- This depends on DB schema actually having the unique index.

Failure:

- If unique constraint exists and duplicate signal is inserted, save fails and outer transaction rolls back unless caught.

## Step 16: Persist Scanner Run

Entity:

```text
ScannerRun
```

Fields:

- `runDate`
- `stocksScanned`
- `stocksFlagged`
- `status=SUCCESS`

DB mutation:

- inserts into `scanner_runs`.

Mutation model:

- append-only. Re-running same date can create another run row unless prevented elsewhere.

## Step 17: Mark Scan Complete

Method:

```text
executionStateService.completeScanForDate(scanDate, flaggedCount)
```

Behavior:

- sets `scan_status=SUCCESS`.
- sets `signals_generated=flaggedCount`.
- sets `last_scan_time=TimeProvider.nowDateTime()`.

DB mutation:

- updates `scan_execution_state`.

## Step 18: Re-read State After Scan

Method:

```text
executionStateService.getOrCreateState(cycleDate)
```

Behavior:

- loads updated state.
- reads `signalsGenerated`.
- logs:

```text
CYCLE_SCAN_COMPLETE cycleId=<id> offset=1 signals=<count>
```

## Step 19: Forward Outcome Computation

Method:

```text
forwardReturnEngine.computeEligibleOutcomes(cycleDate)
```

Behavior:

- loops horizons `{5, 10, 20}`.
- for each horizon:
  - cutoff date = `tradingCalendar.addTradingDays(currentDate, -horizon)`.
  - query eligible signal IDs with `scanDate <= cutoffDate` and no existing outcome.
  - for each eligible signal:
    - load signal.
    - load entry price on signal date.
    - compute exit date = signal date + horizon trading days.
    - load exit price.
    - compute forward return.
    - save `SignalOutcome`.

Tables read:

- `scan_results`
- `stock_prices`
- `signal_outcomes`
- `emergency_closure` indirectly via calendar

Tables written:

- `signal_outcomes`

Failure:

- Per-signal exceptions are caught and logged.
- They do not roll back the batch.

Deterministic guarantee:

- Fixed horizon list.
- trading calendar arithmetic.
- uniqueness/eligibility query prevents duplicate outcomes if DB constraints and query agree.

## Step 20: Cycle End

Behavior:

- calculates duration using wall clock.
- logs:

```text
CYCLE_END cycleId=<id> offset=1 durationMs=<duration>
```

Returns:

```text
SimulationCycleResult(
  tradingOffset=1,
  cycleDate=2024-02-20,
  stocksIngested=<count>,
  signalsGenerated=<count>,
  durationMs=<duration>,
  success=true,
  failureReason=null
)
```

Determinism note:

- duration is non-deterministic response metadata.

## Step 21: Advance Simulation Offset

After all requested cycles succeed:

```text
state.setTradingOffset(state.getTradingOffset() + days)
simulationStateRepository.save(state)
```

DB mutation:

- updates `simulation_state.trading_offset`.
- `version` should increment through optimistic locking.

Commit:

- On method return, Spring commits the transaction.

Important cache note:

- `ExchangeClock.invalidateSimulationCache()` is called on reset by controller, but not after `advance`. If `ExchangeClock.today()` was called earlier and cached the old offset, `/simulation/status` after advance may return a stale current date until cache invalidation or process state changes. This is a current-state risk.

## What Gets Committed

On success:

- price rows from ingestion.
- execution state row updates.
- scan results.
- scanner run row.
- signal outcomes.
- updated simulation offset.

## What Rolls Back On Failure

If an exception propagates from ingestion, scan persistence, state update, calendar, or simulation state save:

- entire batch transaction rolls back.
- no offset advance persists.
- previous cycles in the same batch roll back too.

Exceptions inside `ForwardReturnEngine.computeOutcome` are swallowed per signal, so those individual outcome failures do not roll back the cycle.

==================================================
7️⃣ DETERMINISM & IDEMPOTENCY AUDIT
===================================

## How Determinism Is Enforced

Current deterministic mechanisms:

- Central clock abstraction through `ExchangeClock`.
- Simulation date derived from `simulation_state.base_date + trading_offset`.
- Fixed simulation time `09:15`.
- Trading-day arithmetic through `TradingCalendar`.
- Static holiday set for 2023-2026 plus DB emergency closures.
- Serialized simulation advancement with `advanceLock`.
- Serializable transaction on simulation advancement.
- Single Hikari connection for SQLite.
- State-machine gating through `scan_execution_state`.
- Ordered price histories from repository query.
- Rule parameter snapshots serialized with sorted keys.
- Hardcoded forward return horizons.
- Outcome eligibility excludes already-computed signal/horizon pairs.

## How Idempotency Is Enforced

Intended idempotency mechanisms:

- `scan_execution_state.trading_date` unique.
- `stock_prices(symbol, date)` unique.
- `scan_results(symbol, scan_date, rule_name)` unique index.
- `signal_outcomes(signal_id, horizon_days)` unique.
- `canIngestForDate`: only pending/failed ingestion can run.
- `canScanForDate`: only pending/failed scan after successful data can run.
- `findEligibleSignalIds`: excludes outcomes already present.

Actual current-state concerns:

- Current simulation DB does not visibly include all intended unique indexes.
- `ScannerRun` has no idempotent identity and can append duplicates.
- `ExecutionStateService.getOrCreateState` is not transactional in date-specific form unless called inside a transactional service.
- Missing ingestion source prevents verifying price-upsert/idempotent behavior.

## Unique Constraints

From SQL script:

- `stock_prices`: `UNIQUE(symbol, date)`
- `scan_execution_state`: `trading_date TEXT NOT NULL UNIQUE`
- `scan_results`: unique index `idx_signal_identity(symbol, scan_date, rule_name)`
- `emergency_closure`: `date TEXT NOT NULL UNIQUE`
- `stock_universe`: `symbol TEXT NOT NULL UNIQUE`
- `simulation_state`: primary key `id`
- `signal_outcomes`: `UNIQUE(signal_id, horizon_days)`

From JPA:

- `StockPrice`: unique `(symbol, date)`
- `ScanExecutionState`: unique `trading_date`
- `EmergencyClosure`: unique `date`
- `StockUniverse`: unique `(symbol, exchange)`
- `SimulationState`: primary key `id`
- `SignalOutcome`: unique `(signal_id, horizon_days)`

Mismatch:

- SQL script uses `stock_universe.symbol` unique; JPA uses `(symbol, exchange)`.
- Runtime simulation DB schema created by Hibernate did not show all unique constraints in `.schema` output.

## Replay Guarantees

Strong:

- Simulation offset model is replay-friendly.
- Rule logic is deterministic over input data.
- Indicator calculations are deterministic over ordered input.
- Forward outcome calculations are deterministic over stored prices.

Weak:

- Missing ingestion source is unverifiable.
- Rule list order is not explicitly controlled but currently only one rule exists.
- Active stock order is not explicitly sorted.
- `metadata` uses a plain map string, not canonical JSON.
- Logs and response duration/cycleId are non-deterministic.

## Ordering Guarantees

Guaranteed:

- stock prices per symbol are ordered by date ascending in scanner query.
- recent dashboard signals are ordered by scan date descending.
- scanner runs latest query orders by run date descending.
- parameter snapshot keys are sorted.

Not guaranteed:

- active stock universe order.
- rule bean order if multiple rules are added.
- eligible signal IDs order in forward return query.

## Transaction Guarantees

- Full simulation batch uses serializable isolation.
- Reset uses serializable isolation.
- Daily production scan uses transaction.
- Per-date simulation scan participates in caller transaction.
- Repository operations participate in Spring transactions.
- Some state methods are not annotated and rely on caller context.

## Hidden-State Risks

- `ExchangeClock` simulation cache can become stale if not invalidated after advance.
- Compiled `target/` jar contains classes missing from source, creating false confidence.
- Runtime DB may not match SQL script.
- Existing local DB/log state may influence manual conclusions.

## Mutable-State Risks

- `simulation_state.trading_offset` is mutable global state.
- `scan_execution_state` rows are mutable.
- `stock_universe.is_active` changes scanner population.
- `emergency_closure` changes calendar behavior and thus all future offsets/outcomes.
- `stock_prices` may be overwritten or inserted depending on missing ingestion implementation.

## Wall-Clock Risks

- `SimulationCycleService` uses `System.currentTimeMillis()` for duration only.
- `UUID.randomUUID()` affects logs/response only.
- `EmergencyClosure.prePersist()` uses `LocalDateTime.now()` if caller does not provide createdAt.
- In production, `ExchangeClock` intentionally uses system clock.
- Missing ingestion/provider code may have wall-clock behavior that cannot be audited from source.

## Cache Risks

- `ExchangeClock` caches simulation date without checking DB offset after first read.
- Reset invalidates cache explicitly.
- Advance does not invalidate cache explicitly.
- Hibernate first-level cache exists inside transactions.
- No explicit app-level cache elsewhere.

## Concurrency Risks

- Process-local `advanceLock` protects one JVM only.
- SQLite single writer and single Hikari connection reduce local concurrency.
- `@Version` on `SimulationState` provides optimistic locking.
- Multiple application instances would not share `advanceLock`.
- `getOrCreateState` can race if called concurrently outside simulation transaction, relying on DB unique constraint.

## What Currently Protects System Integrity

- persisted state machine.
- DB uniqueness where actually present.
- serializable simulation transaction.
- single JDBC connection.
- central clock.
- calendar-aware date arithmetic.
- deterministic rule parameter snapshot.
- profile-gated simulation controller.
- global exception handler.

## Assumptions

- SQLite schema is initialized correctly before startup.
- `spring.jpa.hibernate.ddl-auto=validate` is honored against an already-correct schema.
- In simulation, all required properties are present.
- Missing ingestion/provider code would obey simulation determinism boundaries.
- Only one JVM advances simulation at a time.
- Static holiday set is valid for simulated dates.

==================================================
8️⃣ CONFIGURATION & RUNTIME MODEL
=================================

## Profiles

Profiles in source:

- `simulation`
- `production`
- `default`

Profile usage:

- `SimulationController`: `@Profile("simulation")`
- `SimulationClockConfig`: `@Profile("simulation")`
- `SimulationUniverseSeeder`: `@Profile("simulation")`
- `ProductionClockConfig`: `@Profile({"production", "default"})`

Important issue:

- `SimulationProperties` is not profile-gated. It is a `@Component`, so the app may try to bind `simulation.baseDate` even outside simulation. Existing `data/logs/scanner.log` shows startup failed in production/default because `simulation.baseDate` was null.

## Configuration Files

### `application.properties`

Major settings:

- server port `8080`
- app name
- production SQLite DB path
- Hibernate `ddl-auto=validate`
- Hikari pool size 1
- exchange timezone/open/close
- scheduler config
- provider config
- historical reload flags
- validation thresholds
- complete breakout rule config
- logging paths
- Maven-filtered app name/version

### `application-simulation.properties`

Major settings:

- simulation SQLite DB path
- Hibernate `ddl-auto=validate`
- Hibernate/Hikari debug logging
- simulation base date `2024-02-19`
- exchange timezone
- provider/historical/validation settings
- logging path

Issue:

- It does not define the full required `rules.breakout.*` set. It defines `rules.breakout.maxGap=0.05`, which also does not match kebab-case style used in production config.

## Runtime Assumptions

- Java 21.
- Maven available or wrapper usable.
- SQLite DB files exist with matching schema when `ddl-auto=validate`.
- Local filesystem has `data/` and `data/logs/`.
- App runs as a single local process.
- NSE market timezone is `Asia/Kolkata`.
- Simulation base date is a valid trading day.

## Startup Behavior

General:

1. `ScannerApplication.main` starts Spring Boot.
2. Config properties scan runs.
3. JPA initializes against SQLite.
4. Calendar beans initialize.
5. Clock config selects production/default or simulation clock.
6. Controllers/services/repositories initialize.
7. In simulation, `SimulationUniverseSeeder` seeds universe if empty.

Simulation-specific:

- `SimulationClockConfig` gets or creates `simulation_state` row id `1`.
- Validates base date is trading day.
- Creates simulation-aware `ExchangeClock`.

Production/default:

- `ProductionClockConfig` creates system-clock `ExchangeClock`.

Observed startup issue:

- Existing production log shows `SimulationProperties` binding failure because `simulation.baseDate` is absent in production config.

## DB Initialization Behavior

There are two competing models:

1. Manual SQL initialization through `scripts/init_db.sql`.
2. Hibernate schema validation/generation history.

Current properties use:

```text
spring.jpa.hibernate.ddl-auto=validate
```

So the intended runtime expects tables to already exist. `scripts/init_db.sql` is likely the authoritative schema initializer. However, current inspected `market_scanner_sim.db` looks Hibernate-created and not fully matching the SQL script constraints.

## Logging Behavior

Production:

- `logging.file.name=data/logs/scanner.log`
- root and app log level INFO.

Simulation:

- `logging.file.name=data/logs/scanner_simulation.log`
- Hibernate SQL DEBUG.
- Hibernate type TRACE.
- Hikari DEBUG.

Code logs:

- cycle start/ingest/scan/end.
- scanner start/complete.
- signal matches.
- debug-heavy indicator/rule messages.
- global unhandled exceptions.

Current log note:

- missing `favicon.ico` generates unhandled exception logs through `GlobalExceptionHandler`.

## Simulation Configuration

Key property:

```text
simulation.baseDate=2024-02-19
```

Simulation current date:

```text
TradingCalendar.addTradingDays(baseDate, tradingOffset)
```

Manual control:

- `POST /simulation/advance?days=N`
- `POST /simulation/reset`
- `GET /simulation/status`

Maximum batch:

- 2000 trading days.

## Path Assumptions

- DBs under `data/`.
- logs under `data/logs/`.
- SQL script under `scripts/init_db.sql`.
- dashboard template under `src/main/resources/templates/dashboard.html`.
- jar under `target/market-scanner-1.8.0.jar` after build.

## Environment Assumptions

- Local development/operation on filesystem with write access to `data`.
- Single machine.
- No external database server.
- Network only needed if provider source is restored for production ingestion.
- Simulation should not need network if deterministic ingestion source exists.

## How App Is Started

Production/default intended:

```text
java -jar target/market-scanner-1.8.0.jar
```

Simulation intended:

```text
java -jar target/market-scanner-1.8.0.jar --spring.profiles.active=simulation
```

Older docs reference outdated jar names.

## How Simulation Mode Works

Simulation mode:

- switches datasource to `market_scanner_sim.db`.
- activates simulation controller.
- creates simulation clock.
- seeds stock universe.
- maps "today" to persisted simulation state.
- advances by trading days on API request.

## How Current Execution Is Triggered

Currently present source triggers:

- Dashboard read via `GET /`.
- JSON status via `GET /status`.
- health via `GET /health`.
- simulation status/advance/reset via `/simulation/*`.

Absent current source triggers:

- `/ingest/historical`
- `/ingest/daily`
- `/scan/execute`
- scheduled daily scan execution.

==================================================
9️⃣ DEAD / LEGACY / REDUNDANT ARTIFACT AUDIT
============================================

Do not delete anything based on this section. This is identification only.

## Likely Unnecessary Or Legacy Files

### `README.md`

- Reason: Describes older v1.2 production/provider architecture and files no longer present.
- Classification: LEGACY.

### `QUICKSTART.md`

- Reason: References old jar names and endpoints/config not present in current source.
- Classification: LEGACY.

### `archive/*.ps1`

- Reason: Certification and phase scripts, not runtime artifacts.
- Classification: LEGACY / CANDIDATE_FOR_REMOVAL.
- Caveat: Keep if historical certification evidence is valuable.

### `run2_results.txt`, `run3_results.txt`, `run4_results.txt`

- Reason: Captured run outputs, not source.
- Classification: CANDIDATE_FOR_REMOVAL.

### `app.log`, `recovery_test.log`, `data/logs/*`

- Reason: Logs generated by runtime/tests.
- Classification: CANDIDATE_FOR_REMOVAL from repository hygiene perspective.

### `target/*`

- Reason: Generated build output.
- Classification: CANDIDATE_FOR_REMOVAL.
- Caveat: Currently contains compiled classes missing from source, so it is useful forensic evidence but not source truth.

### `data/*.db`

- Reason: Runtime data and local state.
- Classification: ENVIRONMENT ARTIFACT / CANDIDATE_FOR_REMOVAL from source perspective.
- Caveat: Do not delete unless intentionally resetting environment.

## Dead Or Inconsistent SQL/Schema Artifacts

### `simulation_state.is_cycling`, `simulation_state.cycling_started_at`

- Reason: SQL script defines them, current entity does not map them, service comment says flag removed.
- Classification: LEGACY schema fields.

### `scan_results.forward_return_7d/14d/30d`

- Reason: Current forward return engine writes `signal_outcomes` at 5/10/20 horizons.
- Classification: LEGACY columns.

### `stock_universe` uniqueness mismatch

- SQL: `symbol` unique.
- JPA: `(symbol, exchange)` unique.
- Classification: schema drift.

## Abandoned Or Missing Runtime Artifacts

These are not removable because they are already absent from source, but they are architecturally significant:

- `src/main/java/com/trading/scanner/service/data/DataIngestionService.java`
- `DataQualityService.java`
- `DataSourceHealthService.java`
- `DateSanityService.java`
- `src/main/java/com/trading/scanner/service/provider/*`
- `src/main/java/com/trading/scanner/scheduler/DailyScanScheduler.java`
- controller methods for ingestion and scan POST endpoints.

Git status shows many of these as deleted. The current source still references at least `DataIngestionService`.

## Obsolete Config

Potentially obsolete or currently unused:

- provider retry/circuit-breaker/publish buffer settings in properties, because provider source is absent.
- scheduler settings in properties, because scheduler source is absent.
- dashboard action buttons and JavaScript fetch calls, because matching endpoints are absent.
- `rules.breakout.maxGap` in simulation config, because property naming differs from production's `rules.breakout.max-gap` and full set is missing.

==================================================
🔟 ARCHITECTURAL RISK SUMMARY
=============================

## Biggest Current Strengths

- Clear deterministic simulation intent.
- Centralized time abstraction.
- Calendar-aware trading-day arithmetic.
- Strong separation between signal generation and outcome computation.
- Persisted execution state.
- Good use of JPA repositories for simple persistence.
- Rule parameter snapshotting.
- Single SQLite connection reduces accidental concurrent write issues.
- Serializable transaction on simulation advancement.
- Profile-gated simulation controller.

## Biggest Current Risks

- Current source tree is incomplete and likely not buildable.
- Runtime jar and source tree diverge.
- Missing ingestion source prevents verifying the most important deterministic boundary.
- Dashboard invokes endpoints absent from current controllers.
- Simulation config is incomplete for required breakout properties.
- `SimulationProperties` may break non-simulation startup.
- AOP guard likely lacks dependency and has no current annotated provider exits.
- Live DB schema may not enforce intended unique constraints.
- Simulation clock cache may become stale after advance.

## Most Fragile Areas

- Ingestion path, because source is absent.
- Schema initialization, because SQL script and Hibernate-created DB differ.
- Profile/property binding.
- Deterministic replay guarantees around missing provider/data logic.
- Dashboard-to-controller contract.
- Generated artifacts being mistaken for current source.

## Most Trusted Areas

- Entity/repository model, where source is present.
- Calendar logic within 2023-2026 range.
- Indicator calculations over ordered prices.
- Breakout rule logic over deterministic inputs.
- Forward return computation logic.
- Simulation transaction wrapper.

## Operational Concerns

- Need correct DB schema before startup.
- Need source/artifact alignment before trusting builds.
- Need logs and DB files managed outside source.
- Need single-instance operation unless cross-process locking is added elsewhere.
- Need enough price history before scanner can produce meaningful results.
- Need holiday calendar maintenance after 2026.

## Deployment Concerns

- Current production/default startup may fail due `SimulationProperties`.
- Current source lacks production ingestion/provider/scheduler path.
- `ddl-auto=validate` means deployment requires pre-created schema.
- SQLite local files must be backed up carefully.
- No authentication exists for endpoints.

## Maintainability Concerns

- Version drift across docs/config/schema/code.
- Legacy fields and docs obscure current truth.
- No tests in `src/test`.
- Archive scripts reference old endpoints.
- Missing source files make future work risky.
- Schema migrations are not formalized.

==================================================
1️⃣1️⃣ FINAL SYSTEM SUMMARY
===========================

## What This System Currently Is

It is a partially intact deterministic trading research engine built as a Spring Boot monolith over SQLite. The strongest current path is the simulation/scanner/outcome architecture: persisted simulation time, calendar-aware advancement, rule-based signal generation, and forward return measurement.

It is not currently a complete production market scanner in source form because ingestion, provider, scheduler, and some controller endpoints are absent.

## What Stage It Is At

The repository is in an intermediate refactor/recovery stage:

- later deterministic simulation architecture is present.
- older production-provider documentation remains.
- generated artifacts contain classes missing from source.
- current source references missing classes.
- DB/schema/config are not fully aligned.

## Whether It Is Production-Safe

No, not in the current source state.

Reasons:

- missing production data provider/ingestion/scheduler source.
- possible startup config failure.
- dashboard actions point to missing endpoints.
- schema drift and source/artifact drift.
- no current tests.

## Whether It Is Simulation-Safe

Not yet, not as current source.

The simulation design is strong, but actual simulation safety cannot be certified because:

- `DataIngestionService` is missing.
- simulation rule properties are incomplete.
- DB constraints may be incomplete in the live sim DB.
- cache invalidation after advance is questionable.

With those gaps resolved, the architecture is close to a credible deterministic simulation engine.

## Whether Owner Currently Understands Enough To Operate It Safely

The owner can understand the intended architecture from this report, but should not operate it as production infrastructure or rely on current simulation results without first reconciling source, config, and schema.

The safest current operating assumption is:

- The repository documents a deterministic research engine architecture.
- The current source tree is not yet an operationally complete implementation.

## What Should Happen NEXT Before Any New Features Are Added

Before any feature work, the next phase should be stabilization and reconciliation:

- restore or intentionally replace missing source files.
- align current source with the jar or discard stale build artifacts.
- align `application-simulation.properties` with required rule properties.
- reconcile SQL script, JPA annotations, and actual SQLite schema.
- verify build from clean source.
- verify simulation startup and one-day advance from clean DB.
- establish tests or certification scripts that match current endpoints.
- update docs to match current code.

No new trading features should be added until the source tree, runtime configuration, and database schema describe one coherent system.
