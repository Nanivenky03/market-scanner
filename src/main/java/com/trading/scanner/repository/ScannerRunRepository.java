package com.trading.scanner.repository;

import com.trading.scanner.model.ScannerRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ScannerRunRepository extends JpaRepository<ScannerRun, Long> {
    
    List<ScannerRun> findTop10ByOrderByRunDateDesc();
}
