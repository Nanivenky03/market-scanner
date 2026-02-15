package com.trading.scanner.repository;

import com.trading.scanner.model.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {
    
    List<StockPrice> findBySymbolOrderByDateAsc(String symbol);
    
    List<StockPrice> findByDate(LocalDate date);
    
    List<StockPrice> findBySymbolAndDateBetweenOrderByDateAsc(
        String symbol, LocalDate startDate, LocalDate endDate);
    
    boolean existsBySymbolAndDate(String symbol, LocalDate date);

    @Query("select p.date from StockPrice p where p.symbol = :symbol and p.date between :start and :end")
    List<LocalDate> findDatesBySymbolBetween(
        @Param("symbol") String symbol,
        @Param("start") LocalDate start,
        @Param("end") LocalDate end);
    
    @Query("SELECT COUNT(sp) FROM StockPrice sp")
    long countAll();
    
    @Query("SELECT sp FROM StockPrice sp WHERE sp.symbol = :symbol ORDER BY sp.date DESC LIMIT 1")
    StockPrice findLatestBySymbol(@Param("symbol") String symbol);
    
    default LocalDate findLatestDateBySymbol(String symbol) {
        StockPrice latest = findLatestBySymbol(symbol);
        return latest != null ? latest.getDate() : null;
    }
}
