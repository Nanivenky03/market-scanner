package com.trading.scanner.repository;

import com.trading.scanner.model.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    List<StockPrice> findBySymbolOrderByDateDesc(String symbol);

    List<StockPrice> findBySymbolAndDateBetweenOrderByDateDesc(
            String symbol, LocalDate startDate, LocalDate endDate);

    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol " +
            "ORDER BY sp.date DESC")
    List<StockPrice> findTopBySymbolOrderByDateDesc(String symbol);

    /**
     * FIXED: Get latest date by fetching entity instead of MAX() aggregate
     * SQLite stores dates as TEXT, MAX() returns TEXT that can't be parsed to LocalDate
     */
    default LocalDate findLatestDateBySymbol(String symbol) {
        List<StockPrice> prices = findTopBySymbolOrderByDateDesc(symbol);
        if (prices.isEmpty()) {
            return null;
        }
        return prices.get(0).getDate();
    }

    @Query("SELECT DISTINCT sp.symbol FROM StockPrice sp")
    List<String> findAllSymbols();

    boolean existsBySymbolAndDate(String symbol, LocalDate date);
}