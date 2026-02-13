package com.trading.scanner.repository;

import com.trading.scanner.model.SimulationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Simulation State Repository
 * 
 * Manages the single-row simulation state table
 */
@Repository
public interface SimulationStateRepository extends JpaRepository<SimulationState, Integer> {
    // Standard CRUD operations via JpaRepository
    // ID is always 1 (single row table)
}
