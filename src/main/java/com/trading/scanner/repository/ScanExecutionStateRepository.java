package com.trading.scanner.repository;

import com.trading.scanner.model.ScanExecutionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface ScanExecutionStateRepository extends JpaRepository<ScanExecutionState, Long> {
    
    Optional<ScanExecutionState> findByTradingDate(LocalDate tradingDate);
}
