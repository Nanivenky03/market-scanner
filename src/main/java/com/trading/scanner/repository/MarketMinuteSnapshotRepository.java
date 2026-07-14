package com.trading.scanner.repository;

import com.trading.scanner.model.MarketMinuteSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketMinuteSnapshotRepository extends JpaRepository<MarketMinuteSnapshot, Integer> {

    Optional<MarketMinuteSnapshot> findBySymbolAndExchangeAndMinuteTime(
            String symbol,
            String exchange,
            LocalDateTime minuteTime);

    List<MarketMinuteSnapshot> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}