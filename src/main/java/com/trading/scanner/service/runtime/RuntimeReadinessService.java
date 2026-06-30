package com.trading.scanner.service.runtime;

import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.EmergencyClosureRepository;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
    private final EmergencyClosureRepository emergencyClosureRepository;
    private final AngelOneWebSocketService angelOneWebSocketService;
    private final RuntimeSettingService runtimeSettingService;
    private final RuntimeAutomationProperties runtimeAutomationProperties;
    private final TimeProvider timeProvider;

    @Transactional(readOnly = true)
    public ReadinessStatus status() {
        LocalDate today = timeProvider.today();
        boolean tradingDay = isTradingDay(today);

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
        for (StockUniverse stock : activeUniverse) {
            InstrumentMaster instrument = instrumentMasterRepository
                    .findBySymbolAndExchange(stock.getSymbol(), stock.getExchange().name())
                    .orElse(null);

            if (instrument == null || instrument.getBrokerToken() == null || instrument.getBrokerToken().isBlank()) {
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

        boolean readyForCloudDeployment = readyForLiveRuntime && runtimeAutomationProperties.getLive().isAutoRun();

        return new ReadinessStatus(
                today,
                tradingDay,
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
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        return !emergencyClosureRepository.existsByDate(date);
    }

    public record MissingBrokerToken(
            String symbol,
            String exchange) {
    }

    public record ReadinessStatus(
            LocalDate businessDate,
            boolean tradingDay,
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