package com.trading.scanner.repository;

import com.trading.scanner.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<Position, Integer> {

    List<Position> findByPositionStatus(String positionStatus);

    List<Position> findByPositionStatusAndEntryTimeStartingWith(String positionStatus, String datePrefix);

    List<Position> findByEntryTimeStartingWith(String datePrefix);

    Optional<Position> findBySymbolAndPositionStatus(String symbol, String positionStatus);
}
