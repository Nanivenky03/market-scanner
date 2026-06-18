package com.trading.scanner.repository;

import com.trading.scanner.model.SimulationRunGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SimulationRunGroupRepository extends JpaRepository<SimulationRunGroup, Integer> {
}