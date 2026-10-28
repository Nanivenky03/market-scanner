package com.trading.scanner.repository;

import com.trading.scanner.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Integer> {

    Optional<Order> findByBrokerOrderId(String brokerOrderId);

    Optional<Order> findByUniqueOrderId(String uniqueOrderId);

    List<Order> findByOrderStatus(String orderStatus);

    List<Order> findBySymbolAndPlacedAtStartingWith(String symbol, String datePrefix);
}
