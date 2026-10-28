package com.trading.scanner.repository;

import com.trading.scanner.model.SimulatedSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulatedSignalRepository extends JpaRepository<SimulatedSignal, Integer> {

    List<SimulatedSignal> findByStatus(String status);

    List<SimulatedSignal> findBySymbolAndSignalTimeStartingWith(String symbol, String datePrefix);
}
