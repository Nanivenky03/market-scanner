package com.trading.scanner.service.data;

import com.trading.scanner.calendar.TradingCalendar;
import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.BackfillJob;
import com.trading.scanner.model.BackfillJobStatus;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.repository.BackfillJobRepository;
import com.trading.scanner.service.engine.DailyDataStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BackfillQueueService {

        private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
        private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 29);

        private final BackfillJobRepository backfillJobRepository;
        private final IntradayCandleBackfillService backfillService;
        private final DailyDataStatusService dailyDataStatusService;
        private final LiveMarketCandleService liveMarketCandleService;
        private final TradingCalendar tradingCalendar;
        private final TimeProvider timeProvider;

        @Value("${runtime.backfill.max-attempts:3}")
        private int maxAttempts = 3;

        @Value("${runtime.backfill.retry-delay-seconds:3}")
        private long retryDelaySeconds = 3L;

        @Value("${runtime.backfill.lease-minutes:30}")
        private long leaseMinutes = 30L;

        @Value("${runtime.backfill.worker.enabled:true}")
        private boolean workerEnabled = true;

        @Value("${runtime.backfill.worker.pace-ms:400}")
        private long workerPaceMs = 400L;

        private final Object queueSignal = new Object();
        private volatile boolean running = true;
        private Thread workerThread;

        @PostConstruct
        public void startWorker() {
                if (!workerEnabled) {
                        return;
                }
                running = true;
                workerThread = new Thread(this::runWorkerLoop, "backfill-queue-worker");
                workerThread.setDaemon(true);
                workerThread.start();
                log.info("Backfill queue worker thread started");
        }

        @PreDestroy
        public void stopWorker() {
                running = false;
                if (workerThread != null) {
                        workerThread.interrupt();
                }
        }

        public void notifyWorker() {
                synchronized (queueSignal) {
                        queueSignal.notifyAll();
                }
        }

        private void runWorkerLoop() {
                while (running) {
                        try {
                                ProcessResult result = processNext();
                                if (result.processed()) {
                                        log.info("Backfill queue worker processed: {}", result);
                                        try {
                                                Thread.sleep(workerPaceMs);
                                        } catch (InterruptedException ie) {
                                                if (!running)
                                                        break;
                                        }
                                } else {
                                        synchronized (queueSignal) {
                                                queueSignal.wait(2000L);
                                        }
                                }
                        } catch (InterruptedException ie) {
                                if (!running)
                                        break;
                        } catch (Exception ex) {
                                log.warn("Error in backfill queue worker loop: {}", ex.getMessage(), ex);
                                try {
                                        synchronized (queueSignal) {
                                                queueSignal.wait(3000L);
                                        }
                                } catch (InterruptedException ie) {
                                        if (!running)
                                                break;
                                }
                        }
                }
        }

        @EventListener
        @Transactional
        public void onIntradayGapDetected(IntradayGapDetectedEvent event) {
                dailyDataStatusService.markPartial(
                                event.symbol(),
                                event.exchange(),
                                event.tradingDate(),
                                "Intraday gap detected; SmartAPI repair queued");

                enqueueIntradayGap(
                                event.symbol(),
                                event.exchange(),
                                event.tradingDate(),
                                event.fromTime(),
                                event.toTime());
        }

        @Transactional
        public int enqueueIntradayGap(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        LocalDateTime fromTime,
                        LocalDateTime toTime) {

                if (symbol == null
                                || exchange == null
                                || tradingDate == null
                                || fromTime == null
                                || toTime == null) {
                        return 0;
                }

                LocalDateTime now = timeProvider != null && timeProvider.nowDateTime() != null
                                ? timeProvider.nowDateTime()
                                : LocalDateTime.now();

                LocalDate today = resolveToday(now);
                if (tradingDate.isAfter(today) || tradingDate.getYear() < 2020 || tradingDate.getYear() > 2050) {
                        log.warn("Rejected backfill enqueue with invalid tradingDate: {} for symbol={}", tradingDate,
                                        symbol);
                        return 0;
                }

                if (fromTime.toLocalDate().isAfter(today) || fromTime.getYear() < 2020 || fromTime.getYear() > 2050) {
                        log.warn("Rejected backfill enqueue with invalid fromTime: {} for symbol={}", fromTime, symbol);
                        return 0;
                }

                LocalDateTime marketOpen = tradingDate.atTime(MARKET_OPEN);
                LocalDateTime marketClose = tradingDate.atTime(MARKET_CLOSE);

                LocalDateTime clampedFrom = fromTime.isBefore(marketOpen) ? marketOpen : fromTime;
                LocalDateTime clampedTo = toTime.isAfter(marketClose) ? marketClose : toTime;

                if (clampedFrom.isAfter(clampedTo)) {
                        return 0;
                }

                BackfillJob job = backfillJobRepository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> BackfillJob.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .priority(0)
                                                .status(BackfillJobStatus.PENDING)
                                                .attempts(0)
                                                .createdAt(now)
                                                .build());

                boolean newRecoveryCycle = job.getStatus() == BackfillJobStatus.DONE
                                || job.getStatus() == BackfillJobStatus.DEAD_LETTER;

                LocalDateTime mergedFrom = min(job.getFromTime(), fromTime);
                LocalDateTime mergedTo = max(job.getToTime(), toTime);

                boolean rangeChanged = !fromEquals(job.getFromTime(), mergedFrom)
                                || !fromEquals(job.getToTime(), mergedTo);

                if (newRecoveryCycle) {
                        job.setAttempts(0);
                }

                job.setFromTime(mergedFrom);
                job.setToTime(mergedTo);
                job.setPriority(0);
                job.setStatus(BackfillJobStatus.PENDING);
                job.setNextAttemptAt(now);
                job.setLeaseUntil(null);
                job.setLastError(null);
                job.setUpdatedAt(now);

                backfillJobRepository.save(job);

                log.info(
                                "Intraday repair range queued symbol={} exchange={} date={} from={} to={} merged={} retryCycleReset={}",
                                symbol,
                                exchange,
                                tradingDate,
                                mergedFrom,
                                mergedTo,
                                rangeChanged,
                                newRecoveryCycle);

                notifyWorker();

                return 1;
        }

        @Transactional
        public int recoverStaleJobs() {
                LocalDateTime now = timeProvider.nowDateTime();

                List<BackfillJob> staleJobs = backfillJobRepository.findByStatusAndLastAttemptAtBefore(
                                BackfillJobStatus.IN_PROGRESS,
                                now.minusMinutes(leaseMinutes));

                for (BackfillJob job : staleJobs) {
                        job.setStatus(BackfillJobStatus.PENDING);
                        job.setLeaseUntil(null);
                        job.setNextAttemptAt(now);
                        job.setLastError("Recovered stale in-progress job after restart or timeout");
                        job.setUpdatedAt(now);
                        backfillJobRepository.save(job);
                }

                if (!staleJobs.isEmpty()) {
                        log.warn("Recovered stale backfill jobs count={}", staleJobs.size());
                }

                return staleJobs.size();
        }

        @Transactional
        public ProcessResult processNext() {
                LocalDateTime now = timeProvider.nowDateTime();

                recoverStaleJobs();

                Optional<BackfillJob> jobOpt = backfillJobRepository
                                .findFirstByStatusAndNextAttemptAtLessThanEqualOrderByPriorityAscNextAttemptAtAscIdAsc(
                                                BackfillJobStatus.PENDING,
                                                now);

                if (jobOpt.isEmpty()) {
                        return new ProcessResult(
                                        false,
                                        null,
                                        "No pending backfill job");
                }

                BackfillJob job = jobOpt.get();

                LocalDate today = resolveToday(now);
                if (job.getTradingDate() == null
                                || job.getTradingDate().isAfter(today)
                                || job.getTradingDate().getYear() < 2020
                                || job.getTradingDate().getYear() > 2050) {
                        log.warn("Quarantining corrupt backfill job with invalid tradingDate: id={} date={}",
                                        job.getId(), job.getTradingDate());
                        job.setStatus(BackfillJobStatus.DEAD_LETTER);
                        job.setLastError("Corrupt/future tradingDate rejected by safety guard");
                        job.setUpdatedAt(now);
                        backfillJobRepository.save(job);
                        return new ProcessResult(true, job.getId(), "DEAD_LETTER: Invalid tradingDate");
                }

                LocalDateTime fromTime = job.getFromTime() != null
                                ? job.getFromTime()
                                : job.getTradingDate().atTime(MARKET_OPEN);

                LocalDateTime toTime = job.getToTime() != null
                                ? job.getToTime()
                                : job.getTradingDate().atTime(MARKET_CLOSE);

                job.setStatus(BackfillJobStatus.IN_PROGRESS);
                job.setAttempts(job.getAttempts() + 1);
                job.setLastAttemptAt(now);
                job.setLeaseUntil(now.plusMinutes(leaseMinutes));
                job.setUpdatedAt(now);
                backfillJobRepository.save(job);

                try {
                        backfillService.backfillOneMinuteCandles(
                                        job.getSymbol(),
                                        job.getTradingDate(),
                                        fromTime,
                                        toTime);

                        liveMarketCandleService.confirmNoTradeForUnresolvedRange(
                                        job.getSymbol(),
                                        job.getExchange(),
                                        fromTime,
                                        toTime);

                        boolean released = liveMarketCandleService.releaseRepairedRange(
                                        job.getSymbol(),
                                        job.getExchange(),
                                        fromTime,
                                        toTime);

                        if (!released) {
                                return retry(
                                                job,
                                                "Repaired range is still incomplete or unresolved");
                        }

                        boolean fullDay = fromTime.equals(
                                        job.getTradingDate().atTime(MARKET_OPEN))
                                        && toTime.equals(
                                                        job.getTradingDate().atTime(MARKET_CLOSE));

                        if (fullDay) {
                                DailyDataStatusService.CompletenessResult result = dailyDataStatusService
                                                .checkCompleteness(
                                                                job.getSymbol(),
                                                                job.getExchange(),
                                                                job.getTradingDate());

                                if (!result.complete()) {
                                        return retry(
                                                        job,
                                                        "Full-day backfill incomplete: "
                                                                        + result.message());
                                }

                                dailyDataStatusService.markStatus(
                                                job.getSymbol(),
                                                job.getExchange(),
                                                job.getTradingDate(),
                                                DataStatus.REPAIRED,
                                                result.expectedCandleCount(),
                                                result.actualCandleCount(),
                                                result.missingCandleCount(),
                                                "SmartAPI backfill completed");
                        }

                        job.setStatus(BackfillJobStatus.DONE);
                        job.setLeaseUntil(null);
                        job.setLastError(null);
                        job.setUpdatedAt(timeProvider.nowDateTime());
                        backfillJobRepository.save(job);

                        return new ProcessResult(
                                        true,
                                        job.getId(),
                                        "Backfill job completed");

                } catch (Exception ex) {
                        return retry(job, ex.getMessage());
                }
        }

        public void scheduledProcess() {
                try {
                        int processedCount = 0;
                        int maxBatch = 150;
                        while (processedCount < maxBatch) {
                                ProcessResult result = processNext();
                                if (!result.processed()) {
                                        break;
                                }

                                processedCount++;
                                log.info("Backfill job processed: {}", result);

                                try {
                                        Thread.sleep(workerPaceMs);
                                } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        break;
                                }
                        }
                } catch (Exception ex) {
                        log.warn(
                                        "Backfill queue processing failed: {}",
                                        ex.getMessage(),
                                        ex);
                }
        }

        private ProcessResult retry(
                        BackfillJob job,
                        String error) {

                LocalDateTime now = timeProvider.nowDateTime();

                String message = error == null
                                ? "Unknown backfill failure"
                                : error;

                job.setLastError(message);
                job.setLeaseUntil(null);
                job.setUpdatedAt(now);

                if (job.getAttempts() >= maxAttempts) {
                        job.setStatus(BackfillJobStatus.DEAD_LETTER);

                        dailyDataStatusService.markStatus(
                                        job.getSymbol(),
                                        job.getExchange(),
                                        job.getTradingDate(),
                                        DataStatus.FAILED,
                                        null,
                                        null,
                                        null,
                                        message);
                } else {
                        job.setStatus(BackfillJobStatus.PENDING);
                        job.setNextAttemptAt(
                                        now.plusSeconds(retryDelaySeconds));
                }

                backfillJobRepository.save(job);

                return new ProcessResult(
                                true,
                                job.getId(),
                                job.getStatus() + ": " + message);
        }

        private LocalDateTime min(
                        LocalDateTime left,
                        LocalDateTime right) {
                return left == null || right.isBefore(left)
                                ? right
                                : left;
        }

        private LocalDateTime max(
                        LocalDateTime left,
                        LocalDateTime right) {
                return left == null || right.isAfter(left)
                                ? right
                                : left;
        }

        private boolean fromEquals(
                        LocalDateTime left,
                        LocalDateTime right) {
                return left == null
                                ? right == null
                                : left.equals(right);
        }

        private LocalDate resolveToday(LocalDateTime fallbackNow) {
                if (timeProvider != null) {
                        try {
                                LocalDate d = timeProvider.today();
                                if (d != null) {
                                        return d;
                                }
                        } catch (Exception ignored) {
                        }
                }
                return fallbackNow != null ? fallbackNow.toLocalDate() : LocalDate.now();
        }

        public record ProcessResult(
                        boolean processed,
                        Integer jobId,
                        String message) {
        }
}
