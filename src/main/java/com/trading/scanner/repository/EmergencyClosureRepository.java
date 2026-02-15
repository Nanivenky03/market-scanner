package com.trading.scanner.repository;

import com.trading.scanner.model.EmergencyClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Repository
public interface EmergencyClosureRepository extends JpaRepository<EmergencyClosure, Long> {

    boolean existsByDate(LocalDate date);

    @Transactional
    @Modifying
    void deleteByDate(LocalDate date);
}
