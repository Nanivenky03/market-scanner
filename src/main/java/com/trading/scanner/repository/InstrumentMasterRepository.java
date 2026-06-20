package com.trading.scanner.repository;

import com.trading.scanner.model.InstrumentMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InstrumentMasterRepository extends JpaRepository<InstrumentMaster, Integer> {

    Optional<InstrumentMaster> findBySymbolAndExchange(String symbol, String exchange);

    List<InstrumentMaster> findByIsActiveTrueOrderBySymbolAsc();

    @Query("""
                select im
                from InstrumentMaster im
                where im.isActive = true
                  and im.exchange = :exchange
                  and (
                        lower(im.symbol) like lower(concat('%', :query, '%'))
                     or lower(im.companyName) like lower(concat('%', :query, '%'))
                  )
                order by im.symbol asc
            """)
    List<InstrumentMaster> searchActiveByExchangeAndQuery(
            @Param("exchange") String exchange,
            @Param("query") String query);

    Optional<InstrumentMaster> findByBrokerToken(String brokerToken);
}