package com.trading.scanner.repository;

import com.trading.scanner.model.LiveFeedState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface LiveFeedStateRepository
                extends JpaRepository<LiveFeedState, Integer> {

        Optional<LiveFeedState> findBySymbolAndExchangeAndTradingDate(
                        String symbol,
                        String exchange,
                        LocalDate tradingDate);

        List<LiveFeedState> findByTradingDateAndSubscriptionActiveTrue(LocalDate tradingDate);
}
