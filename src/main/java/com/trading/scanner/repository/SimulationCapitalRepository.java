package com.trading.scanner.repository;

import com.trading.scanner.model.SimulationCapital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SimulationCapitalRepository extends JpaRepository<SimulationCapital, Integer> {
    Optional<SimulationCapital> findByTradingDate(String tradingDate);
    Optional<SimulationCapital> findTopByOrderByTradingDateDesc();
    Optional<SimulationCapital> findTopByTradingDateLessThanOrderByTradingDateDesc(String tradingDate);

    @Modifying
    @Query("DELETE FROM SimulationCapital c WHERE c.tradingDate = :tradingDate")
    int deleteByTradingDate(@Param("tradingDate") String tradingDate);
}
