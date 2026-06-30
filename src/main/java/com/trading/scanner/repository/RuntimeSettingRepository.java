package com.trading.scanner.repository;

import com.trading.scanner.model.RuntimeSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RuntimeSettingRepository extends JpaRepository<RuntimeSetting, Integer> {

    Optional<RuntimeSetting> findBySettingKeyAndScopeAndIsActiveTrue(String settingKey, String scope);

    List<RuntimeSetting> findByIsActiveTrueOrderByScopeAscSettingKeyAsc();

    Optional<RuntimeSetting> findBySettingKeyAndIsActiveTrue(String settingKey);
}