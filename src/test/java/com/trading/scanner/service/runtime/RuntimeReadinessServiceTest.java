package com.trading.scanner.service.runtime;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.ExchangeConfiguration;
import com.trading.scanner.config.RuntimeAutomationProperties;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.repository.InstrumentMasterRepository;
import com.trading.scanner.repository.LiveSimulationSignalRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.provider.angelone.AngelOneWebSocketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuntimeReadinessServiceTest {

    @Mock
    private StockUniverseRepository stockUniverseRepository;

    @Mock
    private InstrumentMasterRepository instrumentMasterRepository;

    @Mock
    private StockPriceRepository stockPriceRepository;

    @Mock
    private MarketCandleRepository marketCandleRepository;

    @Mock
    private LiveSimulationSignalRepository liveSimulationSignalRepository;

    @Mock
    private AngelOneWebSocketService angelOneWebSocketService;

    @Mock
    private RuntimeSettingService runtimeSettingService;

    @Mock
    private TimeProvider timeProvider;

    @Mock
    private TradingCalendar tradingCalendar;

    @Mock
    private ExchangeConfiguration exchangeConfiguration;

    private RuntimeAutomationProperties runtimeAutomationProperties;
    private RuntimeReadinessService runtimeReadinessService;

    @BeforeEach
    void setUp() {
        runtimeAutomationProperties = new RuntimeAutomationProperties();
        runtimeAutomationProperties.getLive().setAutoRun(true);

        runtimeReadinessService = new RuntimeReadinessService(
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
                exchangeConfiguration);
    }

    @Test
    void isTradingDay_shouldDelegateToTradingCalendar() {
        LocalDate date = LocalDate.of(2026, 7, 7);

        when(tradingCalendar.isTradingDay(date)).thenReturn(true);

        boolean result = runtimeReadinessService.isTradingDay(date);

        assertEquals(true, result);
        verify(tradingCalendar, times(1)).isTradingDay(date);
    }

    @Test
    void isMarketSessionOpen_shouldReturnFalseWhenNotTradingDay() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 15, 10, 0);

        when(tradingCalendar.isTradingDay(dateTime.toLocalDate())).thenReturn(false);

        boolean result = runtimeReadinessService.isMarketSessionOpen(dateTime);

        assertFalse(result);
    }

    @Test
    void isMarketSessionOpen_shouldReturnTrueInsideSessionWindow() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 7, 7, 10, 0);

        when(tradingCalendar.isTradingDay(dateTime.toLocalDate())).thenReturn(true);
        when(exchangeConfiguration.getMarketOpen()).thenReturn(LocalTime.of(9, 15));
        when(exchangeConfiguration.getMarketClose()).thenReturn(LocalTime.of(15, 30));

        boolean result = runtimeReadinessService.isMarketSessionOpen(dateTime);

        assertTrue(result);
    }

    @Test
    void isMarketSessionOpen_shouldReturnFalseBeforeOpen() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 7, 7, 9, 0);

        when(tradingCalendar.isTradingDay(dateTime.toLocalDate())).thenReturn(true);
        when(exchangeConfiguration.getMarketOpen()).thenReturn(LocalTime.of(9, 15));
        when(exchangeConfiguration.getMarketClose()).thenReturn(LocalTime.of(15, 30));

        boolean result = runtimeReadinessService.isMarketSessionOpen(dateTime);

        assertFalse(result);
    }

    @Test
    void isMarketSessionOpen_shouldReturnFalseAtOrAfterClose() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 7, 7, 15, 30);

        when(tradingCalendar.isTradingDay(dateTime.toLocalDate())).thenReturn(true);
        when(exchangeConfiguration.getMarketOpen()).thenReturn(LocalTime.of(9, 15));
        when(exchangeConfiguration.getMarketClose()).thenReturn(LocalTime.of(15, 30));

        boolean result = runtimeReadinessService.isMarketSessionOpen(dateTime);

        assertFalse(result);
    }

    @Test
    void status_shouldUseTradingCalendarAndSessionFlag() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 10, 0);

        when(timeProvider.today()).thenReturn(today);
        when(timeProvider.nowDateTime()).thenReturn(now);
        when(tradingCalendar.isTradingDay(today)).thenReturn(false);

        when(stockUniverseRepository.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(List.of());
        when(instrumentMasterRepository.count()).thenReturn(0L);
        when(stockPriceRepository.count()).thenReturn(0L);

        when(marketCandleRepository.countByTimeframe(CandleTimeframe.ONE_MINUTE)).thenReturn(0L);
        when(marketCandleRepository.countByTimeframeAndSource(CandleTimeframe.ONE_MINUTE, "LIVE_WEBSOCKET"))
                .thenReturn(0L);
        when(marketCandleRepository.countByTimeframeAndSource(CandleTimeframe.FIVE_MINUTE,
                "DERIVED_FROM_LIVE_ONE_MINUTE")).thenReturn(0L);
        when(marketCandleRepository.countByTimeframeAndSource(CandleTimeframe.FIFTEEN_MINUTE,
                "DERIVED_FROM_LIVE_ONE_MINUTE")).thenReturn(0L);

        when(liveSimulationSignalRepository.count()).thenReturn(0L);
        when(runtimeSettingService.subscriptionMode()).thenReturn(1);

        when(angelOneWebSocketService.status()).thenReturn(new AngelOneWebSocketService.Status(
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                0L,
                0L,
                0L,
                0L));

        RuntimeReadinessService.ReadinessStatus status = runtimeReadinessService.status();

        assertFalse(status.tradingDay());
        assertFalse(status.marketSessionOpen());
        assertEquals(today, status.businessDate());
        verify(tradingCalendar, times(2)).isTradingDay(today);
    }
}