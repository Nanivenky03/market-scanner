package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.BackfillJob;
import com.trading.scanner.model.BackfillJobStatus;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.repository.BackfillJobRepository;
import com.trading.scanner.repository.StockUniverseRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackfillQueueServiceTest {

        private BackfillJobRepository jobRepository;
        private IntradayCandleBackfillService backfillService;
        private DailyDataStatusService dataStatusService;
        private LiveMarketCandleService liveMarketCandleService;
        private TradingCalendar tradingCalendar;
        private TimeProvider timeProvider;
        private BackfillQueueService service;

        @BeforeEach
        void setUp() {
                jobRepository = mock(BackfillJobRepository.class);
                backfillService = mock(IntradayCandleBackfillService.class);
                dataStatusService = mock(DailyDataStatusService.class);
                liveMarketCandleService = mock(LiveMarketCandleService.class);
                tradingCalendar = mock(TradingCalendar.class);
                timeProvider = mock(TimeProvider.class);

                service = new BackfillQueueService(
                                jobRepository,
                                backfillService,
                                dataStatusService,
                                liveMarketCandleService,
                                tradingCalendar,
                                timeProvider);
        }

        @Test
        void processNext_shouldCompleteFullDayJob() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime now = date.plusDays(3).atTime(16, 0);
                LocalDateTime from = date.atTime(9, 15);
                LocalDateTime to = date.atTime(15, 29);

                BackfillJob job = job(1, "ONGC", date, 1, now, from, to);
                stubJobLookup(job, now);

                when(backfillService.backfillOneMinuteCandles(
                                "ONGC", date, from, to))
                                .thenReturn(new OneMinuteBackfillResult(
                                                "ONGC", date, date,
                                                375, 375, 0, "completed"));

                when(liveMarketCandleService.releaseRepairedRange(
                                "ONGC", "NSE", from, to))
                                .thenReturn(true);

                when(dataStatusService.checkCompleteness(
                                "ONGC", "NSE", date))
                                .thenReturn(new DailyDataStatusService.CompletenessResult(
                                                "ONGC", "NSE", date,
                                                375, 375, 0,
                                                false, false, true,
                                                DataStatus.LIVE, "complete"));

                BackfillQueueService.ProcessResult result = service.processNext();

                assertTrue(result.processed());
                assertEquals(BackfillJobStatus.DONE, job.getStatus());

                verify(backfillService)
                                .backfillOneMinuteCandles("ONGC", date, from, to);
                verify(liveMarketCandleService)
                                .releaseRepairedRange("ONGC", "NSE", from, to);
                verify(dataStatusService).markStatus(
                                "ONGC", "NSE", date,
                                DataStatus.REPAIRED,
                                375, 375, 0,
                                "SmartAPI backfill completed");
        }

        @Test
        void processNext_shouldCompleteTargetedRangeWhenReleaseSucceeds() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime now = date.plusDays(3).atTime(16, 0);
                LocalDateTime from = date.atTime(11, 17);
                LocalDateTime to = date.atTime(11, 19);

                BackfillJob job = job(2, "TCS", date, 3, now, from, to);
                stubJobLookup(job, now);

                when(backfillService.backfillOneMinuteCandles(
                                "TCS", date, from, to))
                                .thenReturn(new OneMinuteBackfillResult(
                                                "TCS", date, date,
                                                3, 2, 0, "completed"));

                when(liveMarketCandleService.releaseRepairedRange(
                                "TCS", "NSE", from, to))
                                .thenReturn(true);

                BackfillQueueService.ProcessResult result = service.processNext();

                assertTrue(result.processed());
                assertEquals(BackfillJobStatus.DONE, job.getStatus());

                verify(liveMarketCandleService)
                                .releaseRepairedRange("TCS", "NSE", from, to);
                verify(dataStatusService, never())
                                .checkCompleteness(anyString(), anyString(), any());
        }

        @Test
        void processNext_shouldRetryWhenReleaseBarrierFails() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime now = date.plusDays(3).atTime(16, 0);
                LocalDateTime from = date.atTime(11, 17);
                LocalDateTime to = date.atTime(11, 19);

                BackfillJob job = job(3, "RELIANCE", date, 1, now, from, to);
                stubJobLookup(job, now);

                when(backfillService.backfillOneMinuteCandles(
                                "RELIANCE", date, from, to))
                                .thenReturn(new OneMinuteBackfillResult(
                                                "RELIANCE", date, date,
                                                3, 3, 0, "completed"));

                when(liveMarketCandleService.releaseRepairedRange(
                                "RELIANCE", "NSE", from, to))
                                .thenReturn(false);

                BackfillQueueService.ProcessResult result = service.processNext();

                assertTrue(result.processed());
                assertEquals(BackfillJobStatus.PENDING, job.getStatus());

                verify(dataStatusService, never())
                                .checkCompleteness(anyString(), anyString(), any());
        }

        @Test
        void onIntradayGapDetected_shouldCreateRangeJob() {
                LocalDate date = LocalDate.of(2026, 8, 14);
                LocalDateTime now = date.atTime(11, 20);
                LocalDateTime from = date.atTime(11, 17);
                LocalDateTime to = date.atTime(11, 19);

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(jobRepository.findBySymbolAndExchangeAndTradingDate(
                                "ONGC", "NSE", date))
                                .thenReturn(Optional.empty());
                when(jobRepository.save(any(BackfillJob.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                service.onIntradayGapDetected(new IntradayGapDetectedEvent(
                                "ONGC", "NSE", date, from, to));

                verify(dataStatusService).markPartial(
                                "ONGC",
                                "NSE",
                                date,
                                "Intraday gap detected; SmartAPI repair queued");

                verify(jobRepository).save(argThat(job -> "ONGC".equals(job.getSymbol())
                                && "NSE".equals(job.getExchange())
                                && date.equals(job.getTradingDate())
                                && from.equals(job.getFromTime())
                                && to.equals(job.getToTime())
                                && job.getStatus() == BackfillJobStatus.PENDING));
        }

        @Test
        void processNext_shouldReturnNothingWhenQueueIsEmpty() {
                LocalDateTime now = LocalDateTime.of(2026, 8, 17, 16, 0);

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(jobRepository.findByStatusAndLastAttemptAtBefore(any(), any()))
                                .thenReturn(List.of());
                when(jobRepository
                                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByPriorityAscNextAttemptAtAscIdAsc(
                                                any(), any()))
                                .thenReturn(Optional.empty());

                BackfillQueueService.ProcessResult result = service.processNext();

                assertTrue(!result.processed());
                verifyNoInteractions(backfillService);
                verifyNoInteractions(liveMarketCandleService);
        }

        private void stubJobLookup(BackfillJob job, LocalDateTime now) {
                when(timeProvider.nowDateTime()).thenReturn(now);
                when(jobRepository.findByStatusAndLastAttemptAtBefore(
                                BackfillJobStatus.IN_PROGRESS,
                                now.minusMinutes(30)))
                                .thenReturn(List.of());
                when(jobRepository
                                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByPriorityAscNextAttemptAtAscIdAsc(
                                                BackfillJobStatus.PENDING, now))
                                .thenReturn(Optional.of(job));
                when(jobRepository.save(any(BackfillJob.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));
        }

        private BackfillJob job(
                        int id,
                        String symbol,
                        LocalDate date,
                        int priority,
                        LocalDateTime now,
                        LocalDateTime from,
                        LocalDateTime to) {

                return BackfillJob.builder()
                                .id(id)
                                .symbol(symbol)
                                .exchange("NSE")
                                .tradingDate(date)
                                .priority(priority)
                                .status(BackfillJobStatus.PENDING)
                                .attempts(0)
                                .fromTime(from)
                                .toTime(to)
                                .nextAttemptAt(now)
                                .build();
        }

        @Test
        void enqueueIntradayGap_shouldPreserveAndReactivateCompletedJob() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime now = date.atTime(12, 0);

                BackfillJob existing = BackfillJob.builder()
                                .id(10)
                                .symbol("ONGC")
                                .exchange("NSE")
                                .tradingDate(date)
                                .priority(4)
                                .status(BackfillJobStatus.DONE)
                                .attempts(3)
                                .fromTime(date.atTime(11, 0))
                                .toTime(date.atTime(11, 5))
                                .nextAttemptAt(date.atTime(11, 30))
                                .build();

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(jobRepository.findBySymbolAndExchangeAndTradingDate(
                                "ONGC", "NSE", date))
                                .thenReturn(Optional.of(existing));
                when(jobRepository.save(any(BackfillJob.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                int result = service.enqueueIntradayGap(
                                "ONGC",
                                "NSE",
                                date,
                                date.atTime(11, 3),
                                date.atTime(11, 10));

                assertEquals(1, result);
                assertEquals(BackfillJobStatus.PENDING, existing.getStatus());
                assertEquals(0, existing.getAttempts());
                assertEquals(date.atTime(11, 0), existing.getFromTime());
                assertEquals(date.atTime(11, 10), existing.getToTime());
                assertEquals(now, existing.getNextAttemptAt());
                assertEquals(null, existing.getLeaseUntil());
        }

        @Test
        void recoverStaleJobs_shouldReturnInProgressJobsToPending() {
                LocalDate date = LocalDate.of(2026, 8, 24);
                LocalDateTime now = date.atTime(12, 0);

                BackfillJob stale = BackfillJob.builder()
                                .id(11)
                                .symbol("TCS")
                                .exchange("NSE")
                                .tradingDate(date)
                                .status(BackfillJobStatus.IN_PROGRESS)
                                .attempts(1)
                                .lastAttemptAt(now.minusMinutes(45))
                                .leaseUntil(now.minusMinutes(15))
                                .nextAttemptAt(now)
                                .build();

                when(timeProvider.nowDateTime()).thenReturn(now);
                when(jobRepository.findByStatusAndLastAttemptAtBefore(
                                BackfillJobStatus.IN_PROGRESS,
                                now.minusMinutes(30)))
                                .thenReturn(List.of(stale));
                when(jobRepository.save(any(BackfillJob.class)))
                                .thenAnswer(invocation -> invocation.getArgument(0));

                int recovered = service.recoverStaleJobs();

                assertEquals(1, recovered);
                assertEquals(BackfillJobStatus.PENDING, stale.getStatus());
                assertEquals(null, stale.getLeaseUntil());
                assertEquals(now, stale.getNextAttemptAt());

                verify(jobRepository).save(stale);
        }

}
