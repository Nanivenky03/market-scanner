package com.trading.scanner.repository;

import com.trading.scanner.model.SimulatedOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SimulatedOrderRepository extends JpaRepository<SimulatedOrder, Integer> {

    Optional<SimulatedOrder> findByUniqueOrderId(String uniqueOrderId);

    Optional<SimulatedOrder> findByBrokerOrderId(String brokerOrderId);

    List<SimulatedOrder> findByOrderStatus(String orderStatus);

    List<SimulatedOrder> findBySymbolAndPlacedAtStartingWith(String symbol, String datePrefix);

    List<SimulatedOrder> findByPlacedAtStartingWith(String datePrefix);

    List<SimulatedOrder> findByPlacedAtBetween(String fromTime, String toTime);

    List<SimulatedOrder> findAllByOrderByPlacedAtDesc();

    @Modifying
    @Query("DELETE FROM SimulatedOrder o WHERE o.placedAt LIKE CONCAT(:datePrefix, '%')")
    void deleteByPlacedAtStartingWith(@Param("datePrefix") String datePrefix);
}
