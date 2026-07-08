package com.trading.scanner.repository;

import com.trading.scanner.model.ExchangeHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeHolidayRepository extends JpaRepository<ExchangeHoliday, Integer> {

    boolean existsByExchangeAndTradingDate(String exchange, LocalDate tradingDate);

    Optional<ExchangeHoliday> findByExchangeAndTradingDate(String exchange, LocalDate tradingDate);

    List<ExchangeHoliday> findByExchangeOrderByTradingDateAsc(String exchange);

    List<ExchangeHoliday> findByExchangeAndTradingDateBetweenOrderByTradingDateAsc(
            String exchange,
            LocalDate fromDate,
            LocalDate toDate);

    long countByExchange(String exchange);
}