package com.trading.scanner.service.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.DailyStockContextRepository;
import com.trading.scanner.repository.EodDataEntryRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.repository.VolumeDailyBaselineRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.engine.DailyStockContextService;
import com.trading.scanner.service.engine.VolumeBaselineService;
import com.trading.scanner.service.instrument.MarketIndexRegistry;
import com.trading.scanner.service.provider.DailyBarDto;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pulls historical Angel One data into the production tables and derives everything the strategy and the
 * replay need, so replay can run on far more sessions than live capture ever recorded.
 *
 * Data rules:
 * - 1M candles go to market_candles and daily bars go to stock_prices, each stored exactly as delivered.
 *   Neither is used to correct or validate the other: the exchange day open (09:08 uncrossed price) and the
 *   official close are not derivable from 1M bars, and 1M volume legitimately differs from day volume.
 * - Existing rows are never overwritten; only absent symbol-days and absent daily rows are inserted.
 * - No candle is fabricated for quiet minutes. Thin symbols simply have fewer than 375 candles.
 * - Every raw response is archived on disk for debugging.
 *
 * Operation rules:
 * - One API call per symbol per session for 1M candles; calls are paced by the provider's historical
 *   endpoint limiter, shared with live reconciliation, so the rate limit is never exceeded.
 * - Progress is checkpointed per symbol-day in eod_data_entry, so a stopped run resumes where it left off.
 * - Refuses to run while the live pipeline is active (trading days 06:55-23:50) and stops itself if the
 *   window begins during a run.
 */
@Service
@Slf4j
public class HistoricalBackfillService {

    public static final String SOURCE = "HISTORICAL_API_BACKFILL";

    private static final String EXCHANGE = "NSE";
    private static final LocalTime OPENING_RANGE_LAST_MINUTE = LocalTime.of(9, 29, 59);
    private static final LocalTime QUIET_WINDOW_START = LocalTime.of(6, 55);
    private static final LocalTime QUIET_WINDOW_END = LocalTime.of(23, 50);
    private static final int MIN_SESSIONS_FOR_BASELINE = 20;
    private static final int FULL_BASELINE_SYMBOLS = 450;
    private static final int MAX_CONSECUTIVE_FAILURES = 25;
    /** Consecutive rate-limit rejections that trigger a cool-down instead of hammering on. */
    private static final int RATE_LIMIT_BURST = 5;
    /** Cool-downs in a row with not one successful call between them before the run gives up. */
    private static final int MAX_COOLDOWNS_WITHOUT_PROGRESS = 5;
    /** Retry rounds for symbol-days Angel One rejected as rate limited; the last round records real failures. */
    private static final int MAX_RETRY_ROUNDS = 3;
    private static final int DERIVE_THREADS = 3;
    private static final int MAX_PENDING_FINALIZERS = 2;

    private final TradingCalendar tradingCalendar;
    private final TimeProvider timeProvider;
    private final StockUniverseRepository stockUniverseRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final EodDataEntryRepository eodDataEntryRepository;
    private final EodDataEntryService eodDataEntryService;
    private final DailyDataStatusService dailyDataStatusService;
    private final LiveMarketCandleService liveMarketCandleService;
    private final DailyStockContextService dailyStockContextService;
    private final DailyStockContextRepository dailyStockContextRepository;
    private final VolumeBaselineService volumeBaselineService;
    private final VolumeDailyBaselineRepository volumeDailyBaselineRepository;
    private final MarketIndexRegistry marketIndexRegistry;
    private final AngelOneSessionService angelOneSessionService;

    @Autowired(required = false)
    private AngelOneMarketDataProvider provider;

    @Value("${backup.directory:backups}")
    private String backupDirectory;

