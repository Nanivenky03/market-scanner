package com.trading.scanner.repository;

import com.trading.scanner.model.SimulationVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulationVariantRepository extends JpaRepository<SimulationVariant, Integer> {

    List<SimulationVariant> findByRunGroupIdOrderByIdAsc(Integer runGroupId);
}