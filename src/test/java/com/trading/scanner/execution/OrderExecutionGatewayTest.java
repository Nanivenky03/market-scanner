package com.trading.scanner.execution;

import com.trading.scanner.position.PositionManagerService;
import com.trading.scanner.risk.RiskEngine;
import com.trading.scanner.risk.model.PortfolioMode;
import com.trading.scanner.strategy.fchb.FchbPositionHandler;
import com.trading.scanner.strategy.model.PositionSnapshot;
import com.trading.scanner.strategy.model.StrategyProposal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderExecutionGatewayTest {

    private RiskEngine riskEngine;
    private PositionManagerService positionManager;
    private OrderExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        riskEngine = new RiskEngine();
        riskEngine.initializePortfolio(PortfolioMode.PAPER, 30000.0, LocalDate.of(2026, 10, 11));
        FchbPositionHandler handler = new FchbPositionHandler();
        positionManager = new PositionManagerService(riskEngine, List.of(handler));
        @SuppressWarnings("unchecked")
        ObjectProvider<com.trading.scanner.service.provider.angelone.AngelOneMarketDataProvider> provider = mock(ObjectProvider.class);
        gateway = new OrderExecutionGateway(riskEngine, positionManager, provider);
    }

    private StrategyProposal createProposal(String symbol, String direction, double entry, double stop, double target) {
        return new StrategyProposal(
                symbol,
                "NSE",
                2,
                "TEST_STRATEGY",
                direction,
                entry,
                stop,
                target,
                entry - 2.0,
                100.0,
                Map.of("yellowCount", 0L)
        );
    }

    @Test
    @DisplayName("Successfully processes and fills candidate proposal through execution gateway")
    void testProcessCandidateProposal_Success() {
        StrategyProposal proposal = createProposal("SBIN", "LONG", 800.0, 790.0, 810.0);
        OrderExecutionGateway.ExecutionOutcome outcome = gateway.processCandidateProposal(proposal, PortfolioMode.PAPER);

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.symbol()).isEqualTo("SBIN");
        assertThat(outcome.direction()).isEqualTo("LONG");
        assertThat(outcome.executedPrice()).isEqualTo(800.0);
        assertThat(outcome.structuralStopLoss()).isEqualTo(790.0);
        assertThat(outcome.reAnchoredTarget1()).isEqualTo(810.0);

        PositionSnapshot pos = positionManager.getPosition(PortfolioMode.PAPER, "SBIN");
        assertThat(pos).isNotNull();
        assertThat(pos.symbol()).isEqualTo("SBIN");
    }

    @Test
    @DisplayName("Serializes concurrent proposals: Signal 1 consumes margin and Signal 2 evaluates against decremented available margin")
    void testConcurrentSignals_SerializedExecutionPipeline() throws Exception {
        // Two concurrent signals fired at the exact same moment
        StrategyProposal signalA = createProposal("SIG_A", "LONG", 500.0, 490.0, 510.0);
        StrategyProposal signalB = createProposal("SIG_B", "LONG", 500.0, 490.0, 510.0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger acceptedCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                OrderExecutionGateway.ExecutionOutcome out = gateway.processCandidateProposal(signalA, PortfolioMode.PAPER);
                if (out.accepted()) acceptedCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                OrderExecutionGateway.ExecutionOutcome out = gateway.processCandidateProposal(signalB, PortfolioMode.PAPER);
                if (out.accepted()) acceptedCount.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Both executed safely through the pipeline
        assertThat(acceptedCount.get()).isEqualTo(2);

        PositionSnapshot posA = positionManager.getPosition(PortfolioMode.PAPER, "SIG_A");
        PositionSnapshot posB = positionManager.getPosition(PortfolioMode.PAPER, "SIG_B");
        assertThat(posA).isNotNull();
        assertThat(posB).isNotNull();

        // Total committed margin strictly does not exceed total capital!
        double totalMargin = (posA.currentQuantity() * 500.0 / 5.0) + (posB.currentQuantity() * 500.0 / 5.0);
        assertThat(totalMargin).isLessThanOrEqualTo(30000.0);
    }
}