    @Value("${runtime.historical-backfill.raw-dir:}")
    private String rawDirectory;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService runExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "hist-backfill-run"));
    private volatile Run current;
    /** Base cool-down after a rate-limit burst; multiplied by the number of cool-downs without progress. */
    private volatile long cooldownMillis = 180_000L;

    void setCooldownMillis(long cooldownMillis) {
        this.cooldownMillis = cooldownMillis;
    }

    public HistoricalBackfillService(
            TradingCalendar tradingCalendar,
            TimeProvider timeProvider,
            StockUniverseRepository stockUniverseRepository,
            StockPriceRepository stockPriceRepository,
            MarketCandleRepository marketCandleRepository,
            EodDataEntryRepository eodDataEntryRepository,
            EodDataEntryService eodDataEntryService,
            DailyDataStatusService dailyDataStatusService,
            LiveMarketCandleService liveMarketCandleService,
            DailyStockContextService dailyStockContextService,
            DailyStockContextRepository dailyStockContextRepository,
            VolumeBaselineService volumeBaselineService,
            VolumeDailyBaselineRepository volumeDailyBaselineRepository,
            MarketIndexRegistry marketIndexRegistry,
            AngelOneSessionService angelOneSessionService) {
        this.tradingCalendar = tradingCalendar;
        this.timeProvider = timeProvider;
        this.stockUniverseRepository = stockUniverseRepository;
        this.stockPriceRepository = stockPriceRepository;
        this.marketCandleRepository = marketCandleRepository;
        this.eodDataEntryRepository = eodDataEntryRepository;
        this.eodDataEntryService = eodDataEntryService;
        this.dailyDataStatusService = dailyDataStatusService;
        this.liveMarketCandleService = liveMarketCandleService;
        this.dailyStockContextService = dailyStockContextService;
        this.dailyStockContextRepository = dailyStockContextRepository;
        this.volumeBaselineService = volumeBaselineService;
        this.volumeDailyBaselineRepository = volumeDailyBaselineRepository;
        this.marketIndexRegistry = marketIndexRegistry;
        this.angelOneSessionService = angelOneSessionService;
    }

    public void setProvider(AngelOneMarketDataProvider provider) {
        this.provider = provider;
    }

    /**
     * @param sessions   number of NEW sessions to pull before the earliest session already stored (default 78)
     * @param toDate     last session to pull; default is the session before the earliest stored NIFTY candle
     * @param symbols    restrict to these symbols (dry runs and spot repairs); null/empty means everything
     * @param maxDates   stop after this many sessions (dry runs); null means no limit
     * @param dryRun     fetch and archive raw responses only; write nothing to the database
     * @param retryNoData re-call symbol-days that previously came back empty
     */
    public record RunRequest(
            Integer sessions,
            LocalDate toDate,
            List<String> symbols,
            Integer maxDates,
            boolean dryRun,
            boolean retryNoData) {
    }

    public record RunStatus(
            String runId,
            String state,
            String phase,
            String message,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDate currentDate,
            String currentSymbol,
            int sessionsTotal,
            int sessionsDone,
            long apiCalls,
            long candlesInserted,
            long dailyBarsInserted,
            long skippedAlreadyPresent,
            long noData,
            long failures,
            boolean dryRun,
            String rawArchiveDirectory,
            LocalDateTime startedAt,
            LocalDateTime finishedAt) {
    }

    /** Mutable state of one run; read through {@link #status()}. */
    private final class Run {
        final String runId;
        final RunRequest request;
        final LocalDateTime startedAt = timeProvider.nowDateTime();
        volatile LocalDateTime finishedAt;
        volatile String state = "RUNNING";
        volatile String phase = "PLANNING";
        volatile String message = "";
        volatile boolean stopRequested;
        volatile boolean aborted;
        volatile LocalDate fromDate;
        volatile LocalDate toDate;
        volatile LocalDate currentDate;
        volatile String currentSymbol;
        volatile int sessionsTotal;
        final AtomicInteger sessionsDone = new AtomicInteger();
        final AtomicLong apiCalls = new AtomicLong();
        final AtomicLong candlesInserted = new AtomicLong();
        final AtomicLong dailyBarsInserted = new AtomicLong();
        final AtomicLong skippedAlreadyPresent = new AtomicLong();
        final AtomicLong noData = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicInteger rateLimitStreak = new AtomicInteger();
        final AtomicInteger cooldownsWithoutProgress = new AtomicInteger();
        final AtomicLong rateLimitDeferrals = new AtomicLong();
        final Queue failureLog = new Queue();
        final Queue noDataLog = new Queue();
        final Queue dryRunLog = new Queue();
        Path rawRoot;

        Run(String runId, RunRequest request) {
            this.runId = runId;
            this.request = request;
        }

        RunStatus snapshot() {
            return new RunStatus(runId, state, phase, message, fromDate, toDate, currentDate, currentSymbol,
                    sessionsTotal, sessionsDone.get(), apiCalls.get(), candlesInserted.get(),
                    dailyBarsInserted.get(), skippedAlreadyPresent.get(), noData.get(), failures.get(),
                    request.dryRun(), rawRoot != null ? rawRoot.toString() : null, startedAt, finishedAt);
        }
    }

    /** Thread-safe append-only log of short entries, capped so a bad run cannot exhaust memory. */
    private static final class Queue {
        private final ConcurrentLinkedQueue<Map<String, Object>> items = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();

        void add(Map<String, Object> item) {
            if (size.incrementAndGet() <= 20_000) {
                items.add(item);
            }
        }

        List<Map<String, Object>> list() {
            return new ArrayList<>(items);
        }
    }

    public RunStatus status() {
        Run run = current;
        return run == null ? null : run.snapshot();
    }

    public synchronized RunStatus start(RunRequest request) {
        Run existing = current;
        if (existing != null && existing.finishedAt == null) {
            throw new IllegalStateException("A historical backfill is already running: " + existing.runId);
        }
        if (provider == null || !provider.isAvailable()) {
            throw new IllegalStateException("Angel One provider is unavailable (ANGELONE_ENABLED must be true)");
        }
        String quietViolation = quietWindowViolation();
        if (quietViolation != null) {
            throw new IllegalStateException(quietViolation);
        }
        RunRequest req = request != null ? request : new RunRequest(null, null, null, null, false, false);
        Run run = new Run("hb-" + timeProvider.nowDateTime().toString().replaceAll("[^0-9]", ""), req);
        run.rawRoot = rawRoot().resolve(run.runId);
        current = run;
        runExecutor.submit(() -> execute(run));
        return run.snapshot();
    }

    public RunStatus stop() {
        Run run = current;
        if (run != null && run.finishedAt == null) {
            run.stopRequested = true;
            run.message = "Stop requested; finishing the current symbol-day";
        }
        return run == null ? null : run.snapshot();
    }

    // ------------------------------------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------------------------------------

    private void execute(Run run) {
        ExecutorService derivePool = Executors.newFixedThreadPool(DERIVE_THREADS, r -> daemon(r, "hist-backfill-derive"));
        ExecutorService finalizePool = Executors.newSingleThreadExecutor(r -> daemon(r, "hist-backfill-finalize"));
        Semaphore pendingFinalizers = new Semaphore(MAX_PENDING_FINALIZERS);
        List<Future<?>> finalizers = new ArrayList<>();
        try {
            Files.createDirectories(run.rawRoot);

            LocalDate latest = tradingCalendar.previousTradingDay(timeProvider.today());
            // The default end point is anchored on the earliest candle NOT written by this backfill, so starting the
            // run again resumes the same range instead of extending it further into the past.
            LocalDateTime earliestNifty = marketCandleRepository.findEarliestCandleTimeExcludingSource(
                    "NIFTY", CandleTimeframe.ONE_MINUTE, SOURCE);
            LocalDate toDate = run.request.toDate() != null
                    ? run.request.toDate()
                    : (earliestNifty != null
                            ? tradingCalendar.previousTradingDay(earliestNifty.toLocalDate())
                            : latest);
            int sessions = run.request.sessions() != null ? run.request.sessions() : 78;
            LocalDate fromDate = tradingCalendar.addTradingDays(toDate, -(sessions - 1));

            List<LocalDate> dates = new ArrayList<>();
            for (LocalDate d = fromDate; !d.isAfter(latest); d = tradingCalendar.nextTradingDay(d)) {
                dates.add(d);
            }
            if (run.request.maxDates() != null && dates.size() > run.request.maxDates()) {
                dates = new ArrayList<>(dates.subList(0, run.request.maxDates()));
            }
            run.fromDate = fromDate;
            run.toDate = dates.isEmpty() ? toDate : dates.get(dates.size() - 1);
            run.sessionsTotal = dates.size();

            List<Target> targets = resolveTargets(run.request.symbols());
            log.info("[HIST_BACKFILL] run={} dryRun={} sessions={} {}..{} targets={} raw={}",
                    run.runId, run.request.dryRun(), dates.size(), fromDate, run.toDate, targets.size(), run.rawRoot);

            run.phase = "DAILY_BARS";
            pullDailyBars(run, targets, fromDate.minusDays(14), latest);

            run.phase = "MINUTE_CANDLES";
            int sessionIndex = 0;
            for (LocalDate date : dates) {
                if (shouldStop(run)) {
                    break;
                }
                run.currentDate = date;
                List<Future<?>> deriveTasks = new ArrayList<>();
                Set<String> alreadyHaveCandles = new LinkedHashSet<>(marketCandleRepository
                        .findDistinctSymbolsByTimeframeAndCandleTimeRange(
                                CandleTimeframe.ONE_MINUTE, date.atStartOfDay(), date.plusDays(1).atStartOfDay()));

                List<Target> deferred = new ArrayList<>();
                for (Target target : targets) {
                    if (shouldStop(run)) {
                        break;
                    }
                    run.currentSymbol = target.symbol();
                    if (pullMinuteCandles(run, target, date, alreadyHaveCandles, derivePool, deriveTasks, false)) {
                        deferred.add(target);
                    }
                }
                // Angel One rejects a share of calls as rate limited even far below its documented limits;
                // those symbol-days get one more attempt at the end of the session instead of being lost.
                List<Target> pending = deferred;
                for (int round = 1; round <= MAX_RETRY_ROUNDS && !pending.isEmpty() && !shouldStop(run); round++) {
                    boolean lastRound = round == MAX_RETRY_ROUNDS;
                    if (round > 1) {
                        pauseBetweenRounds(run);
                    }
                    List<Target> stillRejected = new ArrayList<>();
                    for (Target target : pending) {
                        if (shouldStop(run)) {
                            break;
                        }
                        run.currentSymbol = target.symbol();
                        if (pullMinuteCandles(run, target, date, alreadyHaveCandles, derivePool, deriveTasks, lastRound)) {
                            stillRejected.add(target);
                        }
                    }
                    pending = stillRejected;
                }

                if (!run.request.dryRun() && !shouldStop(run)) {
                    final LocalDate finalDate = date;
                    final int index = sessionIndex;
                    pendingFinalizers.acquire();
                    finalizers.add(finalizePool.submit(() -> {
                        try {
                            awaitAll(deriveTasks);
                            finalizeSession(run, finalDate, index, latest);
                        } finally {
                            pendingFinalizers.release();
                        }
                    }));
                }
                run.sessionsDone.incrementAndGet();
                sessionIndex++;
            }

            for (Future<?> f : finalizers) {
                f.get();
            }

            // The state only leaves RUNNING once the run has fully wound down, so a new run can never overlap it.
            run.phase = "DONE";
        } catch (Exception ex) {
            log.error("[HIST_BACKFILL] run={} failed: {}", run.runId, ex.getMessage(), ex);
            run.state = "FAILED";
            run.message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        } finally {
            derivePool.shutdown();
            finalizePool.shutdown();
            if ("RUNNING".equals(run.state)) {
                run.state = run.aborted ? "ABORTED" : (run.stopRequested ? "STOPPED" : "COMPLETED");
            }
            LocalDateTime end = timeProvider.nowDateTime();
            writeSummary(run, end);
            // Published last: a non-null finishedAt means the summary is on disk and nothing is still running.
            run.finishedAt = end;
        }
    }

    private boolean shouldStop(Run run) {
        if (run.stopRequested || run.aborted) {
            return true;
        }
        String violation = quietWindowViolation();
        if (violation != null) {
            run.aborted = true;
            run.message = violation + " Re-start the run later; progress is checkpointed per symbol-day.";
            log.warn("[HIST_BACKFILL] run={} stopping: {}", run.runId, run.message);
            return true;
        }
        return false;
    }

    /** The live pipeline owns the Angel One session, the live cache and the database on trading days. */
    private String quietWindowViolation() {
        LocalDateTime now = timeProvider.nowDateTime();
        if (!tradingCalendar.isTradingDay(now.toLocalDate())) {
            return null;
        }
        LocalTime t = now.toLocalTime();
        if (!t.isBefore(QUIET_WINDOW_START) && t.isBefore(QUIET_WINDOW_END)) {
            return "Historical backfill is blocked on trading days between " + QUIET_WINDOW_START + " and "
                    + QUIET_WINDOW_END + " because the live pipeline is active.";
        }
        return null;
    }

    private record Target(String symbol, boolean isIndex) {
    }

    private List<Target> resolveTargets(List<String> only) {
        Map<String, Target> targets = new LinkedHashMap<>();
        for (MarketIndexRegistry.IndexDefinition idx : marketIndexRegistry.getAllRegisteredIndices()) {
            if (idx != null && idx.canonicalSymbol() != null) {
                String sym = idx.canonicalSymbol().trim().toUpperCase(Locale.ROOT);
                targets.put(sym, new Target(sym, true));
            }
        }
        for (StockUniverse stock : stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()) {
            if (stock != null && stock.getSymbol() != null) {
                String sym = stock.getSymbol().trim().toUpperCase(Locale.ROOT);
                targets.put(sym, new Target(sym, false));
            }
        }
        if (only != null && !only.isEmpty()) {
            Set<String> wanted = new LinkedHashSet<>();
            only.forEach(s -> wanted.add(s.trim().toUpperCase(Locale.ROOT)));
            List<Target> picked = new ArrayList<>();
            for (String sym : wanted) {
                Target t = targets.get(sym);
                if (t != null) {
                    picked.add(t);
                }
            }
            return picked;
        }
        return new ArrayList<>(targets.values());
    }

    // ------------------------------------------------------------------------------------------------
    // Daily bars -> stock_prices
    // ------------------------------------------------------------------------------------------------

    private void pullDailyBars(Run run, List<Target> targets, LocalDate from, LocalDate to) {
        List<Target> deferred = new ArrayList<>();
        for (Target target : targets) {
            if (shouldStop(run)) {
                return;
            }
            run.currentSymbol = target.symbol();
            if (pullDailyBarsFor(run, target, from, to, false)) {
                deferred.add(target);
            }
        }
        List<Target> pending = deferred;
        for (int round = 1; round <= MAX_RETRY_ROUNDS && !pending.isEmpty() && !shouldStop(run); round++) {
            boolean lastRound = round == MAX_RETRY_ROUNDS;
            if (round > 1) {
                pauseBetweenRounds(run);
            }
            List<Target> stillRejected = new ArrayList<>();
            for (Target target : pending) { // calls Angel One rejected for rate limiting go back in the queue
                if (shouldStop(run)) {
                    return;
                }
                run.currentSymbol = target.symbol();
                if (pullDailyBarsFor(run, target, from, to, lastRound)) {
                    stillRejected.add(target);
                }
            }
            pending = stillRejected;
        }
    }

    private void pauseBetweenRounds(Run run) {
        sleepUnlessStopped(run, cooldownMillis);
    }

    /** @return true when the call was rate limited and should be retried later in this run */
    private boolean pullDailyBarsFor(Run run, Target target, LocalDate from, LocalDate to, boolean finalAttempt) {
        {
            try {
                List<DailyBarDto> bars = callWithSessionRefresh(run,
                        () -> provider.fetchHistoricalBars(target.symbol(), from, to));
                run.apiCalls.incrementAndGet();
                onApiSuccess(run);
                writeRaw(run, Paths.get("daily", target.symbol() + ".json"), bars);

                if (run.request.dryRun() || bars == null) {
                    return false;
                }
                for (DailyBarDto bar : bars) {
                    if (bar == null || bar.tradingDate() == null) {
                        continue;
                    }
                    if (stockPriceRepository.findBySymbolAndDate(target.symbol(), bar.tradingDate()).isPresent()) {
                        run.skippedAlreadyPresent.incrementAndGet();
                        continue;
                    }
                    stockPriceRepository.save(StockPrice.builder()
                            .symbol(target.symbol())
                            .date(bar.tradingDate())
                            .openPrice(bar.open())
                            .highPrice(bar.high())
                            .lowPrice(bar.low())
                            .closePrice(bar.close())
                            .adjClose(bar.adjustedClose())
                            .volume(bar.volume() != null ? (int) Math.min(Integer.MAX_VALUE, bar.volume()) : null)
                            .build());
                    run.dailyBarsInserted.incrementAndGet();
                }
            } catch (Exception ex) {
                if (!finalAttempt && isRateLimit(ex)) {
                    onRateLimit(run);
                    return true;
                }
                recordFailure(run, target.symbol(), null, ex);
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // 1M candles -> market_candles
    // ------------------------------------------------------------------------------------------------

    /** @return true when the call was rate limited and should be retried later in this run */
    private boolean pullMinuteCandles(Run run, Target target, LocalDate date, Set<String> alreadyHaveCandles,
                                      ExecutorService derivePool, List<Future<?>> deriveTasks, boolean finalAttempt) {
        String symbol = target.symbol();
        if (alreadyHaveCandles.contains(symbol)) {
            run.skippedAlreadyPresent.incrementAndGet();
            return false;
        }
        Optional<com.trading.scanner.model.EodDataEntry> checkpoint =
                eodDataEntryRepository.findBySymbolAndExchangeAndTradingDate(symbol, EXCHANGE, date);
        if (checkpoint.isPresent() && SOURCE.equals(checkpoint.get().getSource())) {
            boolean wasNoData = checkpoint.get().getActualMinuteCount() != null
                    && checkpoint.get().getActualMinuteCount() == 0;
            if (!(wasNoData && run.request.retryNoData())) {
                run.skippedAlreadyPresent.incrementAndGet();
                return false;
            }
        }

        try {
            AngelOneMarketDataProvider.HistoricalOneMinuteFetch fetch = callWithSessionRefresh(run,
                    () -> provider.fetchHistoricalOneMinuteForBackfill(symbol, date));
            run.apiCalls.incrementAndGet();
            onApiSuccess(run);
            writeRaw(run, Paths.get(date.toString(), symbol + ".json"), fetch.rawResponseJson());

            List<MarketCandle> candles = fetch.candles();
            if (run.request.dryRun()) {
                run.dryRunLog.add(describe(symbol, date, candles));
                return false;
            }

            if (candles.isEmpty()) {
                run.noData.incrementAndGet();
                run.noDataLog.add(Map.of("symbol", symbol, "date", date.toString()));
                eodDataEntryService.complete(symbol, EXCHANGE, date, DataStatus.PARTIAL, 0, 0, 0, 0, 0, 0, SOURCE,
                        "Angel One returned no candles for this session");
                return false;
            }

            LocalDateTime now = timeProvider.nowDateTime();
            List<MarketCandle> toInsert = new ArrayList<>();
            for (MarketCandle c : candles) {
                c.setSource(SOURCE);
                c.setQualityStatus(CandleQualityStatus.RECONCILED);
                c.setCreatedAt(now);
                c.setUpdatedAt(now);
                toInsert.add(c);
            }
            marketCandleRepository.saveAll(toInsert);
            run.candlesInserted.addAndGet(toInsert.size());

            eodDataEntryService.complete(symbol, EXCHANGE, date, DataStatus.RECONCILED,
                    toInsert.size(), toInsert.size(), 0, 0, 0, toInsert.size(), SOURCE,
                    "Stored as delivered by Angel One historical API; quiet minutes are not inferred");

            deriveTasks.add(derivePool.submit(() -> deriveSymbolDay(run, target, date, toInsert.size())));
        } catch (Exception ex) {
            if (!finalAttempt && isRateLimit(ex)) {
                onRateLimit(run);
                return true;
            }
            recordFailure(run, symbol, date, ex);
        }
        return false;
    }

    private Map<String, Object> describe(String symbol, LocalDate date, List<MarketCandle> candles) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("date", date.toString());
        m.put("candles", candles.size());
        if (!candles.isEmpty()) {
            MarketCandle first = candles.get(0);
            MarketCandle last = candles.get(candles.size() - 1);
            m.put("firstCandle", first.getCandleTime().toString());
            m.put("firstOpen", first.getOpenPrice());
            m.put("firstVolume", first.getVolume());
            m.put("lastCandle", last.getCandleTime().toString());
            m.put("lastClose", last.getClosePrice());
            m.put("lastVolume", last.getVolume());
            m.put("volumeSum", candles.stream().mapToLong(c -> c.getVolume() != null ? c.getVolume() : 0L).sum());
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------
    // Derived data (production code paths)
    // ------------------------------------------------------------------------------------------------

    /** Indicators, 5M and 15M candles via the same code live and EOD use; then the daily context for stocks. */
    private void deriveSymbolDay(Run run, Target target, LocalDate date, int candleCount) {
        try {
            liveMarketCandleService.recomputeDerivedData(target.symbol(), EXCHANGE, date);
            if (!target.isIndex()) {
                buildContext(target.symbol(), date);
            }
            dailyDataStatusService.markStatus(target.symbol(), EXCHANGE, date, DataStatus.RECONCILED,
                    candleCount, candleCount, 0,
                    "Historical API backfill: stored as delivered; zero-trade minutes are omitted by the API");
        } catch (Exception ex) {
            log.warn("[HIST_BACKFILL] derive failed symbol={} date={}: {}", target.symbol(), date, ex.getMessage());
            run.failureLog.add(Map.of("symbol", target.symbol(), "date", date.toString(),
                    "stage", "DERIVE", "error", String.valueOf(ex.getMessage())));
            run.failures.incrementAndGet();
        }
    }

    /** Daily context is built from the last opening-range candle, exactly as the live finalizer does. */
    private void buildContext(String symbol, LocalDate date) {
        List<MarketCandle> opening = marketCandleRepository
                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                        symbol, EXCHANGE, CandleTimeframe.ONE_MINUTE,
                        date.atTime(9, 15), date.atTime(OPENING_RANGE_LAST_MINUTE));
        if (!opening.isEmpty()) {
            dailyStockContextService.processFinalizedOneMinuteCandle(opening.get(opening.size() - 1));
        }
    }

    /** Runs in session order after every symbol of the session has been derived. */
    private void finalizeSession(Run run, LocalDate date, int sessionIndex, LocalDate latest) {
        try {
            run.phase = "FINALIZING";
            ensureContexts(date);

            LocalDate effective = tradingCalendar.nextTradingDay(date);
            if (sessionIndex + 1 < MIN_SESSIONS_FOR_BASELINE) {
                return; // fewer than 20 sessions of candles exist yet: a baseline would be partial, so none is written
            }
            if (effective.isAfter(latest)) {
                return; // the live 08:00 job owns baselines for sessions that have not happened yet
            }
            if (volumeDailyBaselineRepository.countByTradingDate(effective) >= FULL_BASELINE_SYMBOLS) {
                return; // a full baseline already exists; never rewrite what live used
            }
            VolumeBaselineService.PreCalculationResult result = volumeBaselineService.preCalculateForCompletedTradingDay(date);
            log.info("[HIST_BACKFILL] baselines for {} from {}: processed={} skipped={}",
                    effective, date, result.processedSymbols(), result.skippedSymbols());
        } catch (Exception ex) {
            log.error("[HIST_BACKFILL] finalize failed for {}: {}", date, ex.getMessage(), ex);
            run.failureLog.add(Map.of("date", date.toString(), "stage", "FINALIZE", "error", String.valueOf(ex.getMessage())));
            run.failures.incrementAndGet();
        }
    }

    /** Sessions with candles but no (or partial) contexts, such as the four live gaps, get theirs built here. */
    private void ensureContexts(LocalDate date) {
        if (dailyStockContextRepository.countByTradingDate(date) >= FULL_BASELINE_SYMBOLS) {
            return;
        }
        Set<String> withContext = new LinkedHashSet<>();
        dailyStockContextRepository.findByTradingDateOrderBySymbolAsc(date).forEach(c -> withContext.add(c.getSymbol()));
        Set<String> withCandles = new LinkedHashSet<>(marketCandleRepository.findDistinctSymbolsByTimeframeAndCandleTimeRange(
                CandleTimeframe.ONE_MINUTE, date.atStartOfDay(), date.plusDays(1).atStartOfDay()));
        for (StockUniverse stock : stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()) {
            String sym = stock.getSymbol();
            if (withCandles.contains(sym) && !withContext.contains(sym)) {
                buildContext(sym, date);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    private <T> T callWithSessionRefresh(Run run, java.util.concurrent.Callable<T> call) throws Exception {
        try {
            return call.call();
        } catch (ProviderException ex) {
            if (isSessionProblem(ex)) {
                log.warn("[HIST_BACKFILL] session problem ({}); refreshing Angel One session and retrying once",
                        ex.getMessage());
                angelOneSessionService.refreshSessionTokens();
                return call.call();
            }
            throw ex;
        }
    }

    static boolean isSessionProblem(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("invalid token") || m.contains("ag8001") || m.contains("ag8002")
                    || m.contains("session expired") || m.contains("jwt")) {
                return true;
            }
        }
        return false;
    }

    private void recordFailure(Run run, String symbol, LocalDate date, Throwable ex) {
        String message = String.valueOf(ex.getMessage());
        boolean untradableInstrument = message.contains("Instrument not found") || message.contains("broker_token is missing");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("symbol", symbol);
        entry.put("date", date != null ? date.toString() : "DAILY");
        entry.put("error", message);
        run.failureLog.add(entry);
        run.failures.incrementAndGet();
        log.warn("[HIST_BACKFILL] failed symbol={} date={}: {}", symbol, date, message);
        if (untradableInstrument || isRateLimit(ex)) {
            return; // a missing instrument is a data issue; rate limiting is handled by cool-downs, not aborts
        }
        if (run.consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
            run.aborted = true;
            run.message = MAX_CONSECUTIVE_FAILURES + " consecutive API failures (last: " + message
                    + "). Stopping to avoid hammering Angel One; fix the cause and start again to resume.";
            log.error("[HIST_BACKFILL] run={} aborted: {}", run.runId, run.message);
        }
    }

    static boolean isRateLimit(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("rate limit") || m.contains("ab1021") || m.contains("too many requests")
                    || m.contains("exceeding access rate")) {
                return true;
            }
        }
        return false;
    }

    private void onApiSuccess(Run run) {
        run.consecutiveFailures.set(0);
        run.rateLimitStreak.set(0);
        run.cooldownsWithoutProgress.set(0);
    }

    /** A burst of rate-limit rejections triggers a cool-down; many cool-downs with no success abort the run. */
    private void onRateLimit(Run run) {
        run.rateLimitDeferrals.incrementAndGet();
        if (run.rateLimitStreak.incrementAndGet() < RATE_LIMIT_BURST) {
            return;
        }
        run.rateLimitStreak.set(0);
        int cooldowns = run.cooldownsWithoutProgress.incrementAndGet();
        if (cooldowns > MAX_COOLDOWNS_WITHOUT_PROGRESS) {
            run.aborted = true;
            run.message = "Angel One kept rate limiting through " + MAX_COOLDOWNS_WITHOUT_PROGRESS
                    + " cool-downs without a single success. Stopping; start again later to resume.";
            log.error("[HIST_BACKFILL] run={} aborted: {}", run.runId, run.message);
            return;
        }
        long waitMillis = cooldownMillis * cooldowns;
        run.message = "Rate limited by Angel One; cooling down " + (waitMillis / 1000) + "s (cool-down "
                + cooldowns + "/" + MAX_COOLDOWNS_WITHOUT_PROGRESS + ")";
        log.warn("[HIST_BACKFILL] run={} {}", run.runId, run.message);
        sleepUnlessStopped(run, waitMillis);
        run.message = "";
    }

    private static void sleepUnlessStopped(Run run, long waitMillis) {
        long until = System.currentTimeMillis() + waitMillis;
        while (System.currentTimeMillis() < until && !run.stopRequested) {
            try {
                Thread.sleep(Math.min(1_000L, Math.max(1L, until - System.currentTimeMillis())));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static void awaitAll(List<Future<?>> tasks) {
        for (Future<?> f : tasks) {
            try {
                f.get();
            } catch (Exception ignored) {
                // each derive task records its own failure
            }
        }
    }

    private Path rawRoot() {
        if (rawDirectory != null && !rawDirectory.isBlank()) {
            return Paths.get(rawDirectory).toAbsolutePath().normalize();
        }
        return Paths.get(backupDirectory).toAbsolutePath().normalize().resolveSibling("historical-raw");
    }

    private void writeRaw(Run run, Path relative, Object payload) {
        try {
            Path target = run.rawRoot.resolve(relative);
            Files.createDirectories(target.getParent());
            String body = payload instanceof String s ? s : json.writeValueAsString(payload);
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("[HIST_BACKFILL] could not archive raw response {}: {}", relative, ex.getMessage());
        }
    }

    private void writeSummary(Run run, LocalDateTime finishedAt) {
        try {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", run.snapshot());
            summary.put("finishedAt", finishedAt);
            summary.put("failures", run.failureLog.list());
            summary.put("noData", run.noDataLog.list());
            if (run.request.dryRun()) {
                summary.put("dryRunSamples", run.dryRunLog.list());
            }
            Files.createDirectories(run.rawRoot);
            Files.writeString(run.rawRoot.resolve("summary.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(summary), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("[HIST_BACKFILL] could not write summary: {}", ex.getMessage());
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
