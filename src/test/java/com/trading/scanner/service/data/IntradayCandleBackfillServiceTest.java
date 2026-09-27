package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.LiveMinuteResolution;
import com.trading.scanner.model.MarketCandle;
import com.trading.scanner.model.MinuteResolutionStatus;
import com.trading.scanner.model.StockPrice;
import com.trading.scanner.repository.LiveMinuteResolutionRepository;
import com.trading.scanner.repository.MarketCandleRepository;
import com.trading.scanner.repository.StockPriceRepository;
import com.trading.scanner.service.provider.ProviderException;
import com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IntradayCandleBackfillServiceTest {

        @Mock
        private AngelOneMarketDataProvider angelOneMarketDataProvider;

        @Mock
        private MarketCandleRepository marketCandleRepository;

        @Mock
        private StockPriceRepository stockPriceRepository;

        @Mock
        private TimeProvider timeProvider;

        @Mock
        private PlatformTransactionManager transactionManager;

        @Mock
        private LiveMinuteResolutionRepository liveMinuteResolutionRepository;

        @Mock
        private TradingCalendar tradingCalendar;

        private IntradayCandleBackfillService service;

        @BeforeEach
        void setUp() {
                service = new IntradayCandleBackfillService(
                                angelOneMarketDataProvider,
                                marketCandleRepository,
                                stockPriceRepository,
                                timeProvider,
                                transactionManager,
                                liveMinuteResolutionRepository,
                                tradingCalendar);
        }

        @Test
        void backfillOneMinuteCandles_shouldNormalizeDeduplicateAndSortBatchSymbols() {
                LocalDate from = LocalDate.of(2026, 5, 20);
                LocalDate to = LocalDate.of(2026, 6, 10);

                List<LocalDate> tradingDays = businessDays(from, to);

                when(stockPriceRepository
                                .findBySymbolAndDateBetweenOrderByDateAsc(
                                                anyString(),
                                                eq(from),
                                                eq(to)))
                                .thenReturn(stockPrices(tradingDays));

                when(angelOneMarketDataProvider
                                .fetchHistoricalOneMinuteCandles(
                                                anyString(),
                                                any(LocalDate.class),
                                                any(LocalDate.class)))
                                .thenAnswer(invocation -> {
                                        String symbol = invocation.getArgument(
                                                        0,
                                                        String.class);

                                        LocalDate chunkFrom = invocation.getArgument(
                                                        1,
                                                        LocalDate.class);

                                        LocalDate chunkTo = invocation.getArgument(
                                                        2,
                                                        LocalDate.class);

                                        return completeCandlesForRange(
                                                        symbol,
                                                        chunkFrom,
                                                        chunkTo);
                                });

                stubPersistence();

                List<?> results = service.backfillOneMinuteCandles(
                                List.of(
                                                "ongc",
                                                "ITC",
                                                "ongc"),
                                from,
                                to);

                assertEquals(2, results.size());

                ArgumentCaptor<String> symbolCaptor =
                                ArgumentCaptor.forClass(String.class);

                verify(angelOneMarketDataProvider, times(8))
                                .fetchHistoricalOneMinuteCandles(
                                                symbolCaptor.capture(),
                                                any(LocalDate.class),
                                                any(LocalDate.class));

                assertEquals(
                                List.of(
                                                "ITC",
                                                "ITC",
                                                "ITC",
                                                "ITC",
                                                "ONGC",
                                                "ONGC",
                                                "ONGC",
                                                "ONGC"),
                                symbolCaptor.getAllValues());
        }

        @Test
        void backfillOneMinuteCandles_shouldRejectIncompleteHistoricalDay() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                when(stockPriceRepository
                                .findBySymbolAndDateBetweenOrderByDateAsc(
                                                "ONGC",
                                                date,
                                                date))
                                .thenReturn(
                                                stockPrices(List.of(date)));

                when(angelOneMarketDataProvider
                                .fetchHistoricalOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                date))
                                .thenReturn(
                                                incompleteCandles(
                                                                "ONGC",
                                                                date,
                                                                361));

                ProviderException exception = assertThrows(
                                ProviderException.class,
                                () -> service.backfillOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                date));

                assertTrue(
                                exception.getMessage()
                                                .contains(
                                                                "Incomplete historical candle response"));

                verify(marketCandleRepository, never())
                                .save(any(MarketCandle.class));

                verify(liveMinuteResolutionRepository, never())
                                .save(any(LiveMinuteResolution.class));
        }

        @Test
        void backfillOneMinuteCandles_shouldSkipAlreadyCompleteDays() {
                LocalDate date = LocalDate.of(2026, 5, 20);

                when(stockPriceRepository
                                .findBySymbolAndDateBetweenOrderByDateAsc(
                                                "ONGC",
                                                date,
                                                date))
                                .thenReturn(
                                                stockPrices(List.of(date)));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                eq("ONGC"),
                                                eq("NSE"),
                                                eq(CandleTimeframe.ONE_MINUTE),
                                                any(LocalDateTime.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(
                                                completeOneMinuteDay(
                                                                "ONGC",
                                                                date));

                List<?> result = List.of(
                                service.backfillOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                date));

                assertEquals(1, result.size());

                verify(angelOneMarketDataProvider, never())
                                .fetchHistoricalOneMinuteCandles(
                                                anyString(),
                                                any(LocalDate.class),
                                                any(LocalDate.class));
        }

        @Test
        void rangeBackfill_shouldNotAssumeNoTradeWhenProviderReturnsNothing() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atTime(11, 17);
                LocalDateTime to = date.atTime(11, 19);

                when(angelOneMarketDataProvider
                                .fetchHistoricalOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                date))
                                .thenReturn(List.of());

                ProviderException exception = assertThrows(
                                ProviderException.class,
                                () -> service.backfillOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                from,
                                                to));

                assertTrue(
                                exception.getMessage()
                                                .contains(
                                                                "no-trade cannot be inferred"));

                verify(marketCandleRepository, never())
                                .save(any(MarketCandle.class));

                verify(liveMinuteResolutionRepository, never())
                                .save(any(LiveMinuteResolution.class));
        }

        @Test
        void rangeBackfill_shouldPersistReturnedCandlesAsRepaired() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atTime(11, 17);
                LocalDateTime to = date.atTime(11, 19);

                MarketCandle returned = oneMinuteCandle(
                                "ONGC",
                                from,
                                250L);

                when(angelOneMarketDataProvider
                                .fetchHistoricalOneMinuteCandles(
                                                "ONGC",
                                                date,
                                                date))
                                .thenReturn(List.of(returned));

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                from))
                                .thenReturn(Optional.empty());

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                "ONGC",
                                                "NSE",
                                                from))
                                .thenReturn(Optional.empty());

                when(timeProvider.nowDateTime())
                                .thenReturn(date.atTime(11, 20));

                stubTransactionOnly();
                stubPersistenceSaves();

                service.backfillOneMinuteCandles(
                                "ONGC",
                                date,
                                from,
                                to);

                verify(marketCandleRepository)
                                .save(argThat(candle -> "ONGC".equals(
                                                candle.getSymbol())
                                                && from.equals(
                                                                candle.getCandleTime())
                                                && candle.getQualityStatus()
                                                                == CandleQualityStatus.REPAIRED
                                                && candle.getProcessingStatus()
                                                                == CandleProcessingStatus.PROVISIONAL));

                verify(liveMinuteResolutionRepository)
                                .save(argThat(resolution -> resolution.getStatus()
                                                == MinuteResolutionStatus.REPAIRED
                                                && from.equals(
                                                                resolution.getMinuteTime())));
        }

        @Test
        void materializeFifteenMinuteCandles_shouldCreateDerivedBuckets() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                LocalDateTime from = date.atStartOfDay();
                LocalDateTime to = date.atTime(23, 59);

                List<MarketCandle> sourceCandles = new ArrayList<>();

                for (int i = 0; i < 30; i++) {
                        LocalDateTime time = date.atTime(9, 15)
                                        .plusMinutes(i);

                        sourceCandles.add(
                                        oneMinuteCandle(
                                                        "ONGC",
                                                        time,
                                                        100L));
                }

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                from,
                                                to))
                                .thenReturn(sourceCandles);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                eq("ONGC"),
                                                eq("NSE"),
                                                eq(CandleTimeframe.FIFTEEN_MINUTE),
                                                any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());

                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.materializeFifteenMinuteCandles(
                                "ONGC",
                                date,
                                date);

                ArgumentCaptor<MarketCandle> captor =
                                ArgumentCaptor.forClass(MarketCandle.class);

                verify(marketCandleRepository, times(2))
                                .save(captor.capture());

                List<MarketCandle> saved = captor.getAllValues();

                assertEquals(2, saved.size());

                assertTrue(
                                saved.stream()
                                                .allMatch(candle -> candle
                                                                .getTimeframe()
                                                                == CandleTimeframe.FIFTEEN_MINUTE));

                assertTrue(
                                saved.stream()
                                                .allMatch(candle -> candle
                                                                .getProcessingStatus()
                                                                == CandleProcessingStatus.RELEASED));

                assertTrue(
                                saved.stream()
                                                .allMatch(candle -> candle
                                                                .getVolume() == 1500L));
        }

        @Test
        void materializeFifteenMinuteCandles_shouldReturnEmptyForNoSourceCandles() {
                LocalDate date = LocalDate.of(2026, 8, 17);

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTimeBetweenOrderByCandleTimeAsc(
                                                "ONGC",
                                                "NSE",
                                                CandleTimeframe.ONE_MINUTE,
                                                date.atStartOfDay(),
                                                date.atTime(23, 59)))
                                .thenReturn(List.of());

                Object result = service.materializeFifteenMinuteCandles(
                                "ONGC",
                                date,
                                date);

                assertTrue(result != null);

                verify(marketCandleRepository, never())
                                .save(any(MarketCandle.class));
        }

        @Test
        void backfillOneMinuteCandles_shouldRejectInvalidDateRange() {
                LocalDate from = LocalDate.of(2026, 8, 20);
                LocalDate to = LocalDate.of(2026, 8, 19);

                assertThrows(
                                ProviderException.class,
                                () -> service.backfillOneMinuteCandles(
                                                "ONGC",
                                                from,
                                                to));

                verifyNoInteractions(
                                angelOneMarketDataProvider,
                                marketCandleRepository,
                                stockPriceRepository);
        }

        @Test
        void backfillOneMinuteCandles_shouldReturnEmptyForEmptySymbolBatch() {
                List<?> result = service.backfillOneMinuteCandles(
                                List.of(),
                                LocalDate.of(2026, 8, 20),
                                LocalDate.of(2026, 8, 20));

                assertTrue(result.isEmpty());

                verifyNoInteractions(
                                angelOneMarketDataProvider,
                                marketCandleRepository,
                                stockPriceRepository);
        }

        private List<MarketCandle> completeOneMinuteDay(
                        String symbol,
                        LocalDate date) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int minute = 0; minute < 375; minute++) {
                        candles.add(
                                        oneMinuteCandle(
                                                        symbol,
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(minute),
                                                        100L));
                }

                return candles;
        }

        private List<MarketCandle> completeCandlesForRange(
                        String symbol,
                        LocalDate from,
                        LocalDate to) {

                List<MarketCandle> candles = new ArrayList<>();

                for (LocalDate date = from;
                                !date.isAfter(to);
                                date = date.plusDays(1)) {

                        if (date.getDayOfWeek().getValue() > 5) {
                                continue;
                        }

                        for (int minute = 0; minute < 375; minute++) {
                                candles.add(
                                                oneMinuteCandle(
                                                                symbol,
                                                                date.atTime(9, 15)
                                                                                .plusMinutes(minute),
                                                                100L));
                        }
                }

                return candles;
        }

        private List<MarketCandle> incompleteCandles(
                        String symbol,
                        LocalDate date,
                        int count) {

                List<MarketCandle> candles = new ArrayList<>();

                for (int minute = 0; minute < count; minute++) {
                        candles.add(
                                        oneMinuteCandle(
                                                        symbol,
                                                        date.atTime(9, 15)
                                                                        .plusMinutes(minute),
                                                        100L));
                }

                return candles;
        }

        private void stubPersistence() {
                stubTransactionOnly();
                stubPersistenceSaves();

                when(marketCandleRepository
                                .findBySymbolAndExchangeAndTimeframeAndCandleTime(
                                                anyString(),
                                                anyString(),
                                                any(CandleTimeframe.class),
                                                any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());

                when(liveMinuteResolutionRepository
                                .findBySymbolAndExchangeAndMinuteTime(
                                                anyString(),
                                                anyString(),
                                                any(LocalDateTime.class)))
                                .thenReturn(Optional.empty());
        }

        private void stubPersistenceSaves() {
                when(marketCandleRepository.save(
                                any(MarketCandle.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                when(liveMinuteResolutionRepository.save(
                                any(LiveMinuteResolution.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private void stubTransactionOnly() {
                TransactionStatus transactionStatus =
                                mock(TransactionStatus.class);

                when(transactionManager.getTransaction(
                                any(TransactionDefinition.class)))
                                .thenReturn(transactionStatus);
        }

        private List<StockPrice> stockPrices(
                        List<LocalDate> dates) {

                return dates.stream()
                                .map(date -> StockPrice.builder()
                                                .symbol("ONGC")
                                                .date(date)
                                                .volume(1000)
                                                .build())
                                .toList();
        }

        private List<LocalDate> businessDays(
                        LocalDate from,
                        LocalDate to) {

                List<LocalDate> dates = new ArrayList<>();

                for (LocalDate date = from;
                                !date.isAfter(to);
                                date = date.plusDays(1)) {

                        if (date.getDayOfWeek().getValue() < 6) {
                                dates.add(date);
                        }
                }

                return dates;
        }

        private MarketCandle oneMinuteCandle(
                        String symbol,
                        LocalDateTime time,
                        long volume) {

                return MarketCandle.builder()
                                .symbol(symbol)
                                .exchange("NSE")
                                .timeframe(CandleTimeframe.ONE_MINUTE)
                                .candleTime(time)
                                .openPrice(100.0)
                                .highPrice(101.0)
                                .lowPrice(99.0)
                                .closePrice(100.5)
                                .volume(volume)
                                .source("TEST")
                                .isFinalized(true)
                                .qualityStatus(
                                                CandleQualityStatus.LIVE)
                                .processingStatus(
                                                CandleProcessingStatus.RELEASED)
                                .build();
        }
}



