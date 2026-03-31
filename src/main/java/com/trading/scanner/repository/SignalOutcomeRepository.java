package com.trading.scanner.repository;

import com.trading.scanner.model.SignalOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for SignalOutcome entities
 */
@Repository
public interface SignalOutcomeRepository extends JpaRepository<SignalOutcome, Integer> {
    
    /**
     * Check if outcome already exists for signal and horizon
     */
    boolean existsBySignalIdAndHorizonDays(Integer signalId, Integer horizonDays);
    
    /**
     * Find signals eligible for outcome computation
     * Signals where scan_date + horizon <= current simulation date
     */
    @Query("SELECT sr.id FROM ScanResult sr " +
           "WHERE sr.scanDate <= :cutoffDate " +
           "AND NOT EXISTS (" +
           "  SELECT 1 FROM SignalOutcome so " +
           "  WHERE so.signalId = sr.id AND so.horizonDays = :horizon" +
           ")")
    List<Integer> findEligibleSignalIds(@Param("horizon") Integer horizon, 
                                      @Param("cutoffDate") LocalDate cutoffDate);
    
    /**
     * Count outcomes by horizon
     */
    long countByHorizonDays(Integer horizonDays);
}
