package com.trading.scanner.repository;

import com.trading.scanner.model.Capital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CapitalRepository extends JpaRepository<Capital, Integer> {
    Optional<Capital> findByTradingDate(String tradingDate);
    Optional<Capital> findTopByOrderByTradingDateDesc();
    Optional<Capital> findTopByTradingDateLessThanOrderByTradingDateDesc(String tradingDate);

    @Modifying
    @Query("DELETE FROM Capital c WHERE c.tradingDate = :tradingDate")
    int deleteByTradingDate(@Param("tradingDate") String tradingDate);
}
