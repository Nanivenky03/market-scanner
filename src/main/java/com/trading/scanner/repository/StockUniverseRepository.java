package com.trading.scanner.repository;

import com.trading.scanner.model.Exchange;
import com.trading.scanner.model.StockUniverse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockUniverseRepository extends JpaRepository<StockUniverse, Integer> {

    List<StockUniverse> findByIsActiveTrue();

    List<StockUniverse> findByIsActiveTrueOrderBySymbolAsc();

    Optional<StockUniverse> findBySymbolAndExchange(String symbol, Exchange exchange);
}