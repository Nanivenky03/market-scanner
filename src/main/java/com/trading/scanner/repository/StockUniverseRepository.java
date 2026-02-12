package com.trading.scanner.repository;

import com.trading.scanner.model.StockUniverse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockUniverseRepository extends JpaRepository<StockUniverse, String> {
    
    List<StockUniverse> findByIsActiveTrue();
    
    List<StockUniverse> findByIndexNameAndIsActiveTrue(String indexName);
    
    long countByIsActiveTrue();
}
