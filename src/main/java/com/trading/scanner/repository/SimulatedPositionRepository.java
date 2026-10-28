package com.trading.scanner.repository;

import com.trading.scanner.model.SimulatedPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulatedPositionRepository extends JpaRepository<SimulatedPosition, Integer> {

    List<SimulatedPosition> findByPositionStatus(String positionStatus);

    List<SimulatedPosition> findByPositionStatusAndEntryTimeStartingWith(String positionStatus, String datePrefix);

    Optional<SimulatedPosition> findBySymbolAndPositionStatus(String symbol, String positionStatus);
}
