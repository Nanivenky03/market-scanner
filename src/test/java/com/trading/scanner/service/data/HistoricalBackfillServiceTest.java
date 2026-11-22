package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.EodDataEntry;
import com.trading.scanner.model.MarketCandle;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HistoricalBackfillServiceTest {

    private static final LocalDate SATURDAY = LocalDate.of(2026, 11, 21);

    private TradingCalendar calendar;
    private TimeProvider time;
    private StockUniverseRepository universe;
    private StockPriceRepository stockPrices;
    private MarketCandleRepository candles;
    private EodDataEntryRepository eodEntries;
    private EodDataEntryService eodService;
    private DailyDataStatusService statusService;
    private LiveMarketCandleService liveCandles;
    private DailyStockContextService contextService;
    private DailyStockContextRepository contexts;
    private VolumeBaselineService baselines;
    private VolumeDailyBaselineRepository dailyBaselines;
    private AngelOneSessionService session;
    private AngelOneMarketDataProvider provider;
    private HistoricalBackfillService service;
    private LocalDateTime now = SATURDAY.atTime(10, 0);

    @TempDir
    Path rawDir;

    @BeforeEach
    void setUp() {
        calendar = mock(TradingCalendar.class);
        when(calendar.isTradingDay(any())).thenAnswer(inv -> isWeekday(inv.getArgument(0)));
        when(calendar.nextTradingDay(any())).thenAnswer(inv -> {
            LocalDate d = ((LocalDate) inv.getArgument(0)).plusDays(1);
            while (!isWeekday(d)) d = d.plusDays(1);
            return d;
        });
        when(calendar.previousTradingDay(any())).thenAnswer(inv -> {
            LocalDate d = ((LocalDate) inv.getArgument(0)).minusDays(1);
            while (!isWeekday(d)) d = d.minusDays(1);
            return d;
        });
        when(calendar.addTradingDays(any(), anyInt())).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            int n = inv.getArgument(1);
            for (int i = 0; i < Math.abs(n); i++) {
                d = n < 0 ? calendar.previousTradingDay(d) : calendar.nextTradingDay(d);
            }
            return d;
        });

        time = new TimeProvider() {
            @Override public LocalDate today() { return now.toLocalDate(); }
            @Override public LocalDateTime nowDateTime() { return now; }
        };
        universe = mock(StockUniverseRepository.class);
        when(universe.findByIsActiveTrueOrderBySymbolAsc())
                .thenReturn(List.of(StockUniverse.builder().symbol("TCS").build()));
        stockPrices = mock(StockPriceRepository.class);
        candles = mock(MarketCandleRepository.class);
        when(candles.findEarliestCandleTimeExcludingSource(eq("NIFTY"), any(), eq(HistoricalBackfillService.SOURCE))).thenReturn(LocalDate.of(2026, 10, 6).atTime(9, 15));
        when(candles.findDistinctSymbolsByTimeframeAndCandleTimeRange(any(), any(), any())).thenReturn(List.of());
        when(candles.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        eodEntries = mock(EodDataEntryRepository.class);
        when(eodEntries.findBySymbolAndExchangeAndTradingDate(any(), any(), any())).thenReturn(Optional.empty());
        eodService = mock(EodDataEntryService.class);
        statusService = mock(DailyDataStatusService.class);
        liveCandles = mock(LiveMarketCandleService.class);
        contextService = mock(DailyStockContextService.class);
        contexts = mock(DailyStockContextRepository.class);
        baselines = mock(VolumeBaselineService.class);
        dailyBaselines = mock(VolumeDailyBaselineRepository.class);
        session = mock(AngelOneSessionService.class);
        provider = mock(AngelOneMarketDataProvider.class);
        when(provider.isAvailable()).thenReturn(true);

        service = new HistoricalBackfillService(calendar, time, universe, stockPrices, candles, eodEntries,
                eodService, statusService, liveCandles, contextService, contexts, baselines, dailyBaselines,
                new MarketIndexRegistry(), session);
        service.setProvider(provider);
        ReflectionTestUtils.setField(service, "rawDirectory", rawDir.toString());
    }

    private static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private HistoricalBackfillService.RunStatus awaitFinished() throws Exception {
        for (int i = 0; i < 200; i++) {
            HistoricalBackfillService.RunStatus s = service.status();
            if (s != null && s.finishedAt() != null) {
                return s;
            }
            Thread.sleep(50);
        }
        fail("run did not finish");
        return null;
    }

    private List<MarketCandle> oneSession(LocalDate date) {
        List<MarketCandle> list = new ArrayList<>();
        // 09:15 carries pre-open style volume; 09:16 and 15:29 present; everything else absent (a thin symbol)
        list.add(candle(date.atTime(9, 15), 100.0, 105_000L));
        list.add(candle(date.atTime(9, 16), 101.0, 3_000L));
        list.add(candle(date.atTime(15, 29), 102.0, 90_000L));
        return list;
    }

    private MarketCandle candle(LocalDateTime t, double price, long volume) {
        return MarketCandle.builder().symbol("TCS").exchange("NSE").timeframe(CandleTimeframe.ONE_MINUTE)
                .candleTime(t).openPrice(price).highPrice(price + 1).lowPrice(price - 1).closePrice(price)
                .volume(volume).source("ANGEL_ONE").qualityStatus(CandleQualityStatus.LIVE).build();
    }

    private HistoricalBackfillService.RunRequest tcsRequest(int maxDates, boolean dryRun) {
        return new HistoricalBackfillService.RunRequest(2, LocalDate.of(2026, 10, 2), List.of("TCS"), maxDates, dryRun, false);
    }

    @Test
    void startIsRefusedWhileTheLivePipelineIsActive() {
        now = LocalDate.of(2026, 11, 23).atTime(10, 0); // Monday, market hours
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.start(tcsRequest(1, false)));
        assertTrue(ex.getMessage().contains("live pipeline is active"));
        verify(provider, never()).fetchHistoricalBars(any(), any(), any());
        verify(provider, never()).fetchHistoricalOneMinuteForBackfill(any(), any());
    }

    @Test
    void startIsRefusedWhenAngelOneIsDisabled() {
        when(provider.isAvailable()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.start(tcsRequest(1, false)));
    }

    @Test
    void dryRunArchivesRawResponsesButWritesNothingToTheDatabase() throws Exception {
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(eq("TCS"), any())).thenAnswer(inv ->
                new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(oneSession(inv.getArgument(1)), "[[\"raw\"]]"));

        service.start(tcsRequest(2, true));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals("COMPLETED", status.state());
        assertTrue(status.dryRun());
        assertEquals(0, status.candlesInserted());
        verify(candles, never()).saveAll(anyList());
        verify(stockPrices, never()).save(any());
        verifyNoInteractions(eodService, liveCandles, contextService);

        Path run = Path.of(status.rawArchiveDirectory());
        try (Stream<Path> files = Files.walk(run)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("TCS.json")));
        }
        assertTrue(Files.exists(run.resolve("summary.json")));
    }

    @Test
    void candlesAreStoredAsDeliveredWithADistinctSourceAndNothingIsFabricated() throws Exception {
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(eq("TCS"), any())).thenAnswer(inv ->
                new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(oneSession(inv.getArgument(1)), "[]"));
        when(candles.findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                eq("TCS"), eq("NSE"), eq(CandleTimeframe.ONE_MINUTE), any(), any()))
                .thenReturn(List.of(candle(LocalDate.of(2026, 9, 30).atTime(9, 16), 101.0, 3_000L)));

        service.start(tcsRequest(1, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals("COMPLETED", status.state());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketCandle>> saved = ArgumentCaptor.forClass(List.class);
        verify(candles).saveAll(saved.capture());
        assertEquals(3, saved.getValue().size(), "only the delivered candles are stored; quiet minutes are not filled");
        assertEquals(105_000L, saved.getValue().get(0).getVolume(), "volume is stored untouched");
        assertEquals(100.0, saved.getValue().get(0).getOpenPrice(), "open is stored untouched");
        saved.getValue().forEach(c -> {
            assertEquals(HistoricalBackfillService.SOURCE, c.getSource());
            assertEquals(CandleQualityStatus.RECONCILED, c.getQualityStatus());
        });
        verify(eodService).complete(eq("TCS"), eq("NSE"), any(), eq(DataStatus.RECONCILED),
                eq(3), eq(3), eq(0), eq(0), eq(0), eq(3), eq(HistoricalBackfillService.SOURCE), anyString());
        verify(liveCandles).recomputeDerivedData(eq("TCS"), eq("NSE"), any());
        verify(contextService).processFinalizedOneMinuteCandle(any());
        assertEquals(3, status.candlesInserted());
    }

    @Test
    void dailyBarsGoToStockPricesOnlyWhenAbsentAndAreNotCrossCheckedAgainstCandles() throws Exception {
        LocalDate d1 = LocalDate.of(2026, 9, 29);
        LocalDate d2 = LocalDate.of(2026, 9, 30);
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of(
                new DailyBarDto("TCS", d1, 10.0, 12.0, 9.0, 11.0, 11.0, 5_000L, "ANGEL_ONE"),
                new DailyBarDto("TCS", d2, 20.0, 22.0, 19.0, 21.0, 21.0, 6_000L, "ANGEL_ONE")));
        when(stockPrices.findBySymbolAndDate("TCS", d1)).thenReturn(Optional.of(
                com.trading.scanner.model.StockPrice.builder().symbol("TCS").date(d1).build()));
        when(provider.fetchHistoricalOneMinuteForBackfill(any(), any())).thenReturn(
                new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(List.of(), "[]"));

        service.start(tcsRequest(1, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals(1, status.dailyBarsInserted());
        ArgumentCaptor<com.trading.scanner.model.StockPrice> saved = ArgumentCaptor.forClass(com.trading.scanner.model.StockPrice.class);
        verify(stockPrices).save(saved.capture());
        assertEquals(d2, saved.getValue().getDate());
        assertEquals(20.0, saved.getValue().getOpenPrice());
        assertEquals(6_000, saved.getValue().getVolume());
    }

    @Test
    void symbolDaysThatAlreadyHaveCandlesOrACheckpointAreNotFetchedAgain() throws Exception {
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of());
        // the first planned date already has TCS candles; the second has a backfill checkpoint
        when(candles.findDistinctSymbolsByTimeframeAndCandleTimeRange(any(), any(), any()))
                .thenReturn(List.of("TCS"))
                .thenReturn(List.of());
        EodDataEntry checkpoint = EodDataEntry.builder().source(HistoricalBackfillService.SOURCE).actualMinuteCount(3).build();
        when(eodEntries.findBySymbolAndExchangeAndTradingDate(eq("TCS"), eq("NSE"), any()))
                .thenReturn(Optional.of(checkpoint));

        service.start(tcsRequest(2, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        verify(provider, never()).fetchHistoricalOneMinuteForBackfill(any(), any());
        assertEquals(2, status.skippedAlreadyPresent());
    }

    @Test
    void anEmptyResponseIsRecordedAsNoDataAndNotAsAFailureOrAFabricatedCandle() throws Exception {
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(eq("TCS"), any())).thenReturn(
                new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(List.of(), "[]"));

        service.start(tcsRequest(1, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals(1, status.noData());
        assertEquals(0, status.failures());
        verify(candles, never()).saveAll(anyList());
        verify(eodService).complete(eq("TCS"), eq("NSE"), any(), eq(DataStatus.PARTIAL),
                eq(0), eq(0), eq(0), eq(0), eq(0), eq(0), eq(HistoricalBackfillService.SOURCE), anyString());
    }

    @Test
    void theRunAbortsAfterRepeatedConsecutiveApiFailuresInsteadOfHammeringTheBroker() throws Exception {
        when(provider.fetchHistoricalBars(any(), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(any(), any()))
                .thenThrow(new ProviderException("HTTP 403 forbidden"));
        // enough targets and sessions that 25 consecutive failures are reachable
        List<StockUniverse> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(StockUniverse.builder().symbol("S" + i).build());
        }
        when(universe.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(many);

        service.start(new HistoricalBackfillService.RunRequest(2, LocalDate.of(2026, 10, 2), null, 1, false, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals("ABORTED", status.state());
        assertTrue(status.failures() >= 25);
        assertTrue(status.message().contains("consecutive"));
    }

    @Test
    void sessionExpiryIsRecognisedSoTheSessionCanBeRefreshed() {
        assertTrue(HistoricalBackfillService.isSessionProblem(new ProviderException("fetch failed. message=Invalid Token, errorcode=AG8001")));
        assertFalse(HistoricalBackfillService.isSessionProblem(new ProviderException("HTTP 403 forbidden")));
    }

    private static final String RATE_LIMITED =
            "Angel One API request failed after retries for endpoint=HISTORICAL_CANDLE (Angel One rate limit exceeded)";

    @Test
    void aRateLimitedCallIsRetriedAtTheEndOfTheSessionInsteadOfBeingLost() throws Exception {
        service.setCooldownMillis(1);
        when(provider.fetchHistoricalBars(eq("TCS"), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(eq("TCS"), any()))
                .thenThrow(new ProviderException(RATE_LIMITED))
                .thenAnswer(inv -> new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(
                        oneSession(inv.getArgument(1)), "[]"));

        service.start(tcsRequest(1, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals("COMPLETED", status.state());
        assertEquals(0, status.failures(), "a rate-limited call that succeeds on retry is not a failure");
        assertEquals(3, status.candlesInserted());
        verify(provider, times(2)).fetchHistoricalOneMinuteForBackfill(eq("TCS"), any());
    }

    @Test
    void persistentRateLimitingCoolsDownThenStopsWithoutCountingAsApiFailures() throws Exception {
        service.setCooldownMillis(1);
        when(provider.fetchHistoricalBars(any(), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(any(), any()))
                .thenThrow(new ProviderException(RATE_LIMITED));
        List<StockUniverse> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(StockUniverse.builder().symbol("S" + i).build());
        }
        when(universe.findByIsActiveTrueOrderBySymbolAsc()).thenReturn(many);

        service.start(new HistoricalBackfillService.RunRequest(2, LocalDate.of(2026, 10, 2), null, 1, false, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        assertEquals("ABORTED", status.state());
        assertTrue(status.message().contains("kept rate limiting"), status.message());
        verify(candles, never()).saveAll(anyList());
    }

    @Test
    void rateLimitMessagesAreRecognised() {
        assertTrue(HistoricalBackfillService.isRateLimit(new ProviderException(RATE_LIMITED)));
        assertTrue(HistoricalBackfillService.isRateLimit(new RuntimeException("x", new ProviderException("AB1021 Too many requests"))));
        assertFalse(HistoricalBackfillService.isRateLimit(new ProviderException("HTTP 403 forbidden")));
    }

    @Test
    void theDefaultRangeIsAnchoredOnCandlesThisBackfillDidNotWrite() throws Exception {
        when(provider.fetchHistoricalBars(any(), any(), any())).thenReturn(List.of());
        when(provider.fetchHistoricalOneMinuteForBackfill(any(), any())).thenReturn(
                new AngelOneMarketDataProvider.HistoricalOneMinuteFetch(List.of(), "[]"));

        service.start(new HistoricalBackfillService.RunRequest(3, null, List.of("TCS"), 1, true, false));
        HistoricalBackfillService.RunStatus status = awaitFinished();

        // earliest non-backfill NIFTY candle is 2026-10-06, so the range ends on the prior session (2026-10-05 in
        // the weekday-only test calendar) and 3 sessions reach back to 2026-10-01
        assertEquals(LocalDate.of(2026, 10, 1), status.fromDate());
    }
}
