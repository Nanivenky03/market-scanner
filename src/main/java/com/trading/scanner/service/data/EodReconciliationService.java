package com.trading.scanner.service.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.model.WorkflowStatus;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneMarketDtos;
import com.trading.scanner.service.runtime.RuntimeAlertService;
import com.trading.scanner.service.workflow.WorkflowStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class EodReconciliationService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);

        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private static final String RECONCILIATION_SOURCE = "EOD_RECONCILIATION";

        private static final String REQUIRED_INDEX_SYMBOL = "NIFTY";

        private static final String REQUIRED_INDEX_EXCHANGE = "NSE";

        private final AngelOneMarketDataProvider marketDataProvider;

        private final MarketCandleRepository marketCandleRepository;

        private final LiveMinuteResolutionRepository resolutionRepository;

        private final DailyDataStatusService dailyDataStatusService;

        private final StockUniverseRepository stockUniverseRepository;

        private final TradingCalendar tradingCalendar;
        private final TimeProvider timeProvider;
        private final EodDataEntryService eodDataEntryService;

        private final LiveMarketCandleService liveMarketCandleService;
        private final WorkflowStatusService workflowStatusService;
        private final RuntimeAlertService runtimeAlertService;
        private final AngelOneSessionService angelOneSessionService;
        private final AngelOneWebSocketService angelOneWebSocketService;

        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        private final java.util.concurrent.locks.ReentrantLock reconciliationLock = new java.util.concurrent.locks.ReentrantLock();

        @Value("${runtime.reconciliation.price-tolerance:0.0001}")
        private double priceTolerance = 0.0001;

        @Value("${runtime.eod.raw-archive-dir:./data/raw-eod}")
        private String rawArchiveDir = "./data/raw-eod";

        @Autowired
        public EodReconciliationService(
                        AngelOneMarketDataProvider marketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        LiveMinuteResolutionRepository resolutionRepository,
                        DailyDataStatusService dailyDataStatusService,
                        StockUniverseRepository stockUniverseRepository,
                        TradingCalendar tradingCalendar,
                        TimeProvider timeProvider,
                        EodDataEntryService eodDataEntryService,
                        LiveMarketCandleService liveMarketCandleService,
                        WorkflowStatusService workflowStatusService,
                        RuntimeAlertService runtimeAlertService,
                        AngelOneSessionService angelOneSessionService,
                        AngelOneWebSocketService angelOneWebSocketService) {

                this.marketDataProvider = marketDataProvider;
                this.marketCandleRepository = marketCandleRepository;
                this.resolutionRepository = resolutionRepository;
                this.dailyDataStatusService = dailyDataStatusService;
                this.stockUniverseRepository = stockUniverseRepository;
                this.tradingCalendar = tradingCalendar;
                this.timeProvider = timeProvider;
                this.eodDataEntryService = eodDataEntryService;
                this.liveMarketCandleService = liveMarketCandleService;
                this.workflowStatusService = workflowStatusService;
                this.runtimeAlertService = runtimeAlertService;
                this.angelOneSessionService = angelOneSessionService;
                this.angelOneWebSocketService = angelOneWebSocketService;
        }

        /*
         * Backward-compatible constructor for existing tests.
         */
        public EodReconciliationService(
                        AngelOneMarketDataProvider marketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        LiveMinuteResolutionRepository resolutionRepository,
                        DailyDataStatusService dailyDataStatusService,
                        StockUniverseRepository stockUniverseRepository,
                        TradingCalendar tradingCalendar,
                        TimeProvider timeProvider,
                        EodDataEntryService eodDataEntryService,
                        LiveMarketCandleService liveMarketCandleService) {

                this(
                                marketDataProvider,
                                marketCandleRepository,
                                resolutionRepository,
                                dailyDataStatusService,
                                stockUniverseRepository,
                                tradingCalendar,
                                timeProvider,
                                eodDataEntryService,
                                liveMarketCandleService,
                                null,
                                null,
                                null,
                                null);
        }

        /*
         * Backward-compatible constructor for existing tests.
         */
        public EodReconciliationService(
                        AngelOneMarketDataProvider marketDataProvider,
                        MarketCandleRepository marketCandleRepository,
                        LiveMinuteResolutionRepository resolutionRepository,
                        DailyDataStatusService dailyDataStatusService,
                        StockUniverseRepository stockUniverseRepository,
                        TradingCalendar tradingCalendar,
                        TimeProvider timeProvider,
                        EodDataEntryService eodDataEntryService) {

                this(
                                marketDataProvider,
                                marketCandleRepository,
                                resolutionRepository,
                                dailyDataStatusService,
                                stockUniverseRepository,
                                tradingCalendar,
                                timeProvider,
                                eodDataEntryService,
                                null);
        }

        @Transactional
        public ReconciliationBatchResult reconcilePreviousTradingDay() {
                LocalDate tradingDate = tradingCalendar.previousTradingDay(
                                timeProvider.today());
                return reconcileTradingDay(tradingDate);
        }

        public ReconciliationBatchResult reconcileTradingDay(LocalDate tradingDate) {
                if (tradingDate == null) {
                        tradingDate = tradingCalendar.previousTradingDay(timeProvider.today());
                }

                if (!tradingCalendar.isTradingDay(tradingDate)) {
                        return new ReconciliationBatchResult(
                                        tradingDate,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "Skipped non-trading day");
                }

                if (!reconciliationLock.tryLock()) {
                        log.warn("EOD reconciliation is already running for date {}. Skipping concurrent execution.",
                                        tradingDate);
                        return new ReconciliationBatchResult(
                                        tradingDate,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "EOD reconciliation is already running for date: " + tradingDate);
                }

                try {
                        // Verify prerequisite: WebSocket must not be connected during EOD
                        // reconciliation
                        if (angelOneWebSocketService != null && angelOneWebSocketService.status().connected()) {
                                throw new IllegalStateException(
                                                "EOD reconciliation blocked: WebSocket is still connected");
                        }

                        UUID marketHoursWorkflowId = null;
                        if (workflowStatusService != null) {
                                String dateStr = tradingDate.toString();
                                if (tradingDate.equals(timeProvider.today())) {
                                        if (!workflowStatusService.isSuccessfulActiveDailyWorkflow(
                                                        WorkflowStatusService.MARKET_HOURS,
                                                        WorkflowStatusService.MARKET_HOURS_GROUP,
                                                        dateStr)) {
                                                throw new IllegalStateException(
                                                                "EOD reconciliation blocked: market-hours workflow stage is not SUCCESS for date "
                                                                                + dateStr);
                                        }
                                }
                                try {
                                        WorkflowStatus marketHours = workflowStatusService.findOrCreateDailyWorkflow(
                                                        WorkflowStatusService.MARKET_HOURS,
                                                        WorkflowStatusService.MARKET_HOURS_GROUP,
                                                        dateStr,
                                                        null);
                                        if (marketHours != null) {
                                                marketHoursWorkflowId = marketHours.getWorkflowId();
                                        }
                                } catch (Exception ignored) {
                                }
                        }

                        WorkflowStatus eodWorkflow = null;
                        if (workflowStatusService != null) {
                                eodWorkflow = workflowStatusService.prepareForDaily(
                                                WorkflowStatusService.EOD_RECONCILIATION,
                                                WorkflowStatusService.EOD_GROUP,
                                                tradingDate.toString(),
                                                marketHoursWorkflowId);
                                if (eodWorkflow.getStatus() == WorkflowStatus.Status.SUCCESS) {
                                        log.info("EOD reconciliation already succeeded for date: {}", tradingDate);
                                        return new ReconciliationBatchResult(
                                                        tradingDate,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        "EOD reconciliation already succeeded for date: "
                                                                        + tradingDate);
                                }
                                eodWorkflow = workflowStatusService.markRunning(eodWorkflow.getWorkflowId());
                        }

                        Map<String, ReconciliationTarget> targets = new LinkedHashMap<>();

                        // Include benchmark index NIFTY in EOD targets
                        targets.put(
                                        "NSE|NIFTY",
                                        new ReconciliationTarget(
                                                        "NIFTY",
                                                        "NSE"));

                        for (StockUniverse stock : stockUniverseRepository
                                        .findByIsActiveTrueOrderBySymbolAsc()) {

                                if (stock == null
                                                || stock.getSymbol() == null
                                                || stock.getExchange() == null) {
                                        continue;
                                }

                                String symbol = stock.getSymbol()
                                                .trim()
                                                .toUpperCase();

                                String exchange = stock.getExchange()
                                                .name()
                                                .trim()
                                                .toUpperCase();

                                targets.putIfAbsent(
                                                exchange + "|" + symbol,
                                                new ReconciliationTarget(
                                                                symbol,
                                                                exchange));
                        }

                        int processed = 0;
                        int reconciled = 0;
                        int repaired = 0;
                        int partial = 0;

                        LocalDate today = timeProvider != null ? timeProvider.today() : null;
                        LocalDateTime now = timeProvider != null ? timeProvider.nowDateTime() : null;
                        boolean isFinalAttempt = (today != null && tradingDate.isBefore(today))
                                        || (now != null && now.getHour() >= 23);

                        // Batch fetch market quotes for all target symbols in one go (chunked by 50)
                        List<String> targetSymbols = targets.values().stream()
                                        .map(ReconciliationTarget::symbol)
                                        .toList();
                        Map<String, AngelOneMarketDtos.AngelOneMarketQuoteItem> marketQuotes = Map.of();
                        if (marketDataProvider != null) {
                                try {
                                        marketQuotes = marketDataProvider.fetchMarketQuotes(targetSymbols, "NSE");
                                } catch (Exception ex) {
                                        log.warn("Failed to fetch batch market quotes for date {}: {}", tradingDate,
                                                        ex.getMessage());
                                }
                        }

                        for (ReconciliationTarget target : targets.values()) {

                                processed++;

                                if (eodDataEntryService != null && eodDataEntryService.isSuccessful(
                                                target.symbol(),
                                                target.exchange(),
                                                tradingDate)) {
                                        reconciled++;
                                        continue;
                                }

                                try {
                                        AngelOneMarketDtos.AngelOneMarketQuoteItem quote = marketQuotes
                                                        .get(target.symbol());
                                        ReconciliationResult result = reconcile(
                                                        target.symbol(),
                                                        target.exchange(),
                                                        tradingDate,
                                                        isFinalAttempt,
                                                        quote);

                                        if (result.status() == DataStatus.RECONCILED) {
                                                reconciled++;
                                        } else if (result.status() == DataStatus.REPAIRED) {
                                                repaired++;
                                        } else {
                                                partial++;
                                        }

                                } catch (Exception ex) {
                                        partial++;

                                        log.warn(
                                                        "EOD reconciliation failed symbol={} exchange={} date={}: {}",
                                                        target.symbol(),
                                                        target.exchange(),
                                                        tradingDate,
                                                        ex.getMessage(),
                                                        ex);
                                }
                        }

                        String message = partial == 0
                                        ? "EOD reconciliation completed successfully"
                                        : "EOD reconciliation completed with partial symbols="
                                                        + partial;

                        if (partial > 0) {
                                if (isFinalAttempt) {
                                        if (runtimeAlertService != null) {
                                                runtimeAlertService.reportEodReconciliationFailure(
                                                                tradingDate,
                                                                partial,
                                                                "EOD reconciliation found incomplete or missing candles for "
                                                                                + partial
                                                                                + " symbol(s) on " + tradingDate,
                                                                Map.of(
                                                                                "tradingDate", tradingDate.toString(),
                                                                                "symbolsProcessed", processed,
                                                                                "reconciledSymbols", reconciled,
                                                                                "repairedSymbols", repaired,
                                                                                "partialSymbols", partial));
                                        }
                                        if (workflowStatusService != null && eodWorkflow != null) {
                                                workflowStatusService.markFailed(
                                                                eodWorkflow.getWorkflowId(),
                                                                message,
                                                                "Incomplete/failed symbols count: " + partial);
                                        }
                                } else {
                                        log.info("EOD reconciliation found partial data for {} symbols on intermediate retry (hour={}). Alert deferred to subsequent hourly retry.",
                                                        partial, now != null ? now.getHour() : "unknown");
                                }
                        } else {
                                if (runtimeAlertService != null) {
                                        runtimeAlertService.resolveEodReconciliation(tradingDate);
                                }
                                if (workflowStatusService != null && eodWorkflow != null) {
                                        workflowStatusService.markSuccess(eodWorkflow.getWorkflowId(), message);
                                        executeDailyCycleComplete(tradingDate, eodWorkflow.getWorkflowId());
                                }
                        }

                        return new ReconciliationBatchResult(
                                        tradingDate,
                                        processed,
                                        reconciled,
                                        repaired,
                                        partial,
                                        message);
                } finally {
                        reconciliationLock.unlock();
                }
        }

        private void executeDailyCycleComplete(LocalDate tradingDate, UUID eodWorkflowId) {
                if (workflowStatusService == null) {
                        return;
                }
                try {
                        WorkflowStatus dailyCycleWorkflow = workflowStatusService.prepareForDaily(
                                        WorkflowStatusService.DAILY_CYCLE_COMPLETE,
                                        WorkflowStatusService.SESSION_GROUP,
                                        tradingDate.toString(),
                                        eodWorkflowId);

                        if (dailyCycleWorkflow.getStatus() != WorkflowStatus.Status.SUCCESS) {
                                dailyCycleWorkflow = workflowStatusService
                                                .markRunning(dailyCycleWorkflow.getWorkflowId());
                        }

                        if (angelOneSessionService != null) {
                                angelOneSessionService.clearCachedSession();
                        }

                        workflowStatusService.markSuccess(
                                        dailyCycleWorkflow.getWorkflowId(),
                                        "Daily cycle completed and broker session invalidated cleanly");
                        log.info("Daily cycle complete stage finalized successfully for date: {}", tradingDate);
                } catch (Exception ex) {
                        log.error("Failed to complete daily cycle workflow for date {}: {}", tradingDate,
                                        ex.getMessage(), ex);
                }
        }

        @Transactional
        public ReconciliationResult reconcile(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {
                LocalDate today = timeProvider != null ? timeProvider.today() : null;
                LocalDateTime now = timeProvider != null ? timeProvider.nowDateTime() : null;
                boolean isFinalAttempt = (today != null && tradingDate.isBefore(today))
                                || (now != null && now.getHour() >= 23);
                return reconcile(symbol, exchange, tradingDate, isFinalAttempt, null);
        }

        @Transactional
        public ReconciliationResult reconcile(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        boolean isFinalAttempt) {
                return reconcile(symbol, exchange, tradingDate, isFinalAttempt, null);
        }

        @Transactional
        public ReconciliationResult reconcile(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        boolean isFinalAttempt,
                        AngelOneMarketDtos.AngelOneMarketQuoteItem quote) {

                LocalDate today = timeProvider != null ? timeProvider.today() : null;
                LocalDateTime now = timeProvider != null ? timeProvider.nowDateTime() : null;
                boolean effectiveFinalAttempt = isFinalAttempt
                                || (today != null && tradingDate.isBefore(today))
                                || (now != null && now.getHour() >= 23);

                int expected = tradingCalendar
                                .expectedOneMinuteCandleCount(
                                                tradingDate);

                if (expected == 0) {
                        return new ReconciliationResult(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        DataStatus.PARTIAL,
                                        0,
                                        0,
                                        0,
                                        0,
                                        "Skipped non-trading day");
                }

                eodDataEntryService.begin(
                                symbol,
                                exchange,
                                tradingDate,
                                RECONCILIATION_SOURCE);

                try {
                        LocalDateTime from = tradingDate.atTime(MARKET_OPEN);
                        LocalDateTime to = tradingDate.atTime(MARKET_CLOSE);

                        List<MarketCandle> localCandles = marketCandleRepository
                                        .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                        symbol,
                                                        exchange,
                                                        CandleTimeframe.ONE_MINUTE,
                                                        from,
                                                        to);

                        // 1. Fetch official 1-minute historical candles from SmartAPI
                        List<MarketCandle> providerCandles = marketDataProvider
                                        .fetchHistoricalOneMinuteCandles(
                                                        symbol,
                                                        tradingDate,
                                                        tradingDate);

                        saveRawResponseToArchive(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        providerCandles);

                        if (providerCandles == null
                                        || providerCandles.isEmpty()) {
                                throw new ProviderException(
                                                "Provider returned no candles; "
                                                                + "no-trade cannot be inferred"
                                                                + "; symbol="
                                                                + symbol
                                                                + ", tradingDate="
                                                                + tradingDate);
                        }

                        // 2. Validate historical provider data integrity (Boundary, Quote OHLC, Volume
                        // Ceiling)
                        boolean providerValid = validateProviderDataIntegrity(
                                        symbol,
                                        tradingDate,
                                        expected,
                                        providerCandles,
                                        quote);

                        if (!providerValid && !effectiveFinalAttempt) {
                                int unres = Math.max(1, expected - providerCandles.size());
                                String reason = "EOD provider data integrity check failed (data may be lagging/truncated). Retrying hourly.";
                                dailyDataStatusService.markStatus(
                                                symbol,
                                                exchange,
                                                tradingDate,
                                                DataStatus.PARTIAL,
                                                expected,
                                                providerCandles.size(),
                                                unres,
                                                reason);
                                return new ReconciliationResult(
                                                symbol,
                                                exchange,
                                                tradingDate,
                                                DataStatus.PARTIAL,
                                                0,
                                                0,
                                                0,
                                                unres,
                                                reason);
                        }

                        List<LiveMinuteResolution> resolutions = resolutionRepository
                                        .findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
                                                        symbol,
                                                        exchange,
                                                        from,
                                                        to);

                        Map<LocalDateTime, MarketCandle> providerByTime = indexCandles(providerCandles);

                        Map<LocalDateTime, MarketCandle> localByTime = indexCandles(localCandles);

                        Map<LocalDateTime, LiveMinuteResolution> resolutionByTime = new HashMap<>();

                        for (LiveMinuteResolution resolution : resolutions) {
                                resolutionByTime.put(
                                                resolution.getMinuteTime(),
                                                resolution);
                        }

                        int matched = 0;
                        int repaired = 0;
                        int noTrade = 0;
                        int unresolved = 0;

                        for (int i = 0; i < expected; i++) {

                                LocalDateTime minute = from.plusMinutes(i);

                                MarketCandle providerCandle = providerByTime.get(minute);

                                MarketCandle localCandle = localByTime.get(minute);

                                if (providerCandle != null) {
                                        if (localCandle == null) {
                                                MarketCandle inserted = createRepairedCandle(
                                                                providerCandle,
                                                                symbol,
                                                                exchange,
                                                                minute);

                                                marketCandleRepository.save(
                                                                inserted);

                                                markRepairedResolution(
                                                                symbol,
                                                                exchange,
                                                                minute);

                                                repaired++;
                                                continue;
                                        }

                                        if (sameValues(
                                                        localCandle,
                                                        providerCandle)) {

                                                markMatchedCandle(localCandle);
                                                matched++;

                                        } else {
                                                copyValues(
                                                                providerCandle,
                                                                localCandle);

                                                localCandle.setSource(
                                                                RECONCILIATION_SOURCE);

                                                localCandle.setQualityStatus(
                                                                CandleQualityStatus.REPAIRED);

                                                localCandle.setProcessingStatus(
                                                                CandleProcessingStatus.RELEASED);

                                                localCandle.setIsFinalized(true);

                                                localCandle.setUpdatedAt(
                                                                timeProvider.nowDateTime());

                                                marketCandleRepository.save(
                                                                localCandle);

                                                markRepairedResolution(
                                                                symbol,
                                                                exchange,
                                                                minute);

                                                repaired++;
                                        }

                                        continue;
                                }

                                LiveMinuteResolution resolution = resolutionByTime.get(minute);

                                if (resolution != null
                                                && resolution.getStatus() == MinuteResolutionStatus.NO_TRADE_CONFIRMED) {
                                        if (localCandle != null) {
                                                marketCandleRepository.delete(localCandle);
                                        }
                                        noTrade++;
                                } else if (providerValid || effectiveFinalAttempt) {
                                        if (localCandle != null) {
                                                marketCandleRepository.delete(localCandle);
                                        }
                                        markNoTradeResolution(
                                                        symbol,
                                                        exchange,
                                                        minute);
                                        noTrade++;
                                } else {
                                        unresolved++;
                                }
                        }

                        /*
                         * Recompute from the beginning of the day so RSI,
                         * VWAP, candle structure, and derived candles are
                         * consistent after reconciliation repairs.
                         */
                        if (liveMarketCandleService != null) {
                                liveMarketCandleService
                                                .recomputeDerivedData(
                                                                symbol,
                                                                exchange,
                                                                tradingDate);
                        }

                        DailyDataStatusService.CompletenessResult completeness = dailyDataStatusService
                                        .checkCompleteness(
                                                        symbol,
                                                        exchange,
                                                        tradingDate);

                        DataStatus finalStatus;
                        String reason;

                        if (unresolved == 0
                                        && completeness.complete()) {

                                finalStatus = repaired > 0
                                                ? DataStatus.REPAIRED
                                                : DataStatus.RECONCILED;

                                reason = repaired > 0
                                                ? "EOD reconciliation repaired candle differences"
                                                : "EOD reconciliation verified all candles via provider integrity check";

                        } else {
                                finalStatus = DataStatus.PARTIAL;

                                reason = "EOD reconciliation unresolved minutes="
                                                + unresolved
                                                + ", noTrade="
                                                + noTrade;
                        }

                        eodDataEntryService.complete(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        finalStatus,
                                        completeness.expectedCandleCount(),
                                        completeness.actualCandleCount(),
                                        noTrade,
                                        unresolved,
                                        repaired,
                                        matched,
                                        RECONCILIATION_SOURCE,
                                        reason);

                        dailyDataStatusService.markStatus(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        finalStatus,
                                        completeness.expectedCandleCount(),
                                        completeness.actualCandleCount(),
                                        completeness.missingCandleCount(),
                                        reason);

                        return new ReconciliationResult(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        finalStatus,
                                        matched,
                                        repaired,
                                        noTrade,
                                        unresolved,
                                        reason);

                } catch (Exception ex) {
                        eodDataEntryService.markFailed(
                                        symbol,
                                        exchange,
                                        tradingDate,
                                        RECONCILIATION_SOURCE,
                                        safeMessage(ex));

                        throw ex;
                }
        }

        private Map<LocalDateTime, MarketCandle> indexCandles(
                        List<MarketCandle> candles) {

                Map<LocalDateTime, MarketCandle> result = new LinkedHashMap<>();

                if (candles == null) {
                        return result;
                }

                for (MarketCandle candle : candles) {

                        if (candle != null
                                        && candle.getCandleTime() != null) {
                                result.put(
                                                candle.getCandleTime(),
                                                candle);
                        }
                }

                return result;
        }

        private boolean sameValues(
                        MarketCandle local,
                        MarketCandle provider) {

                return close(
                                local.getOpenPrice(),
                                provider.getOpenPrice())
                                && close(
                                                local.getHighPrice(),
                                                provider.getHighPrice())
                                && close(
                                                local.getLowPrice(),
                                                provider.getLowPrice())
                                && close(
                                                local.getClosePrice(),
                                                provider.getClosePrice())
                                && Objects.equals(
                                                local.getVolume(),
                                                provider.getVolume());
        }

        private boolean validateProviderDataIntegrity(
                        String symbol,
                        LocalDate tradingDate,
                        int expected,
                        List<MarketCandle> providerCandles,
                        AngelOneMarketDtos.AngelOneMarketQuoteItem quote) {

                if (providerCandles == null || providerCandles.isEmpty()) {
                        return false;
                }

                MarketCandle firstCandle = providerCandles.get(0);
                if (firstCandle.getCandleTime() == null
                                || !firstCandle.getCandleTime().toLocalTime().equals(MARKET_OPEN)) {
                        log.warn("EOD provider integrity check failed for symbol={} on {}: first candle is not at 09:15 ({})",
                                        symbol, tradingDate, firstCandle.getCandleTime());
                        return false;
                }

                MarketCandle lastCandle = providerCandles.get(providerCandles.size() - 1);
                LocalTime lastTime = lastCandle.getCandleTime() != null ? lastCandle.getCandleTime().toLocalTime()
                                : null;

                if (quote != null) {
                        if (quote.open() != null && quote.open() > 0.0) {
                                if (!close(firstCandle.getOpenPrice(), quote.open())) {
                                        log.warn("EOD provider integrity check failed for symbol={} on {}: Open price mismatch (provider={}, quote={})",
                                                        symbol, tradingDate, firstCandle.getOpenPrice(), quote.open());
                                        return false;
                                }
                        }

                        if (quote.high() != null && quote.high() > 0.0) {
                                double providerHigh = providerCandles.stream()
                                                .mapToDouble(c -> c.getHighPrice() != null ? c.getHighPrice() : 0.0)
                                                .max().orElse(0.0);
                                if (!close(providerHigh, quote.high())) {
                                        log.warn("EOD provider integrity check failed for symbol={} on {}: High price mismatch (provider={}, quote={})",
                                                        symbol, tradingDate, providerHigh, quote.high());
                                        return false;
                                }
                        }

                        if (quote.low() != null && quote.low() > 0.0) {
                                double providerLow = providerCandles.stream()
                                                .mapToDouble(c -> c.getLowPrice() != null ? c.getLowPrice() : 0.0)
                                                .min().orElse(0.0);
                                if (!close(providerLow, quote.low())) {
                                        log.warn("EOD provider integrity check failed for symbol={} on {}: Low price mismatch (provider={}, quote={})",
                                                        symbol, tradingDate, providerLow, quote.low());
                                        return false;
                                }
                        }

                        if (quote.ltp() != null && quote.ltp() > 0.0) {
                                if (!close(lastCandle.getClosePrice(), quote.ltp())) {
                                        log.warn("EOD provider integrity check failed for symbol={} on {}: Close/LTP mismatch (provider={}, quote={})",
                                                        symbol, tradingDate, lastCandle.getClosePrice(), quote.ltp());
                                        return false;
                                }
                        }

                        if (quote.tradeVolume() != null && quote.tradeVolume() > 0L) {
                                long providerTotalVolume = providerCandles.stream()
                                                .mapToLong(c -> c.getVolume() != null ? c.getVolume() : 0L)
                                                .sum();
                                if (providerTotalVolume > quote.tradeVolume()) {
                                        log.warn("EOD provider integrity check failed for symbol={} on {}: Volume exceeded ceiling (provider={}, quote={})",
                                                        symbol, tradingDate, providerTotalVolume, quote.tradeVolume());
                                        return false;
                                }
                        }
                } else {
                        LocalTime expectedSessionClose = MARKET_OPEN.plusMinutes(Math.max(0, expected - 1));
                        LocalTime earliestAcceptableLast = expectedSessionClose.minusMinutes(Math.min(5, expected / 2));
                        if (lastTime == null || lastTime.isBefore(earliestAcceptableLast)) {
                                log.warn("EOD provider integrity check failed for symbol={} on {}: last candle too early ({}) without quote confirmation",
                                                symbol, tradingDate, lastTime);
                                return false;
                        }
                }

                return true;
        }

        private boolean close(
                        Double left,
                        Double right) {

                if (left == null
                                || right == null) {
                        return left == right;
                }

                return Math.abs(left - right) <= priceTolerance;
        }

        private void markMatchedCandle(
                        MarketCandle candle) {

                if (candle.getQualityStatus() != CandleQualityStatus.REPAIRED) {
                        candle.setQualityStatus(
                                        CandleQualityStatus.RECONCILED);
                }

                candle.setProcessingStatus(
                                CandleProcessingStatus.RELEASED);

                candle.setIsFinalized(true);
                candle.setUpdatedAt(
                                timeProvider.nowDateTime());

                marketCandleRepository.save(candle);
        }

        private MarketCandle createRepairedCandle(
                        MarketCandle providerCandle,
                        String symbol,
                        String exchange,
                        LocalDateTime minute) {

                LocalDateTime now = timeProvider.nowDateTime();

                return MarketCandle.builder()
                                .symbol(symbol)
                                .exchange(exchange)
                                .timeframe(
                                                CandleTimeframe.ONE_MINUTE)
                                .candleTime(minute)
                                .openPrice(
                                                providerCandle.getOpenPrice())
                                .highPrice(
                                                providerCandle.getHighPrice())
                                .lowPrice(
                                                providerCandle.getLowPrice())
                                .closePrice(
                                                providerCandle.getClosePrice())
                                .volume(
                                                providerCandle.getVolume())
                                .openInterest(
                                                providerCandle.getOpenInterest())
                                .source(RECONCILIATION_SOURCE)
                                .createdAt(now)
                                .updatedAt(now)
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.REPAIRED)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }

        private void copyValues(
                        MarketCandle source,
                        MarketCandle target) {

                target.setOpenPrice(
                                source.getOpenPrice());

                target.setHighPrice(
                                source.getHighPrice());

                target.setLowPrice(
                                source.getLowPrice());

                target.setClosePrice(
                                source.getClosePrice());

                target.setVolume(
                                source.getVolume());

                target.setOpenInterest(
                                source.getOpenInterest());
        }

        private void markRepairedResolution(
                        String symbol,
                        String exchange,
                        LocalDateTime minute) {

                LocalDateTime now = timeProvider.nowDateTime();

                LiveMinuteResolution resolution = resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                symbol,
                                                exchange,
                                                minute)
                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .minuteTime(minute)
                                                .tradingDate(
                                                                minute.toLocalDate())
                                                .createdAt(now)
                                                .build());

                resolution.setStatus(
                                MinuteResolutionStatus.REPAIRED);

                resolution.setReason(
                                "SmartAPI EOD reconciliation returned a candle");

                resolution.setResolvedAt(now);
                resolution.setUpdatedAt(now);

                resolutionRepository.save(resolution);
        }

        private void markNoTradeResolution(
                        String symbol,
                        String exchange,
                        LocalDateTime minute) {

                LocalDateTime now = timeProvider.nowDateTime();

                LiveMinuteResolution resolution = resolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                symbol,
                                                exchange,
                                                minute)
                                .orElseGet(() -> LiveMinuteResolution.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .minuteTime(minute)
                                                .tradingDate(
                                                                minute.toLocalDate())
                                                .createdAt(now)
                                                .build());

                resolution.setStatus(
                                MinuteResolutionStatus.NO_TRADE_CONFIRMED);

                resolution.setReason(
                                "SmartAPI EOD reconciliation confirmed no trade");

                resolution.setResolvedAt(now);
                resolution.setUpdatedAt(now);

                resolutionRepository.save(resolution);
        }

        private void saveRawResponseToArchive(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        List<MarketCandle> candles) {

                if (rawArchiveDir == null
                                || rawArchiveDir.isBlank()
                                || candles == null) {
                        return;
                }

                try {
                        Path dateDir = Path.of(
                                        rawArchiveDir,
                                        tradingDate.toString());

                        Files.createDirectories(dateDir);

                        Path targetFile = dateDir.resolve(
                                        symbol.toUpperCase() + ".json");

                        List<RawBrokerCandleArchive> archive = candles.stream()
                                        .map(RawBrokerCandleArchive::from)
                                        .toList();

                        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                                        targetFile.toFile(),
                                        archive);

                } catch (Exception ex) {
                        log.warn(
                                        "Failed to archive raw broker response for symbol={} date={}: {}",
                                        symbol,
                                        tradingDate,
                                        ex.getMessage());
                }
        }

        public record RawBrokerCandleArchive(
                        String timestamp,
                        Double open,
                        Double high,
                        Double low,
                        Double close,
                        Long volume,
                        Long openInterest) {

                public static RawBrokerCandleArchive from(MarketCandle candle) {
                        return new RawBrokerCandleArchive(
                                        candle.getCandleTime() != null ? candle.getCandleTime().toString() : null,
                                        candle.getOpenPrice(),
                                        candle.getHighPrice(),
                                        candle.getLowPrice(),
                                        candle.getClosePrice(),
                                        candle.getVolume(),
                                        candle.getOpenInterest());
                }
        }

        private String safeMessage(
                        Throwable throwable) {

                return throwable == null
                                || throwable.getMessage() == null
                                || throwable.getMessage().isBlank()
                                                ? "Unknown error"
                                                : throwable.getMessage();
        }

        private record ReconciliationTarget(
                        String symbol,
                        String exchange) {
        }

        public record ReconciliationResult(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        DataStatus status,
                        int matchedCandles,
                        int repairedCandles,
                        int noTradeMinutes,
                        int unresolvedMinutes,
                        String message) {
        }

        public record ReconciliationBatchResult(
                        LocalDate tradingDate,
                        int symbolsProcessed,
                        int reconciledSymbols,
                        int repairedSymbols,
                        int partialSymbols,
                        String message) {
        }
}
