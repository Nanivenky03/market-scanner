package com.trading.scanner.repository;

import com.trading.scanner.model.InstrumentMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstrumentMasterRepository extends JpaRepository<InstrumentMaster, Integer> {

    Optional<InstrumentMaster> findBySymbolAndExchange(String symbol, String exchange);

    List<InstrumentMaster> findByIsActiveTrueOrderBySymbolAsc();
}