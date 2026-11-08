package com.trading.scanner.repository;

import com.trading.scanner.model.StockSectorClassification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockSectorClassificationRepository extends JpaRepository<StockSectorClassification, Long> {

    Optional<StockSectorClassification> findBySymbol(String symbol);

    List<StockSectorClassification> findBySectorIndexSymbol(String sectorIndexSymbol);

    List<StockSectorClassification> findByIsActiveTrueOrderBySymbolAsc();

    boolean existsBySymbol(String symbol);
}
