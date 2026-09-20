package com.trading.scanner.repository;

import com.trading.scanner.model.LiveFeedState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

        List<LiveFeedState> findByTradingDateAndSubscriptionActiveTrue(
                        LocalDate tradingDate);

        /**
         * Atomically creates the state row if it does not exist.
         * Existing rows are left unchanged.
         */
        @Modifying
        @Query(value = """
                        INSERT INTO live_feed_state (
                            symbol,
                            exchange,
                            trading_date,
                            blocked,
                            health_status,
                            subscription_active,
                            consecutive_recovery_ticks,
                            updated_at
                        )
                        VALUES (
                            :symbol,
                            :exchange,
                            :tradingDate,
                            false,
                            'HEALTHY',
                            false,
                            0,
                            :updatedAt
                        )
                        ON CONFLICT (
                            symbol,
                            exchange,
                            trading_date
                        )
                        DO NOTHING
                        """, nativeQuery = true)
        int ensureExists(
                        @Param("symbol") String symbol,
                        @Param("exchange") String exchange,
                        @Param("tradingDate") String tradingDate,
                        @Param("updatedAt") String updatedAt);
}
