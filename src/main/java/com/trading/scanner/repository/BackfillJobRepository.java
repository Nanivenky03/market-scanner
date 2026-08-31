package com.trading.scanner.repository;

import com.trading.scanner.model.BackfillJob;
import com.trading.scanner.model.BackfillJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BackfillJobRepository extends JpaRepository<BackfillJob, Integer> {

    boolean existsBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    Optional<BackfillJob> findBySymbolAndExchangeAndTradingDate(
            String symbol,
            String exchange,
            LocalDate tradingDate);

    Optional<BackfillJob> findFirstByStatusAndNextAttemptAtLessThanEqualOrderByPriorityAscNextAttemptAtAscIdAsc(
            BackfillJobStatus status,
            LocalDateTime now);

    List<BackfillJob> findByStatusAndLastAttemptAtBefore(
            BackfillJobStatus status,
            LocalDateTime cutoff);
}
