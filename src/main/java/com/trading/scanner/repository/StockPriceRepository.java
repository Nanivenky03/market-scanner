package com.trading.scanner.repository;

import com.trading.scanner.model.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceRepository extends JpaRepository<StockPrice, Integer> {

        List<StockPrice> findBySymbolOrderByDateAsc(String symbol);

        List<StockPrice> findBySymbolAndDateLessThanEqualOrderByDateAsc(String symbol, LocalDate date);

        List<StockPrice> findByDate(LocalDate date);

        List<StockPrice> findBySymbolAndDateBetweenOrderByDateAsc(
                        String symbol, LocalDate from, LocalDate to);

        boolean existsBySymbolAndDate(String symbol, LocalDate date);

        Optional<StockPrice> findBySymbolAndDate(String symbol, LocalDate date);

        @Query("select p.date from StockPrice p where p.symbol = :symbol and p.date between :start and :end")
        List<LocalDate> findDatesBySymbolBetween(
                        @Param("symbol") String symbol,
                        @Param("start") LocalDate start,
                        @Param("end") LocalDate end);

        @Query("SELECT COUNT(sp) FROM StockPrice sp")
        long countAll();

        Optional<StockPrice> findFirstBySymbolOrderByDateDesc(String symbol);

        default LocalDate findLatestDateBySymbol(String symbol) {
                return findFirstBySymbolOrderByDateDesc(symbol)
                                .map(StockPrice::getDate)
                                .orElse(null);
        }
}