package com.trading.scanner.repository;

import com.trading.scanner.model.RuntimeSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuntimeSettingRepository extends JpaRepository<RuntimeSetting, Long> {

    Optional<RuntimeSetting> findByNameAndIsActiveTrue(String name);

    List<RuntimeSetting> findByIsActiveTrueOrderByNameAsc();

    Optional<RuntimeSetting> findByName(String name);
}