package com.trading.scanner.repository;

import com.trading.scanner.model.Signal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SignalRepository extends JpaRepository<Signal, Integer> {

    List<Signal> findByStatus(String status);

    List<Signal> findBySymbolAndSignalTimeStartingWith(String symbol, String datePrefix);
}
