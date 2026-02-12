package com.trading.scanner.repository;

import com.trading.scanner.model.ScannerRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScannerRunRepository extends JpaRepository<ScannerRun, Long> {
    
    Optional<ScannerRun> findTopByOrderByRunDateDesc();
    
    List<ScannerRun> findByRunDateOrderByCreatedAtDesc(LocalDate runDate);
    
    List<ScannerRun> findTop30ByOrderByRunDateDesc();
}
