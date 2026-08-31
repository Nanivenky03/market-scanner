package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.data.EodDataEntryService;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeReadinessService {

        private static final String BOOTSTRAP_STATUS_KEY = BaseDataResetService.BOOTSTRAP_STATUS_KEY;

        private static final String BOOTSTRAP_COMPLETE = "COMPLETE";

        private static final String BOOTSTRAP_REQUIRED = "REQUIRED";

        private static final String REQUIRED_INDEX_SYMBOL = "NIFTY";

        private static final String REQUIRED_INDEX_EXCHANGE = "NSE";

        private final StockUniverseRepository stockUniverseRepository;
        private final InstrumentMasterRepository instrumentMasterRepository;
        private final StockPriceRepository stockPriceRepository;
        private final MarketCandleRepository marketCandleRepository;
        private final LiveSimulationSignalRepository liveSimulationSignalRepository;
        private final AngelOneWebSocketService angelOneWebSocketService;
        private final RuntimeSettingService runtimeSettingService;
        private final RuntimeAutomationProperties runtimeAutomationProperties;
        private final TimeProvider timeProvider;
        private final TradingCalendar tradingCalendar;
        private final ExchangeConfiguration exchangeConfiguration;
        private final EodDataEntryService eodDataEntryService;

        @Autowired
        public RuntimeReadinessService(
                        StockUniverseRepository stockUniverseRepository,
                        InstrumentMasterRepository instrumentMasterRepository,
                        StockPriceRepository stockPriceRepository,
                        MarketCandleRepository marketCandleRepository,
                        LiveSimulationSignalRepository liveSimulationSignalRepository,
                        AngelOneWebSocketService angelOneWebSocketService,
                        RuntimeSettingService runtimeSettingService,
                        RuntimeAutomationProperties runtimeAutomationProperties,
                        TimeProvider timeProvider,
                        TradingCalendar tradingCalendar,
                        ExchangeConfiguration exchangeConfiguration,
                        EodDataEntryService eodDataEntryService) {

                this.stockUniverseRepository = stockUniverseRepository;
                this.instrumentMasterRepository = instrumentMasterRepository;
                this.stockPriceRepository = stockPriceRepository;
                this.marketCandleRepository = marketCandleRepository;
                this.liveSimulationSignalRepository = liveSimulationSignalRepository;
                this.angelOneWebSocketService = angelOneWebSocketService;
                this.runtimeSettingService = runtimeSettingService;
                this.runtimeAutomationProperties = runtimeAutomationProperties;
                this.timeProvider = timeProvider;
                this.tradingCalendar = tradingCalendar;
                this.exchangeConfiguration = exchangeConfiguration;
                this.eodDataEntryService = eodDataEntryService;
        }

        /*
         * Backward-compatible constructor for existing tests and callers.
         */
        public RuntimeReadinessService(
                        StockUniverseRepository stockUniverseRepository,
                        InstrumentMasterRepository instrumentMasterRepository,
                        StockPriceRepository stockPriceRepository,
                        MarketCandleRepository marketCandleRepository,
                        LiveSimulationSignalRepository liveSimulationSignalRepository,
                        AngelOneWebSocketService angelOneWebSocketService,
                        RuntimeSettingService runtimeSettingService,
                        RuntimeAutomationProperties runtimeAutomationProperties,
                        TimeProvider timeProvider,
                        TradingCalendar tradingCalendar,
                        ExchangeConfiguration exchangeConfiguration) {

                this(
                                stockUniverseRepository,
                                instrumentMasterRepository,
                                stockPriceRepository,
                                marketCandleRepository,
                                liveSimulationSignalRepository,
                                angelOneWebSocketService,
                                runtimeSettingService,
                                runtimeAutomationProperties,
                                timeProvider,
                                tradingCalendar,
                                exchangeConfiguration,
                                null);
        }

        @Transactional(readOnly = true)
        public ReadinessStatus status() {
                LocalDate today = timeProvider.today();

                boolean tradingDay = isTradingDay(today);

                boolean marketSessionOpen = isMarketSessionOpen(
                                timeProvider.nowDateTime());

                List<StockUniverse> activeUniverse = stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc();

                int activeUniverseCount = activeUniverse.size();

                long instrumentMasterCount = instrumentMasterRepository.count();

                long dailyPriceRowCount = stockPriceRepository.count();

                long historicalOneMinuteCount = marketCandleRepository
                                .countByTimeframe(
                                                CandleTimeframe.ONE_MINUTE);

                long liveOneMinuteCount = marketCandleRepository
                                .countByTimeframeAndSource(
                                                CandleTimeframe.ONE_MINUTE,
                                                "LIVE_WEBSOCKET");

                long liveDerivedFiveMinuteCount = marketCandleRepository
                                .countByTimeframeAndSource(
                                                CandleTimeframe.FIVE_MINUTE,
                                                "DERIVED_FROM_LIVE_ONE_MINUTE");

                long liveDerivedFifteenMinuteCount = marketCandleRepository
                                .countByTimeframeAndSource(
                                                CandleTimeframe.FIFTEEN_MINUTE,
                                                "DERIVED_FROM_LIVE_ONE_MINUTE");

                long liveSignalCount = liveSimulationSignalRepository.count();

                List<MissingBrokerToken> missingBrokerTokens = new ArrayList<>();

                for (StockUniverse stock : activeUniverse) {
                        InstrumentMaster instrument = instrumentMasterRepository
                                        .findBySymbolAndExchange(
                                                        stock.getSymbol(),
                                                        stock.getExchange().name())
                                        .orElse(null);

                        if (instrument == null
                                        || instrument.getBrokerToken() == null
                                        || instrument.getBrokerToken().isBlank()) {

                                missingBrokerTokens.add(
                                                new MissingBrokerToken(
                                                                stock.getSymbol(),
                                                                stock.getExchange().name()));
                        }
                }

                AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.status();

                String bootstrapStatus = bootstrapStatus();

                boolean bootstrapStatusComplete = BOOTSTRAP_COMPLETE.equalsIgnoreCase(
                                bootstrapStatus);

                boolean baseDataComplete = bootstrapStatusComplete
                                && requiredPreviousDayEodComplete(
                                                today,
                                                tradingDay,
                                                activeUniverse);

                boolean bootstrapReady = bootstrapStatusComplete
                                && baseDataComplete;

                boolean readyForHistoricalData = activeUniverseCount > 0
                                && instrumentMasterCount > 0
                                && missingBrokerTokens.isEmpty()
                                && dailyPriceRowCount > 0
                                && historicalOneMinuteCount > 0
                                && bootstrapReady;

                boolean readyForLiveRuntime = activeUniverseCount > 0
                                && instrumentMasterCount > 0
                                && missingBrokerTokens.isEmpty()
                                && bootstrapReady;

                boolean readyForCloudDeployment = readyForLiveRuntime
                                && runtimeAutomationProperties
                                                .getLive()
                                                .isAutoRun();

                return new ReadinessStatus(
                                today,
                                tradingDay,
                                marketSessionOpen,
                                activeUniverseCount,
                                instrumentMasterCount,
                                dailyPriceRowCount,
                                historicalOneMinuteCount,
                                liveOneMinuteCount,
                                liveDerivedFiveMinuteCount,
                                liveDerivedFifteenMinuteCount,
                                liveSignalCount,
                                runtimeAutomationProperties
                                                .getLive()
                                                .isAutoRun(),
                                runtimeSettingService.subscriptionMode(),
                                websocketStatus.connected(),
                                websocketStatus.messagesReceived(),
                                websocketStatus.parsedTicksReceived(),
                                websocketStatus.parserFailures(),
                                missingBrokerTokens.size(),
                                missingBrokerTokens,
                                bootstrapStatus,
                                bootstrapReady,
                                readyForHistoricalData,
                                readyForLiveRuntime,
                                readyForCloudDeployment);
        }

        @Transactional(readOnly = true)
        public boolean bootstrapReady() {
                String status = bootstrapStatus();

                if (!BOOTSTRAP_COMPLETE.equalsIgnoreCase(status)) {
                        return false;
                }

                if (eodDataEntryService == null) {
                        return true;
                }

                LocalDate today = timeProvider.today();

                boolean tradingDay = tradingCalendar.isTradingDay(today);

                List<StockUniverse> activeUniverse = stockUniverseRepository
                                .findByIsActiveTrueOrderBySymbolAsc();

                return requiredPreviousDayEodComplete(
                                today,
                                tradingDay,
                                activeUniverse);
        }

        @Transactional(readOnly = true)
        public boolean isTradingDay(LocalDate date) {
                return tradingCalendar.isTradingDay(date);
        }

        @Transactional(readOnly = true)
        public boolean isMarketSessionOpen(
                        LocalDateTime dateTime) {

                if (!isTradingDay(dateTime.toLocalDate())) {
                        return false;
                }

                LocalTime currentTime = dateTime.toLocalTime();

                LocalTime marketOpen = exchangeConfiguration.getMarketOpen();

                LocalTime marketClose = exchangeConfiguration.getMarketClose();

                return !currentTime.isBefore(marketOpen)
                                && currentTime.isBefore(marketClose);
        }

        private boolean requiredPreviousDayEodComplete(
                        LocalDate today,
                        boolean tradingDay,
                        List<StockUniverse> activeUniverse) {

                if (eodDataEntryService == null) {
                        return true;
                }

                if (!tradingDay) {
                        return true;
                }

                if (activeUniverse == null
                                || activeUniverse.isEmpty()) {
                        return false;
                }

                LocalDate previousTradingDay = tradingCalendar.previousTradingDay(today);

                Map<String, RequiredSymbol> requiredSymbols = requiredSymbols(activeUniverse);

                for (RequiredSymbol required : requiredSymbols.values()) {
                        if (!eodDataEntryService.isSuccessful(
                                        required.symbol(),
                                        required.exchange(),
                                        previousTradingDay)) {
                                return false;
                        }
                }

                return true;
        }

        private Map<String, RequiredSymbol> requiredSymbols(
                        List<StockUniverse> activeUniverse) {

                Map<String, RequiredSymbol> result = new LinkedHashMap<>();

                for (StockUniverse stock : activeUniverse) {
                        if (stock == null
                                        || stock.getSymbol() == null
                                        || stock.getExchange() == null) {
                                return Map.of(
                                                "INVALID",
                                                new RequiredSymbol(
                                                                "INVALID",
                                                                "INVALID"));
                        }

                        String symbol = stock.getSymbol()
                                        .trim()
                                        .toUpperCase();

                        String exchange = stock.getExchange()
                                        .name()
                                        .trim()
                                        .toUpperCase();

                        result.putIfAbsent(
                                        exchange + "|" + symbol,
                                        new RequiredSymbol(
                                                        symbol,
                                                        exchange));
                }

                result.putIfAbsent(
                                REQUIRED_INDEX_EXCHANGE
                                                + "|"
                                                + REQUIRED_INDEX_SYMBOL,
                                new RequiredSymbol(
                                                REQUIRED_INDEX_SYMBOL,
                                                REQUIRED_INDEX_EXCHANGE));

                return result;
        }

        private String bootstrapStatus() {
                String value = runtimeSettingService.getString(
                                BOOTSTRAP_STATUS_KEY,
                                BOOTSTRAP_REQUIRED);

                if (value == null || value.isBlank()) {
                        return BOOTSTRAP_REQUIRED;
                }

                return value.trim();
        }

        public record MissingBrokerToken(
                        String symbol,
                        String exchange) {
        }

        private record RequiredSymbol(
                        String symbol,
                        String exchange) {
        }

        public record ReadinessStatus(
                        LocalDate businessDate,
                        boolean tradingDay,
                        boolean marketSessionOpen,
                        int activeUniverseCount,
                        long instrumentMasterCount,
                        long dailyPriceRowCount,
                        long historicalOneMinuteCount,
                        long liveOneMinuteCount,
                        long liveDerivedFiveMinuteCount,
                        long liveDerivedFifteenMinuteCount,
                        long liveSignalCount,
                        boolean autoRunEnabled,
                        int subscriptionMode,
                        boolean websocketConnected,
                        long websocketMessagesReceived,
                        long websocketParsedTicksReceived,
                        long websocketParserFailures,
                        int missingBrokerTokenCount,
                        List<MissingBrokerToken> missingBrokerTokens,
                        String bootstrapStatus,
                        boolean bootstrapReady,
                        boolean readyForHistoricalData,
                        boolean readyForLiveRuntime,
                        boolean readyForCloudDeployment) {
        }
}
