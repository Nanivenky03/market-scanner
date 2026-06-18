package com.trading.scanner.repository;

import com.trading.scanner.model.SimulationTrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulationTradeRepository extends JpaRepository<SimulationTrade, Integer> {

    List<SimulationTrade> findByVariantIdOrderBySignalDateAsc(Integer variantId);

    void deleteByVariantId(Integer variantId);
}