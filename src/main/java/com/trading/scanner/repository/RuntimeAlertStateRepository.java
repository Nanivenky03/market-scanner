package com.trading.scanner.repository;

import com.trading.scanner.model.RuntimeAlertState;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuntimeAlertStateRepository extends JpaRepository<RuntimeAlertState, Integer> {

    Optional<RuntimeAlertState> findByAlertKey(String alertKey);

    List<RuntimeAlertState> findByStatusOrderByUpdatedAtDesc(String status);

    List<RuntimeAlertState> findByStatusAndSeverityOrderByUpdatedAtDesc(String status, String severity);

    long countByStatusAndSeverity(String status, String severity);

    default List<RuntimeAlertState> findAllRecent() {
        return findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }
}