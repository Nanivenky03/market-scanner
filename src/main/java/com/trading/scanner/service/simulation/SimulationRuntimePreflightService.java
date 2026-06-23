package com.trading.scanner.service.simulation;

import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.InstrumentMaster;
import com.trading.scanner.model.SimulationState;
import com.trading.scanner.model.StockUniverse;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.SimulationStateRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Profile("simulation")
@RequiredArgsConstructor
public class SimulationRuntimePreflightService {

    private final StockUniverseRepository stockUniverseRepository;
    private final InstrumentMasterRepository instrumentMasterRepository;
    private final StockPriceRepository stockPriceRepository;
    private final MarketCandleRepository marketCandleRepository;
    private final LiveSimulationSignalRepository liveSimulationSignalRepository;
    private final SimulationStateRepository simulationStateRepository;
    private final AngelOneWebSocketService angelOneWebSocketService;

    @Value("${runtime.live.auto-run:false}")
    private boolean autoRunEnabled;

    @Value("${runtime.live.subscription-mode:1}")
    private int subscriptionMode;

    @Transactional(readOnly = true)
    public PreflightStatus status() {
        List<StockUniverse> activeUniverse = stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc();

        int activeUniverseCount = activeUniverse.size();
        long instrumentMasterCount = instrumentMasterRepository.count();
        long dailyPriceRowCount = stockPriceRepository.count();

        long historicalOneMinuteCount = marketCandleRepository.countByTimeframe(CandleTimeframe.ONE_MINUTE);
        long liveOneMinuteCount = marketCandleRepository.countByTimeframeAndSource(CandleTimeframe.ONE_MINUTE,
                "LIVE_WEBSOCKET");
        long liveDerivedFiveMinuteCount = marketCandleRepository.countByTimeframeAndSource(CandleTimeframe.FIVE_MINUTE,
                "DERIVED_FROM_LIVE_ONE_MINUTE");
        long liveDerivedFifteenMinuteCount = marketCandleRepository
                .countByTimeframeAndSource(CandleTimeframe.FIFTEEN_MINUTE, "DERIVED_FROM_LIVE_ONE_MINUTE");
        long liveSignalCount = liveSimulationSignalRepository.count();

        SimulationState simulationState = simulationStateRepository.findById(1).orElse(null);

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

        boolean readyForHistoricalSimulation = activeUniverseCount > 0
                && instrumentMasterCount > 0
                && dailyPriceRowCount > 0
                && historicalOneMinuteCount > 0
                && missingBrokerTokens.isEmpty();

        boolean readyForMondayLiveValidation = activeUniverseCount > 0
                && instrumentMasterCount > 0
                && missingBrokerTokens.isEmpty();

        boolean readyForCloudSimulation = readyForMondayLiveValidation
                && autoRunEnabled
                && simulationState != null;

        return new PreflightStatus(
                simulationState != null,
                simulationState != null ? simulationState.getBaseDate() : null,
                activeUniverseCount,
                instrumentMasterCount,
                dailyPriceRowCount,
                historicalOneMinuteCount,
                liveOneMinuteCount,
                liveDerivedFiveMinuteCount,
                liveDerivedFifteenMinuteCount,
                liveSignalCount,
                autoRunEnabled,
                subscriptionMode,
                websocketStatus.connected(),
                websocketStatus.messagesReceived(),
                websocketStatus.parsedTicksReceived(),
                websocketStatus.parserFailures(),
                missingBrokerTokens.size(),
                missingBrokerTokens,
                readyForHistoricalSimulation,
                readyForMondayLiveValidation,
                readyForCloudSimulation);
    }

    public record MissingBrokerToken(
            String symbol,
            String exchange) {
    }

    public record PreflightStatus(
            boolean simulationStatePresent,
            LocalDate simulationBaseDate,
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
            boolean readyForHistoricalSimulation,
            boolean readyForMondayLiveValidation,
            boolean readyForCloudSimulation) {
    }
}