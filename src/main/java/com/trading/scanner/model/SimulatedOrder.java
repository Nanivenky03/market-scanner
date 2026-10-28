package com.trading.scanner.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "simulated_orders")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimulatedOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "signal_id")
    private Integer signalId;

    @Column(name = "parent_order_id")
    private Integer parentOrderId;

    @Column(name = "strategy_id", nullable = false)
    private Integer strategyId;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "symbol_token")
    private String symbolToken;

    @Column(name = "exchange", nullable = false)
    private String exchange;

    @Column(name = "variety")
    private String variety;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    @Column(name = "order_type", nullable = false)
    private String orderType;

    @Column(name = "product_type", nullable = false)
    private String productType;

    @Column(name = "duration")
    private String duration;

    @Column(name = "price")
    private Double price;

    @Column(name = "trigger_price")
    private Double triggerPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "disclosed_quantity")
    private Integer disclosedQuantity;

    @Column(name = "square_off")
    private Double squareOff;

    @Column(name = "stop_loss")
    private Double stopLoss;

    @Column(name = "trailing_stop_loss")
    private Double trailingStopLoss;

    @Column(name = "average_fill_price")
    private Double averageFillPrice;

    @Column(name = "filled_quantity")
    private Integer filledQuantity;

    @Column(name = "order_status", nullable = false)
    private String orderStatus;

    @Column(name = "broker_order_id")
    private String brokerOrderId;

    @Column(name = "unique_order_id")
    private String uniqueOrderId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "placed_at")
    private String placedAt;

    @Column(name = "filled_at")
    private String filledAt;

    @Column(name = "cancelled_at")
    private String cancelledAt;

    @Column(name = "created_at", nullable = false)
    private String createdAt;

    @Column(name = "updated_at", nullable = false)
    private String updatedAt;

    @PrePersist
    public void prePersist() {
        String now = java.time.LocalDateTime.now().toString();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = java.time.LocalDateTime.now().toString();
    }
}
