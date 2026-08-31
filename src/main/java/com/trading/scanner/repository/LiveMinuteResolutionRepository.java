package com.trading.scanner.repository;

import com.trading.scanner.model.LiveMinuteResolution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LiveMinuteResolutionRepository
        extends JpaRepository<LiveMinuteResolution, Integer> {

    Optional<LiveMinuteResolution> findBySymbolAndExchangeAndMinuteTime(
            String symbol,
            String exchange,
            LocalDateTime minuteTime);

    List<LiveMinuteResolution> findBySymbolAndExchangeAndMinuteTimeBetweenOrderByMinuteTimeAsc(
            String symbol,
            String exchange,
            LocalDateTime from,
            LocalDateTime to);
}
