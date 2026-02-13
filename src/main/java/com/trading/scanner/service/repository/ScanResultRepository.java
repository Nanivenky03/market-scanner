package com.trading.scanner.repository;

import com.trading.scanner.model.ScanResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ScanResultRepository extends JpaRepository<ScanResult, Long> {
    
    List<ScanResult> findTop10ByOrderByScanDateDesc();
    
    List<ScanResult> findByScanDate(LocalDate scanDate);
}
