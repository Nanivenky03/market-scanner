package com.trading.scanner.service.data;

import com.trading.scanner.config.TimeProvider;
import com.trading.scanner.model.DataStatus;
import com.trading.scanner.model.EodDataEntry;
import com.trading.scanner.model.EodDataStatus;
import com.trading.scanner.repository.EodDataEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EodDataEntryService {

        private static final List<EodDataStatus> SUCCESS_STATUSES = List.of(
                        EodDataStatus.COMPLETE,
                        EodDataStatus.REPAIRED);

        private final EodDataEntryRepository repository;
        private final TimeProvider timeProvider;

        @Value("${runtime.data.pipeline-version:V1.3.x}")
        private String pipelineVersion = "V1.3.x";

        @Transactional
        public EodDataEntry begin(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        String source) {

                LocalDateTime now = timeProvider.nowDateTime();

                EodDataEntry entry = repository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> EodDataEntry.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .build());

                entry.setStatus(EodDataStatus.IN_PROGRESS);
                entry.setSource(source);
                entry.setPipelineVersion(pipelineVersion);
                entry.setStartedAt(now);
                entry.setCompletedAt(null);
                entry.setLastError(null);
                entry.setUpdatedAt(now);

                return repository.save(entry);
        }

        @Transactional
        public EodDataEntry complete(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        DataStatus dataStatus,
                        int expected,
                        int actual,
                        int noTrade,
                        int unresolved,
                        int repaired,
                        int reconciled,
                        String source,
                        String message) {

                LocalDateTime now = timeProvider.nowDateTime();

                EodDataEntry entry = repository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> EodDataEntry.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .startedAt(now)
                                                .build());

                EodDataStatus status = dataStatus == DataStatus.REPAIRED
                                ? EodDataStatus.REPAIRED
                                : dataStatus == DataStatus.RECONCILED
                                                ? EodDataStatus.COMPLETE
                                                : EodDataStatus.PARTIAL;

                entry.setStatus(status);
                entry.setExpectedMinuteCount(expected);
                entry.setActualMinuteCount(actual);
                entry.setNoTradeMinuteCount(noTrade);
                entry.setUnresolvedMinuteCount(unresolved);
                entry.setRepairedMinuteCount(repaired);
                entry.setReconciledMinuteCount(reconciled);
                entry.setSource(source);
                entry.setPipelineVersion(pipelineVersion);
                entry.setCompletedAt(now);
                entry.setLastError(
                                status == EodDataStatus.PARTIAL
                                                ? message
                                                : null);
                entry.setUpdatedAt(now);

                return repository.save(entry);
        }

        @Transactional
        public EodDataEntry markFailed(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate,
                        String source,
                        String error) {

                LocalDateTime now = timeProvider.nowDateTime();

                EodDataEntry entry = repository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .orElseGet(() -> EodDataEntry.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(tradingDate)
                                                .startedAt(now)
                                                .build());

                entry.setStatus(EodDataStatus.FAILED);
                entry.setSource(source);
                entry.setPipelineVersion(pipelineVersion);
                entry.setLastError(error);
                entry.setUpdatedAt(now);

                return repository.save(entry);
        }

        @Transactional(readOnly = true)
        public Optional<LocalDate> latestSuccessfulDate(
                        String symbol,
                        String exchange) {

                return repository
                                .findBySymbolAndExchangeOrderByTradingDateDesc(
                                                symbol,
                                                exchange)
                                .stream()
                                .filter(entry -> SUCCESS_STATUSES.contains(entry.getStatus()))
                                .map(EodDataEntry::getTradingDate)
                                .findFirst();
        }

        @Transactional(readOnly = true)
        public boolean isSuccessful(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate) {

                return repository
                                .findBySymbolAndExchangeAndTradingDate(
                                                symbol,
                                                exchange,
                                                tradingDate)
                                .map(entry -> SUCCESS_STATUSES.contains(entry.getStatus()))
                                .orElse(false);
        }

        @Transactional
        public int ensureInitialBaselineIfEmpty(List<com.trading.scanner.model.StockUniverse> activeUniverse,
                        LocalDate previousTradingDay) {
                if (activeUniverse == null || activeUniverse.isEmpty() || previousTradingDay == null) {
                        return 0;
                }

                LocalDateTime now = timeProvider.nowDateTime();
                int seeded = 0;

                for (com.trading.scanner.model.StockUniverse stock : activeUniverse) {
                        String symbol = stock.getSymbol();
                        String exchange = stock.getExchange() != null ? stock.getExchange().name() : "NSE";

                        if (repository.findBySymbolAndExchangeAndTradingDate(symbol, exchange, previousTradingDay)
                                        .isEmpty()) {
                                EodDataEntry entry = EodDataEntry.builder()
                                                .symbol(symbol)
                                                .exchange(exchange)
                                                .tradingDate(previousTradingDay)
                                                .status(EodDataStatus.COMPLETE)
                                                .expectedMinuteCount(375)
                                                .actualMinuteCount(375)
                                                .noTradeMinuteCount(0)
                                                .unresolvedMinuteCount(0)
                                                .repairedMinuteCount(0)
                                                .reconciledMinuteCount(375)
                                                .source("INITIAL_BASELINE")
                                                .pipelineVersion(pipelineVersion)
                                                .startedAt(now)
                                                .completedAt(now)
                                                .updatedAt(now)
                                                .build();
                                repository.save(entry);
                                seeded++;
                        }
                }

                return seeded;
        }
}
