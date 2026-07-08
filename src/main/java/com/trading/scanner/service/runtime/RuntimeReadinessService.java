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
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuntimeReadinessService {

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

        @Transactional(readOnly = true)
        public ReadinessStatus status() {
                LocalDate today = timeProvider.today();
                boolean tradingDay = isTradingDay(today);
                boolean marketSessionOpen = isMarketSessionOpen(timeProvider.nowDateTime());

                List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();

                int activeUniverseCount = activeUniverse.size();
                long instrumentMasterCount = instrumentMasterRepository.count();
                long dailyPriceRowCount = stockPriceRepository.count();

                long historicalOneMinuteCount = marketCandleRepository.countByTimeframe(CandleTimeframe.ONE_MINUTE);
                long liveOneMinuteCount = marketCandleRepository.countByTimeframeAndSource(
                                CandleTimeframe.ONE_MINUTE, "LIVE_WEBSOCKET");
                long liveDerivedFiveMinuteCount = marketCandleRepository.countByTimeframeAndSource(
                                CandleTimeframe.FIVE_MINUTE, "DERIVED_FROM_LIVE_ONE_MINUTE");
                long liveDerivedFifteenMinuteCount = marketCandleRepository.countByTimeframeAndSource(
                                CandleTimeframe.FIFTEEN_MINUTE, "DERIVED_FROM_LIVE_ONE_MINUTE");
                long liveSignalCount = liveSimulationSignalRepository.count();

                List<MissingBrokerToken> missingBrokerTokens = new ArrayList<>();
                for (int i = 0; i < activeUniverse.size(); i++) {
                        StockUniverse stock = activeUniverse.get(i);
                        InstrumentMaster instrument = instrumentMasterRepository
                                        .findBySymbolAndExchange(stock.getSymbol(), stock.getExchange().name())
                                        .orElse(null);

                        if (instrument == null || instrument.getBrokerToken() == null
                                        || instrument.getBrokerToken().isBlank()) {
                                missingBrokerTokens.add(new MissingBrokerToken(
                                                stock.getSymbol(),
                                                stock.getExchange().name()));
                        }
                }

                AngelOneWebSocketService.Status websocketStatus = angelOneWebSocketService.status();

                boolean readyForHistoricalData = activeUniverseCount > 0
                                && instrumentMasterCount > 0
                                && missingBrokerTokens.isEmpty()
                                && dailyPriceRowCount > 0
                                && historicalOneMinuteCount > 0;

                boolean readyForLiveRuntime = activeUniverseCount > 0
                                && instrumentMasterCount > 0
                                && missingBrokerTokens.isEmpty();

                boolean readyForCloudDeployment = readyForLiveRuntime
                                && runtimeAutomationProperties.getLive().isAutoRun();

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
                                runtimeAutomationProperties.getLive().isAutoRun(),
                                runtimeSettingService.subscriptionMode(),
                                websocketStatus.connected(),
                                websocketStatus.messagesReceived(),
                                websocketStatus.parsedTicksReceived(),
                                websocketStatus.parserFailures(),
                                missingBrokerTokens.size(),
                                missingBrokerTokens,
                                readyForHistoricalData,
                                readyForLiveRuntime,
                                readyForCloudDeployment);
        }

        @Transactional(readOnly = true)
        public boolean isTradingDay(LocalDate date) {
                return tradingCalendar.isTradingDay(date);
        }

        @Transactional(readOnly = true)
        public boolean isMarketSessionOpen(LocalDateTime dateTime) {
                if (!isTradingDay(dateTime.toLocalDate())) {
                        return false;
                }

                LocalTime currentTime = dateTime.toLocalTime();
                LocalTime marketOpen = exchangeConfiguration.getMarketOpen();
                LocalTime marketClose = exchangeConfiguration.getMarketClose();

                return !currentTime.isBefore(marketOpen) && currentTime.isBefore(marketClose);
        }

        public record MissingBrokerToken(
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
                        boolean readyForHistoricalData,
                        boolean readyForLiveRuntime,
                        boolean readyForCloudDeployment) {
        }
}