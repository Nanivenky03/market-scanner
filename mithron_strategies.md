# Mithron — Three Strategy Complete Design
## Trader's Source Document for Implementation

---

# PART 1: COMMON RULES ACROSS ALL THREE STRATEGIES

These apply universally. Every strategy inherits all of these. None of them are optional.

---

## Master Market Filter

Before any strategy evaluates any signal, this must pass first.

```
Nifty LTP > Nifty VWAP → Long rules active → all three strategies may run
Nifty LTP < Nifty VWAP → Long rules suspended → no signals from any strategy

This is checked on every 5M candle close (tick-level or 1M crosses are noise).
Data Freshness Circuit Breaker:
  If the latest finalized NIFTY 5M candle is older than 10 minutes during market hours (> 09:25 AM):
    → Market regime is set to UNKNOWN and dataValid is set to FALSE.
    → Fail-closed rule: All new strategy entries are blocked automatically until fresh Nifty data resumes.

State machine:
  If Nifty 5M candle closes BELOW Nifty VWAP:
    → Suspend new entries from that candle close onwards
    → Existing open positions managed normally, not exited
  If Nifty 5M candle then closes BACK ABOVE Nifty VWAP:
    → New entries re-enabled from that candle close onwards
    → Day is NOT permanently killed
  Exception — do not re-enable if:
    The time is past 01:00 PM
    OR daily loss kill switch has already fired
    OR it is a wind-down session (>= 02:45 PM)
```

---

## Day Type and What It Changes

```
Calculated at 09:15 AM from first Nifty tick:
  gapPct = (niftyOpen - niftyPrevClose) / niftyPrevClose × 100

NORMAL day (gap between -0.75% and +0.75%):
  Breakout reference = prevDayHigh for all stocks
  All three strategies active from 09:30 AM

GAP_UP day (gap > +0.75%):
  Breakout reference = openingRangeHigh (not prevDayHigh)
  First Candle High Breakout: SUSPENDED entirely for the day
  ORB: active but position size reduced 50%
  VWAP Pullback: active but watchlist expiry reduced from 60 to 30 minutes

GAP_DOWN day (gap < -0.75%):
  All long rules suspended
  Short strategy active (Phase 2 — not yet built)
  No signals from any of the three current strategies
```

---

## Time Gates (applies to all strategies)

```
Before 09:15 AM:       No activity
09:15 AM to 09:30 AM:  Opening range building only. No signals.
09:30 AM:              All strategies become active
After 10:30 AM:        FCHB signals stop (09:30 AM to 10:30 AM entry window). Strict cutoff eliminates midday chop/decay.
After 12:30 PM:        ORB signals stop.
After 01:00 PM:        VWAP Pullback continues.
02:45 PM:              Begin closing positions
                       If profitable → exit at market
                       If at loss but above stop → exit at market
                       If stop already at breakeven → hold to hard close
03:00 PM:              HARD CLOSE — all remaining positions closed at market
                       No exceptions. No discretion.
```

---

## Expiry Day Rules

```
Weekly expiry (every Thursday, or Wednesday if Thursday is holiday):
  VWAP Pullback: active, reduce position size 30%
  FCHB: active, raise minimum VOL_X threshold to 2.5
  ORB: SUSPENDED entirely
  Minimum confidence score raised to 75 (from 60)
  Maximum trades reduced to 3 (from 5)
  Hard close at 02:15 PM (not 03:00 PM)

Monthly expiry (last Thursday of month):
  ALL THREE STRATEGIES: SUSPENDED for entire day
  No trading. No exceptions.
```

---

## Position Sizing & Dynamic Capital Management

```
Available Capital & Margin Management:
  - Mithron tracks total starting capital, live broker cash via SmartAPI RMS, margin currently committed to active positions, and unencumbered available buying power.
  - Sizing is calculated dynamically per trade based on available margin rather than a hardcoded static amount:
      AvailableMargin = min(LiveBrokerCash, RAM_Cash - TotalCommittedMargin)
  - Intraday margin committed per open position (MIS 5x leverage):
      CommittedMargin = (Quantity × EntryPrice) / 5.0

Universal Dual-Directional Sizing Formulas:
  riskRupees = availableTradingCapital × 0.010  (1.0% risk per trade)
  For LONG:  riskPerShare = entryPrice - stopLossPrice
  For SHORT: riskPerShare = stopLossPrice - entryPrice
  qtyStandard = floor(riskRupees / riskPerShare)

Smart Downsizing Under Capital Constraints:
  If (qtyStandard × entryPrice / 5.0) > AvailableMargin:
    qtyDownsized = floor((AvailableMargin × 5.0) / entryPrice)
  Else:
    qtyDownsized = qtyStandard

Hard Safety Gates on Downsized Trades:
  1. Minimum Quantity Gate: qtyFinal must be >= 2.
     Reason: Target 1 sells 50% of the position. With qty = 1, splitting is mathematically impossible.
     If qtyFinal < 2 → Trade rejected ("INSUFFICIENT_MARGIN_CAPITAL").
  2. 3x Friction Filter on Downsized Shares:
     Expected 1R Gross Profit = qtyFinal × riskPerShare
     Estimated Friction = Rs. 40 (flat brokerage) + (2 × qtyFinal × entryPrice × 0.0003)
     If 1R < 3 × Estimated Friction → Trade rejected ("EXCESSIVE_FRICTION_DRAG").
     Prevents taking heavily downsized positions where statutory taxes and broker fees consume the edge.

Margin Verification via Angel One SmartAPI RMS:
  Before executing or evaluating signals, Mithron queries Angel One RMS Limits:
    GET /rest/secure/angelbroking/user/v1/getRMS
  Available cash is synchronized into PortfolioState, eliminating blind reliance on in-memory estimates.
```

---

## Pre-Trade 3x Friction Viability Filter (Replaces Retired Net R:R Check)

```
Why the original R:R Formula (netRewardAt1R / netRiskAt1R >= 1.5) was retired:
  At an initial 1:1 gross reward-to-risk setup, netRewardAt1R = 1R - charges, while netRiskAt1R = 1R + charges.
  Because netReward is mathematically always smaller than netRisk, this ratio can never reach 1.0 (let alone 1.5) regardless of capital size.
  Every trade would fail, causing permanent execution paralysis.

The Definitive 3x Friction Viability Filter:
  Expected 1R Gross Profit >= 3 × Estimated Round-Trip Friction

Pre-Trade Conservative Friction Estimation (BrokerChargesCalculator):
  Implemented in `BrokerChargesCalculator.calculate3LegEstimatedFriction(tradeValue)`:
  - Brokerage: ₹60.0 flat (assumes conservative 3-leg execution: 1 entry + 2 partial exits at max ₹20/leg)
  - STT: 0.025% on sell turnover (₹0 on buy)
  - Stamp Duty: 0.003% on buy turnover (₹0 on sell)
  - NSE Exchange Txn Fee: 0.00307% on both legs
  - SEBI Turnover Fee: ₹10 / Crore (0.0001%) on both legs
  - GST: 18% on (Brokerage + Exchange Fees + SEBI Fees)
  - Round-Trip Turnover = 2 × (Quantity × EntryPrice)

Post-Trade Official Reconciliation:
  Pre-trade gating strictly uses the dedicated `BrokerChargesCalculator` above.
  Post-trade P&L accounting records exact actual charges deducted from broker order fills via `PositionManagerService.recordBrokerFill()`, updating `PortfolioState.accumulatedIncurredFriction` and `netRealizedPnl`.

Why this calculation protects small accounts (e.g., Rs. 30,000 capital):
  - On a normal Rs. 500 stock with Rs. 4 stop: Qty = 112, Turnover = Rs. 1.12L, Charges = ~Rs. 99.
    Rs. 450 (1R) >= 3 × Rs. 99 (Rs. 297) → PASSES.
  - On an Rs. 1,800 stock with tight Rs. 5.40 stop: Qty = 83, Turnover = Rs. 2.98L, Charges = ~Rs. 165.
    Rs. 450 (1R) < 3 × Rs. 165 (Rs. 495) → REJECTED (Friction Trap).
  It naturally steers the system away from high-turnover friction traps on tight stops!
```

---

## Traffic-Light Quality Gate (Red / Yellow / Green Matrix)

Mithron replaces opaque 0–100 numerical scoring with an intuitive, deterministic **Traffic-Light Quality Gate**. Every signal parameter is evaluated as **GREEN**, **YELLOW**, or **RED**.

```
Condition Classification Matrix (7 Quality Gates):

1. Breakout Extension (BO_%):
   GREEN:   0.0% to 0.4% above breakout reference (ideal entry sweet spot)
   YELLOW:  0.4% to 0.8% above reference (acceptable, slightly extended)
   RED:     > 0.8% above reference (chasing price; risk of immediate pullback)

2. Volume Multiple (VOL_X - Time-Normalized vs 20-Day Curve):
   GREEN:   VOL_X >= 2.5 (massive institutional participation)
   YELLOW:  VOL_X between 1.8 and 2.5 (solid above-average participation)
   RED:     VOL_X < 1.8 (insufficient breakout conviction)

3. Master Market Filter Alignment (Nifty vs VWAP):
   GREEN:   Nifty 5M Close > Nifty VWAP by >= 0.30% (strong macro tailwind)
   YELLOW:  Nifty 5M Close > Nifty VWAP by < 0.30% (positive, but narrow buffer)
   RED:     Nifty 5M Close < Nifty VWAP (master market filter suspended)

4. Dual Timeframe & Trend Context:
   GREEN:   15M Close > 15M VWAP AND 5M candle bodyRatio >= 0.65
   YELLOW:  15M Close > 15M VWAP AND 5M candle bodyRatio between 0.55 and 0.65
   RED:     15M Close < 15M VWAP OR 5M candle bodyRatio < 0.55

5. Macro & Day Type Filters:
   GREEN:   Normal day (-0.75% <= gap <= +0.75%), prevDayRangePct <= 3.0%
   YELLOW:  prevDayRangePct between 3.0% and 4.0%
   RED:     Gap-up day (> +0.75%), prevDayRangePct > 4.0%

6. Opening Candle Volume Ratio:
   GREEN:   Signal 5M volume >= 2.0x First Candle Volume
   YELLOW:  Signal 5M volume between 1.5x and 2.0x First Candle Volume
   RED:     Signal 5M volume < 1.5x First Candle Volume

7. Upper Circuit Headroom:
   GREEN:   Upper circuit headroom >= 3.0%
   YELLOW:  Upper circuit headroom between 2.0% and 3.0%
   RED:     Upper circuit headroom < 2.0% (liquidity freeze risk)

[AUDIT NOTE - RSI Gate Retired]:
Originally, a 5M RSI gate (55-72) was evaluated as Gate 3. Forensic trade-by-trade audit across 499 symbols
revealed that explosive institutional breakout runners routinely push 5M RSI to 74-82 within the first 15-30
minutes. Treating RSI > 72 as "overbought" disqualified the highest-performing trades of the entire dataset
(e.g., JSWSTEEL, TITAN, ATGL). Institutional momentum breakouts expand with RSI; RSI is mean-reverting, not
trend-following. The RSI gate was permanently removed from FCHB traffic lights.
```

### Trade Quota & Admission Rules Across the Day

```
Trades 1, 2, and 3 (Early/Core Trades):
  - 0 REDs permitted.
  - Maximum 2 YELLOWs permitted.
  - Setup must be predominantly GREEN.

Trades 4 and 5 (Late/Capping Trades):
  - 0 REDs permitted.
  - 0 YELLOWs permitted (MUST BE ALL GREEN).
  - Setup must be a textbook, flawless A+ candidate.

Rationale for Asymmetric Quality Gate:
  The first three trades represent the primary risk allocation of the day. If the portfolio takes those trades, capital is now deployed or partially subjected to intraday drawdown.
  Allowing marginal (YELLOW) setups for trades 4 and 5 risks over-trading and giving back daily gains. Trades 4 and 5 are strictly reserved for elite setups that demand inclusion.
```

---

## Risk Engine — Dual Portfolio Separation & Pre-Trade Gates

### Dual Portfolio State Separation (`PortfolioState`):

```
Core Principle:
  Paper trading and Live real-money trading MUST operate under separate, isolated portfolio states:
    - PortfolioState(PAPER): Tracks paper trade count, strategy-level counters, open paper positions, and simulated P&L.
    - PortfolioState(LIVE): Tracks live trade count, strategy-level counters, open broker positions, and actual broker P&L.

Why Portfolios Must Be Decoupled:
  If a single shared trade quota were used, paper trades triggered in the morning would block live real-money trades.
  Conversely, a paper trading drawdown must never trigger a kill switch on real-money trading.
  Both portfolios execute the exact same risk rules, but against their own isolated state machines.
```

### Strict 5-Phase Pre-Trade Risk Pipeline (Evaluated per Portfolio):

All gates are evaluated in a strict, predictable sequential pipeline. Fast, zero-cost checks reject invalid proposals before expensive calculations:

```
=============================================================================
PHASE 1: ZERO-COST FAST DISQUALIFICATIONS (Pre-Sizing)
=============================================================================
Gate 1: Symbol Lock (One Entry Per Stock Per Day)
  Strictly 1 entry per stock per day across all strategies combined in that portfolio.
  Rejected if portfolio.hasTradedSymbolToday(proposal.symbol()).
  Phantom Signal Protection: If an entry order cancels without executing, the symbol is unlocked.

Gate 2: Strategy-Level Daily Limits (Circuit Breaker & Over-Trading Guard)
  - Maximum 5 executed trades per day per strategy:
    Prevents over-trading in one setup; locks in daily strategy alpha once successful trades are secured.
    Rejected ("STRATEGY_MAX_DAILY_TRADES_EXCEEDED").
  - Maximum 3 losses per day per strategy (Cooling-off Rule):
    If a strategy suffers 3 consecutive or cumulative losses in a single session, the market regime is
    hostile to that setup (e.g. chop / mean-reversion trap). That strategy is suspended for the day.
    Rejected ("STRATEGY_COOLING_OFF_MAX_LOSSES_REACHED").

[NOTE on Concurrent Positions Cap]:
  The legacy static cap of "Max 3 open positions" was permanently removed. In a multi-strategy system,
  capping concurrent positions at 3 prematurely chokes independent, non-correlated setups from other strategies.
  Account safety is maintained dynamically by the 4.0% Master Rupee Risk Shield (Gate 6).

Gate 3: Existing Account Circuit Breaker
  Fast check if already realized loss + current open risk + reserved open exit friction >= 4.0% of current capital.
  Rejected ("DAILY_LOSS_KILL_SWITCH_TRIGGERED").

=============================================================================
PHASE 2: SIGNAL VALIDITY & SANITY
=============================================================================
Gate 3: Directional Structure & Stop Bounds
  For LONG:  Entry > InitialStopLoss AND Target1 > Entry.
  For SHORT: Entry < InitialStopLoss AND Target1 < Entry.
  Stop Distance: Must satisfy 0.2% <= StopDistance <= 5.0% of Entry Price.

=============================================================================
PHASE 3: SIZING, MARGIN & CHARGES ENGINE
=============================================================================
Gate 4: Dynamic Available Margin Check & Sizing
  Target risk = 1.5% of current capital (1R = Rs. 750 on Rs. 50k).
  AvailableMargin = CurrentCapital - CommittedMargin - ReservedExitFriction.
  If RequiredMargin > AvailableMargin:
    Downsize to available buying power (min 2 shares for 50% partial exit).
    If quantity < 2 rejected ("INSUFFICIENT_MARGIN_CAPITAL" or "QUANTITY_LESS_THAN_MINIMUM_2").

=============================================================================
PHASE 4: FRICTION VIABILITY FILTER
=============================================================================
Gate 5: Strategy Setup Viability & Downsizing Friction Guard
  Viability Rule: Strategy 1R target risk >= 3 × Estimated Round-Trip Friction (evaluated against strategy 1R target Rs. 750, not temporarily downsized quantity).
  Downsized Rule: Actual downsized risk/gain must exceed estimated round-trip charges.
  Rejected if targetRiskRupees < 3 * estimatedFriction or actualRiskRupees <= estimatedFriction ("EXCESSIVE_FRICTION_DRAG").

=============================================================================
PHASE 5: DYNAMIC RUPEE RISK SHIELD (<4.0% OF CURRENT CAPITAL)
=============================================================================
Gate 6: Master Dynamic Rupee Risk Shield
  Replaces arbitrary hard caps (Max 3 positions, Max 5 daily trades, 3.2% open risk buffer).
  Total Downside Exposure:
    totalDownsideExposure = realizedLoss (if negative) + openPositionsDownsideRisk (from active order SL) + openExitFriction + candidateRisk + candidateFriction.
  If totalDownsideExposure > 4.0% of CurrentCapital:
    Rejected ("DAILY_LOSS_KILL_SWITCH_TRIGGERED").
  When existing positions ratchet their stop-loss to breakeven or into profit, their downside risk drops to Rs. 0, immediately unlocking risk capacity for new high-probability breakout setups.
```

### Serialized Single-Writer Execution Gateway (`OrderExecutionGateway`):

```
The Concurrent Candidate Signal Race Condition:
  When multiple strategies or multiple symbols trigger breakout signals at the exact same 5-minute candle close (09:35:00):
  - If strategy threads evaluate available margin in parallel, both threads read the same available capital.
  - Both proposals pass Risk Engine gates simultaneously.
  - Both fire market orders to the broker simultaneously, causing the second order to be rejected with "Insufficient Margin" or creating an over-leveraged account.

The Institutional Gateway Solution:
  Mithron routes all qualified strategy proposals through OrderExecutionGateway:
    synchronized (executionGatewayMutex) {
        1. Evaluate proposal against RiskEngine (allocates margin & locks symbol upfront).
        2. If approved, place order with broker / simulation engine.
        3. On fill: PositionManagerService records position & reconciles trade book.
        4. On failure/rejection: RiskEngine unlocks symbol immediately.
    }
  Signals are processed sequentially in microseconds, eliminating race conditions while allowing position management and tick streaming to run fully concurrently per symbol.
```

---

## Single-Order State Machine & Race-Condition Prevention

### The Reverse Position Race Condition Hazard:

```
The Fatal Scenario in Algorithmic Intraday Execution:
  1. Position is LONG 100 shares.
  2. A protective Stop-Loss Market order (SL-M) for 100 shares rests on the broker's exchange book.
  3. Price experiences sudden volatility. An in-memory trigger fires (e.g., tighter trailing stop or time cutoff).
  4. If the application cancels the resting SL-M order and immediately sends a new MARKET SELL order:
     - Due to exchange network latency, the resting SL-M order triggers and fills on the exchange.
     - A few milliseconds later, the application's cancellation is rejected ("Order already filled"), BUT the new MARKET SELL order is ALSO placed and fills!
     - 100 shares sold + 100 shares sold = 200 shares sold.
     - The account is now ACCIDENTALLY SHORT 100 SHARES in real money!
     - If the stock bounces, the account suffers unlimited overnight or intraday short losses.

Mithron's Architectural Solution: The Single-Order State Machine
  At no point does Mithron ever place a second exit order while an existing exit order rests on the broker server.
```

### Order Lifecycle & In-Place Modification (`modifyOrder`):

```
1. Entry Order:
   - On 5M signal confirmation (09:35:00), place immediate MARKET ORDER.
   - Entry price fills at next 1M candle open.

2. Initial Protective Stop Placement:
   - Immediately upon entry fill confirmation, place a single resting STOPLOSS_MARKET order on the broker:
       transactionType = SELL
       orderType = STOPLOSS_MARKET
       triggerPrice = initialStopLoss (firstCandleLow × 0.995)
       quantity = positionQuantity

3. Trailing Stop Adjustments (1M Candle Boundary):
   - In-memory trailing stop calculated on every tick and 1M candle close.
   - When a tighter stop is confirmed (at 0.8R breakeven, or ATR ratchet), Mithron calls SmartAPI modifyOrder:
       modifyOrder(orderId, newTriggerPrice)
   - The resting order is modified IN-PLACE on the exchange order book.
   - Zero new orders created. Zero race condition possible.

4. Pure Monotonic Stop Loss & Emergency Exits:
   - Institutional Invariant: Stops move monotonically forward only (tightening risk, never widening).
   - Retest Holding: Normal breakout retests above the stop loss do NOT trigger premature exits. The trade is given breathing room to develop.
   - Stop Loss Hit: If price touches or crosses the stop level, the resting STOPLOSS_MARKET order triggers an immediate exchange execution.
   - Emergency or EOD Sweep Exits: Mithron modifies the EXISTING resting stop-loss order directly to MARKET:
       modifyOrder(orderId, orderType="MARKET")
   - The broker exchange instantly converts the resting order into an immediate market execution.
   - If the resting order already filled just before the modification reached the broker, the modify call returns "Order already executed" (AB1004/AB1008). Mithron catches this response and cleanly marks position status as CLOSED without sending any redundant sell orders.
```

---

## Execution Timing: Real-Time Ticks vs Candle Closes

```
Decoupling of Entry Signals vs Exit Monitoring:

1. Entry Signal Evaluation:
   - Evaluated strictly on FINALIZED CANDLE CLOSES (e.g. 5M candle close for FCHB).
   - Prevents false-breakout noise and mid-candle phantom signals.
   - Consumes in-memory StockContextCache sliding windows.

2. Target & Stop-Loss Monitoring:
   - Evaluated on EVERY REAL-TIME TICK in memory (via LiveMarketSnapshotService).
   - Why: A sudden 30-second spike or flash crash can pierce a target or stop loss and retrace before a 1M candle finishes. Waiting for a 1M candle close to detect a stop or target hit introduces catastrophic slippage.
   - Tick breaches trigger immediate order execution or broker order modification.

3. Trailing Stop Ratchet:
   - Ratchet adjustments evaluate at 1M candle close boundaries (and on milestone target hits).
```

---

## Pre-Market Day Open Resilience & Outage Recovery

```
Canonical Day Open Definition:
  - Day Open is the 09:08:00 uncrossed equilibrium price from the NSE pre-market auction.
  - Frozen and locked into DailyStockContext and stock_prices between 09:08 and 09:10 AM.

Handling Pre-Market Network Outages (09:00 to 09:14 AM):
  - If a network disconnect or WebSocket feed outage occurs during the pre-market auction:
    • NEVER fall back to the continuous 09:15 candle open. The 09:15 open contains continuous session order book imbalances and does NOT equal the official NSE Day Open.
    • The pre-market workflow checks if open price in DailyStockContext / stock_prices is null or zero.
    • If missing, Mithron calls Angel One SmartAPI Market Quote API (GET /rest/secure/angelbroking/market/v1/quote) during the 09:10–09:14 window to fetch the authoritative open quote.
  - Strategies remain 100% pure functions: they consume DailyStockContext.getTodayOpenPrice() and never invoke broker APIs directly.
```

---

## Strategy Parameter Architecture: YAML Baselines + DB Overrides

```
Parameter Management:
  1. YAML Definition (Version Controlled):
     - Every strategy defines baseline parameters in a versioned YAML file (e.g., config/strategies/fchb_strategy.yaml).
     - Contains default values for RSI boundaries, extension limits, volume multipliers, and time cutoffs.
  2. Database Seeding & Runtime Overrides (strategy_rule_value table):
     - On bootstrap, defaults from YAML are inserted into strategy_rule_value if not already present.
     - Parameters can be adjusted dynamically in the database without code recompilation or application redeployment.
  3. Immutable Signal Auditing:
     - When any strategy evaluates a candidate, the exact parameter values used are serialized into JSON and stored in signals.rule_context, ensuring 100% historical reproduceability.
```

---

## Long vs. Short Strategy Decoupling

```
Architectural Separation:
  - Long strategies (e.g., FchbStrategy) and Short strategies (e.g., FclbStrategy - First Candle Low Breakdown) are implemented as SEPARATE, INDEPENDENT CLASSES.
  - Rationale:
    1. Asymmetric Market Physics: Selloffs are high-velocity and panic-driven; rallies are grinding and accumulation-driven. Indicators require different lookbacks and thresholds.
    2. Regulatory Constraints: Intraday short selling in Indian cash equities requires MIS product type and mandatory square-off before 03:15 PM, whereas delivery/CNC applies differently.
    3. Maintenance & Cleanliness: Combining long and short branches into a single class leads to conditional spaghetti code and brittle regressions.
```

---

## Decoupling Signal Generation and Position Management

```
Role Separation:
  - Signal Evaluator (e.g., FchbStrategy): Pure function implementing Strategy.evaluate(StockContext, MarketState). Operates on finalized candles to propose trade entries.
  - Position Handler (e.g., FchbPositionHandler): Implements StrategyPositionHandler.evaluatePosition(Position, LiveMarketSnapshot). Operates on incoming ticks and 1M candles to manage trailing stops, partial profit taking, and exits.
  - PositionManagerService: Central service holding all OPEN positions across strategies, dispatching each position to its corresponding StrategyPositionHandler based on strategy_id.
```

---

## Universal Trade Lifecycle

Applies identically to all three strategies after entry:

```
Entry price = 0R reference point
Initial stop loss = -1R

Price reaches 0.8R:
  Move stop loss UP to entry price (0R = breakeven)
  Worst case from this point forward = zero loss
  Stop never comes back down from here

Price reaches 1R (Target 1 Partial Exit):
  EXIT 50% of position at market
  Unconditional Stop Lock: Stop for remaining 50% immediately moves to max(currentStop, entryPrice)
    - If 0.8R ratchet already fired: Stop is already at entry (unchanged).
    - If price jumped directly past 0.8R to 1R in a single tick: Stop is moved from FCL to entry price immediately.
    - Zero downside risk on the remaining position.
  ATR-based trailing stop begins on remaining 50% (Candidate stop = 1M Low - 0.5 * 5M ATR). Stop only ratchets upward.

Trailing stop on remaining 50% (ATR-based):
  On every 1M candle close that is HIGHER than previous:
    new_stop = that_candle_low - (0.5 × 5M_ATR)
    Only update if new_stop > current trailing stop
    Stop never moves down, only up

Price hits stop at any stage:
  Market order exit, full remaining quantity
  In prod: Single-order state machine converts resting stop order to MARKET.

At 02:45 PM (02:15 PM on expiry days):
  If profitable (any amount) → exit at market
  If at loss but above stop → exit at market
  If stop already at breakeven or better → hold to hard close

At 03:00 PM (02:15 PM on expiry days):
  Hard close all remaining positions at market
  No exceptions
```

---

## Entry Order Rules

```
Canonical Execution Model (Market Order on Signal Confirmation):
  When a 5M candle closes and satisfies all strategy conditions (BO_%, VOL_X, RSI, Nifty > VWAP, Traffic Light Gate):
    → Signal is confirmed immediately on that 5M candle close (e.g., 09:35:00)
    → Place immediate MARKET ORDER for entry
    → Execution Price: Next 1-minute candle Open price (e.g., 09:35:00 Open)
    → In simulation: entryPrice = nextCandle.openPrice

Why Market Order over Limit Order:
  - Eliminates execution latency and 1-minute artificial delay
  - Eliminates unfill risk on genuine high-momentum breakouts
  - Eliminates limit-order timeout ambiguity in simulation and replay
  - Accurately mirrors real-world automated production execution

On fill (Dual-Layer Protection Architecture):
  1. Immediately calculate exact stop loss price (FCL × 0.995).
  2. Exchange Resting Stop: Place resting protective stop order (SL-M) immediately on broker exchange.
     Managed in real time via Single-Order State Machine (modifyOrder for trailing stops and market exits).
  3. GTT Crash Protection (Server-Side Broker Safety Net):
     Immediately register a server-side GTT (Good Till Triggered) stop order at FCL × 0.995 directly on Angel One servers.
     Why GTT is essential:
       - If the local Windows application crashes, the laptop reboots, or home internet drops, the GTT order lives on Angel One's cloud infrastructure.
       - Guarantees catastrophic loss protection during abnormal runtime failures.
       - When trailing stop moves: Cancel old GTT and register new GTT.
       - On normal position exit or 15:00 hard close: Cancel active GTT order cleanly.

---

## Hard Rules That Cannot Be Overridden

```
No manual override — ever
One entry per stock per day — no exceptions
  If stop loss hit on RELIANCE, no re-entry on RELIANCE today
  Even if strong signal fires again
  Even if stock then rallies strongly
No re-entry after target hit on same stock same day
No trading before 09:30 AM
Hard close at 03:00 PM — no exceptions
Stop loss always executes as market order — never hope for recovery
No scalping (charges make it impossible at this capital)
No news trading
No reversal trading (not until year 2 minimum)
Monthly expiry = no trading
```

---

## Common Hard Filters (Run Before Any Signal)

These are checked once before strategy-specific evaluation begins.

```
Filter 1 — skipToday flag:
  If daily_stock_context.skipToday == true → skip for entire day
  Currently always false until scrapers built
  Covers: F&O ban, corporate actions, recent results

Filter 2 — Already extended:
  If (currentPrice - todayOpenPrice) / todayOpenPrice > 2.5%
  → skip this signal
  Stock has already moved too much. You are chasing.

Filter 3 — Circuit proximity:
  If currentPrice > upperCircuitLimit × 0.98
  → skip this signal
  Stop loss unexecutable if stock hits circuit and freezes.
  upperCircuitLimit comes directly from SnapQuote WebSocket feed.

Filter 4 — Post-volatile day:
  If prevDayRangePct > 4%
  → skip all signals for this stock today
  ATR, RSI, volume all read distorted post-volatile data.

Filter 5 — Individual gap (FCHB specific):
  If stock individually gapped up > 1.5% at open
  → skip FCHB for this stock today
  First candle high is artificially elevated.
```

---

# PART 2: STRATEGY 1 — VWAP PULLBACK

---

## Concept

Breakout scanner identifies stocks that have broken above previous day's high. You do NOT enter at the breakout. You wait for the stock to pull back toward VWAP and then bounce off it. You enter on the bounce.

Why this works: When a strong stock breaks out, smart money often pulls it back toward VWAP to accumulate more shares at better prices. This creates a healthy low-volume pullback followed by a fresh surge with renewed buying. You enter near VWAP with a tight stop, targeting the morning breakout high and beyond.

Natural R:R: 1:2 to 1:3 because entry is tight near VWAP and target is back to the morning high.

---

## Stage 1 — Stock Selection (Breakout Scanner)

Runs on 5M candle closes from 09:30 AM onwards.

```
ALL of these must be true on the 5M signal candle:

1. 5M close > breakoutReferencePrice
   (prevDayHigh on normal days, openingRangeHigh on gap-up days)

2. BO_% between 0 and 1.0%
   = (close - breakoutReferencePrice) / breakoutReferencePrice × 100
   If > 1.0%: price has already run too far. Do not enter.

3. VOL_X >= 1.5 (time-normalized volume comparison)

4. RSI on 5M between 55 and 72
   Below 55: not enough momentum
   Above 72: overextended, high reversal risk

5. Stock close > stock's own VWAP
   Confirms institutional support below current price

6. 15M context: last 3 consecutive 15M candles close above 15M VWAP
   Confirms broader trend is supportive

7. Signal candle must be strong bullish:
   bodyRatio >= 0.60
   upperWickRatio <= 0.25
   rangePct >= 0.15%
   close > open

8. Nifty above Nifty VWAP (master filter)

9. Confidence score >= 60

10. VWAP distance at breakout:
    (close - stockVWAP) / stockVWAP × 100 must be <= 2.0%
    If stock is > 2% above VWAP at breakout: pullback to VWAP
    would require too large a drop. By the time it pulls back
    that far, momentum is likely dead. Do not add to watchlist.

→ Stock added to VWAP_WATCHLIST with timestamp
→ Record: breakout price, morning high (today's high at that moment)
```

---

## Stage 2 — Pullback Detection

Monitored on every 1M candle close for all stocks in watchlist.

```
Signs of a genuine healthy pullback (ALL should be true):

1. Price is moving DOWN from breakout level toward VWAP
   At least 3 consecutive 1M candles closing bearish (close < open)

2. RSI on 1M is declining candle by candle
   Confirms momentum is genuinely cooling
   If RSI is RISING during the pullback → this is not a real pullback
   It is accumulation or sideways chop → remove from watchlist

3. Volume on pullback candles is LOWER than the breakout candle
   Low volume pullback = profit takers exiting, not aggressive selling
   High volume pullback = distribution, institutions selling into rally
   → Remove from watchlist if pullback volume > breakout volume

4. Price is now within 0.3% of current VWAP
   vwapProximityPct = (currentPrice - stockVWAP) / stockVWAP × 100
   Must be between -0.3% and +0.3%
```

---

## Stage 3 — Entry Trigger (Bounce Confirmation)

Monitored on 1M candles after stock reaches VWAP proximity.

```
Entry requires ALL of these:

1. First 1M candle closes GREEN above VWAP (close > VWAP)
2. Second consecutive 1M candle also closes GREEN above VWAP
3. Volume on second candle > 1.2× recent average
   (fresh buying appearing, not just dead cat bounce)
4. Price is still BELOW the morning breakout high
   (room to run toward target)
5. VWAP direction at entry: RISING or FLAT
   If VWAP itself is falling: institutional money is exiting
   Do not enter against falling VWAP
6. Nifty still above its VWAP at this moment

→ Entry signal confirmed
→ Run full confidence score (must still be >= 60)
→ Run risk engine (all 5 checks)
→ Wait for next 1M candle confirmation (check if also green)
→ Place limit buy at confirmation candle high + 0.1%
→ Auto-cancel if not filled within 2 minutes
```

---

## Stop Loss

```
Stop loss price = 0.4% below VWAP at time of entry
  = entryVWAP × 0.996

Rationale: VWAP is the support level. If price closes a full
1M candle below VWAP after entry, the bounce has failed.

Stop loss monitoring:
  Primary: tick data monitors price in real time
  Early exit trigger: if 1M candle CLOSES below VWAP
    → Exit at market immediately
    → Do not wait for stop loss level to be hit
    → A full 1M candle close below VWAP means the bounce failed

GTT placed immediately on Angel One at stop loss price.
```

---

## Targets

```
Target 1 (first exit, 50% of position):
  Morning breakout high (the high made at the time of breakout)

Target 2 (remaining 50%):
  ATR-based trailing stop after first exit
  Trail follows every new 1M candle high
  No fixed target — let winner run as long as trail holds

Typical R:R: 1:2 to 1:3 because entry is tight near VWAP
and target is the morning high which can be 1.5-2.5R away
```

---

## Watchlist Management

```
Maximum watchlist size: 10 stocks simultaneously
  If 11th stock signals a breakout: score it and add the best
  10 by score. Lowest scoring existing watchlist stock is dropped
  if it has not yet pulled back.

Watchlist expiry — stock is REMOVED from watchlist when ANY of:

  Trigger 1 (time): No pullback to VWAP within 60 minutes of
    breakout (30 minutes on gap-up days)
    Stock ran without pulling back. Do not chase.

  Trigger 2 (price): Price falls more than 1.5% below breakout level
    This is no longer a healthy pullback.
    Something is wrong with the stock's momentum.

  Trigger 3 (VWAP direction): VWAP itself starts declining after
    stock entered watchlist
    Institutional money is exiting. Setup invalidated.

  Trigger 4 (failed bounce): First bounce attempt fails
    Price touches VWAP, gets one green 1M candle, then falls back below
    Remove stock from watchlist. The level is weakening.
    Do not wait for a second bounce.

  Trigger 5 (time gate): marketSession reaches ORB_CUTOFF (12:30 PM)
    VWAP Pullback continues, but any stock that has been on watchlist
    since before 09:30 AM without pulling back should be reviewed.
    Stocks added after 10:30 AM only get 30 minutes on watchlist.

One entry per stock per day:
  If a stock enters watchlist, entry fires, stop loss hits
  → stock is removed from watchlist for remainder of day
  Do not try again on the same stock.
```

---

## VWAP Pullback Hard Filters (In Addition to Common Filters)

```
Filter 6 — RSI rising during pullback:
  If RSI on 1M is increasing while price is falling
  → Remove from watchlist immediately
  This is accumulation, not a pullback

Filter 7 — Breakout candle must be strong bullish:
  Breakout candle that triggered watchlist entry must pass
  strongBullish check. If it was a weak candle, the entire
  setup has no conviction. Do not add to watchlist.

Filter 8 — VWAP distance at breakout > 2%:
  Already covered in stock selection above.
```

---

## VWAP Pullback Timeframe Usage

```
5M candles:  Stock selection (RSI, VOL_X, strong candle at breakout)
             ATR for trailing stop calculation
1M candles:  Pullback monitoring (RSI direction, consecutive bearish)
             Volume direction during pullback
             Bounce confirmation (two consecutive green above VWAP)
             Entry confirmation candle
             ATR for abnormal move detection on open position
15M candles: Context confirmation (last 3 closes above 15M VWAP)
Tick data:   Real-time stop loss monitoring
             Real-time VWAP proximity calculation
```

---

# PART 3: STRATEGY 2 — FIRST CANDLE HIGH BREAKOUT (FCHB)

---

## Concept

The first five-minute candle of the day (09:15 to 09:19 AM) absorbs all overnight news, pre-open manipulation, and gap effects. Its high is the cleanest resistance level of the entire day. When price breaks above this level later in the session with strong volume and RSI momentum, it signals that buyers have decisively taken control after the opening period settled.

Why this works: The opening candle represents a battle. The high of that battle is where sellers held back buyers. When buyers come back later with conviction (high volume, strong candle, RSI momentum), they are defeating the resistance from the morning, which tends to lead to continuation.

This strategy is SUSPENDED on gap-up days (Nifty gap > 0.75%) because the first candle high is artificially elevated by the gap. Breaking above an already-gapped-up first candle high has no technical meaning.

---

## First Candle Reference Building (09:15 to 09:19 AM)

Done before signals can fire. Stored in `daily_stock_context`.

```
Uses five 1M candles: 09:15, 09:16, 09:17, 09:18, 09:19

firstCandleOpen   = open of 09:15 1M candle
firstCandleHigh   = max(high) across all 5 candles
firstCandleLow    = min(low) across all 5 candles
firstCandleClose  = close of 09:19 1M candle
firstCandleVolume = sum(volume) across all 5 candles
firstCandleRangePct = (firstCandleHigh - firstCandleLow) / firstCandleLow × 100
firstCandleBullish  = firstCandleClose > firstCandleOpen (strictly green candle)
```

**First candle quality filter (if any fail → FCHB suspended for this stock today):**
```
1. firstCandleRangePct < 0.15%: range too tight, reference level meaningless
2. firstCandleRangePct > 3.0%:  chaotic opening volatility, protective stop too wide
3. firstCandleBullish == false: bearish or doji first candle indicates negative/indecisive opening sentiment
4. Missing candle guard: All five 1M bars (09:15..09:19) must be fully finalized and present in memory

Note on Volume Normalization & Turnover Floor:
  - Mithron avoids arbitrary static turnover thresholds (e.g. rigid Rs. 1 Crore floor) and raw opening candle volume multipliers (e.g. 2.0x first candle volume).
  - Rationale: High-quality mid-cap setups often build liquidity progressively after 09:30 AM rather than printing Rs. 1 Crore in the opening 5 minutes. Furthermore, opening candles frequently contain institutional block uncrossing trades that artificially inflate first candle volume, rendering a 2.0x comparison unrealistic.
  - Instead, volume conviction is strictly and mathematically normalized via VOL_X (comparing 5M volume against the symbol's 20-day time-of-day baseline curve).
```

---

## Entry Signal Detection (5M candle close, 09:30 AM to 10:30 AM)

Operates as a pure decision function (`FchbStrategy.evaluate(StockContext, MarketState)`):

```
ALL conditions must pass:

1. 5M candle closes ABOVE firstCandleHigh
   (breakout confirmed strictly on finalized 5M candle close)

2. Breakout Extension: BO_% <= 0.8%
   = (5M_close - firstCandleHigh) / firstCandleHigh × 100
   If > 0.8%: price has extended too far from base; chasing risk is RED.

3. Time-Normalized Volume: VOL_X >= 1.8
   Volume exceeds 180% of the symbol's 20-day historical average for this exact 5-minute interval.

4. Intraday Trend Support: Stock Close > Stock VWAP
   Buyers maintain dominance above volume-weighted average price.

5. Dual Timeframe Alignment: 15M Close > 15M VWAP
   Higher timeframe trend supports the breakout direction.

6. Strong Bullish Candle Structure:
   bodyRatio >= 0.60, upperWickRatio <= 0.25, rangePct >= 0.15%

7. Master Market Filter: Nifty 5M Close > Nifty VWAP
   Evaluated from MarketState. Master market filter must be active.

8. Morning Entry Window: 09:30 AM to 10:30 AM
   Signals stop strictly at 10:30 AM before midday volume decay and chop set in. Forensic replay proved that trades entered after 10:30 AM generated a dismal 16.7% win rate and severely drained account capital. Restricting entries to 09:30–10:30 AM locks in high-momentum expansion.

9. Traffic-Light Admission Gate:
   - Evaluates the 7 Quality Gates (BO_%, VOL_X, Nifty distance, 15M trend context, Day type, Opening vol ratio, Upper circuit room).
   - Trades 1, 2, 3: 0 REDs, maximum 2 YELLOWs.
   - Trades 4, 5: MUST BE ALL GREEN (0 REDs, 0 YELLOWs).

→ Signal qualified on 5M candle close
→ Dispatched to RiskEngine for dynamic margin & R:R verification
→ On approval: Immediate MARKET ORDER entry placed (fills at next 1M candle open)
```

---

## Protective Stop Loss & Multi-Tier Position Management (`FchbPositionHandler`)

Managed dynamically by `FchbPositionHandler` across incoming ticks and completed candle closes using a pure technical exit hierarchy:

```
Priority 1: 14:55:00 Hard Close Cutoff (Session Safety Boundary)
  - 15-minute buffer before broker RMS square-off (03:10–03:15 PM) to prevent broker penalty charges.
  - Unconditional market exit for all remaining open positions ("HARD_CLOSE_TIME_CUTOFF").

Priority 2: 14:45:00 Wind-Down Cutoff (Conditional Capital Protection)
  - If a position has NOT yet hit Target 1 and stop loss is still below breakeven, exit immediately ("WIND_DOWN_EXIT").
  - If position is profit-protected at or above breakeven (Target 1 booked), runner holds until 14:55 hard close or trailing stop.
  - [EMPIRICAL VALIDATION]: Replay audit proved holding until 14:45 earned +₹1,087.55 more Gross P&L (+₹1,394.73 Net)
    than exiting at 14:15, capturing the late-afternoon institutional continuation squeeze.

Priority 3: Real-Time Tick Stop Loss Hit (Emergency Floor & Trailing Breaches)
  - Placed immediately as resting STOPLOSS_MARKET order on broker exchange upon entry fill.
  - Initial stop loss: firstCandleLow × 0.995 (-1.0R catastrophic floor).
  - Evaluated on every tick: LTP <= currentStopLoss → FULL EXIT ("STOP_LOSS_HIT").

Priority 4: Target 1 Partial Profit Booking (+0.73R) + Immediate Breakeven Lock
  - When LTP touches Target 1: entryPrice + 0.73 × (entryPrice - initialStopLoss):
    → Partial exit 50% of position quantity at market ("TARGET_1_HIT").
    → Immediately ratchet remaining 50% stop loss to Breakeven (entryPrice), eliminating downside risk.

Priority 5: Completed 5M Candle Invalidation Rules (Evaluated on Completed Candle Closes)
  Evaluated strictly on 5M candle closes to avoid 1-minute microstructure noise / shakeouts.
  
  - Rule 5A: 5M Failed Breakout Cut
    If 5M close < firstCandleHigh AND close <= Entry - 0.50R → FULL EXIT ("FAILED_BREAKOUT_EXIT").
    Cuts genuine failed bull traps at -0.50R, saving ~50% of risk compared to full -1.0R stop.
  - Rule 5B: 5M Stock VWAP Breakdown Cut
    If 5M close < Stock VWAP AND close <= Entry - 0.50R → FULL EXIT ("VWAP_BREAKDOWN_EXIT").
    Exits when volume-weighted institutional support is decisively lost by >= 0.50R.
  - Rule 5C: 5M Momentum Exhaustion Cut
    If 5M EMA 9 < EMA 21 AND close < Entry → FULL EXIT ("MOMENTUM_EXHAUSTION_EXIT").
    Cuts trade when short-term momentum crosses bearish while underwater.

Priority 6: Continuous Monotonic Dynamic Trailing Stop
  - Pre-Target 1 (Base Position):
    • Stop loss trails with a clean 1.0R leash behind peak price (stopLoss = peakPrice - 1.0R).
    • Does NOT prematurely snap to breakeven before Target 1 (prevents entry retest shakeouts).
    • At +0.50R price expansion, stop loss has ratcheted to -0.50R, cutting risk in half while giving trade room to breathe.
  - Post-Target 1 (Runner Position):
    • 50% cash booked at +0.73R, stop locked to Entry (Breakeven).
    • Stop loss trails with a clean 0.73R leash behind peak price (stopLoss = peakPrice - 0.73R).
    • Monotonic enforcement: stop only moves in the favorable direction, never backward.
```

---

## Institutional Statutory Charges & Multi-Broker Support (`BrokerChargesCalculator`)

Mithron integrates institutional fee accounting supporting both full-service discount brokers (Angel One) and zero-brokerage trading APIs (Kotak Neo, Finvasia Shoonya):

```
Universal Statutory Taxes (Applied by Law across All Indian Brokers):
  - STT (Securities Transaction Tax): 0.025% on SELL turnover only (Rs. 0 on BUY).
  - Stamp Duty: 0.003% on BUY turnover only (Rs. 0 on SELL).
  - NSE Exchange Turnover Charges: 0.00307% on both BUY and SELL turnover.
  - SEBI Turnover Charges: Rs. 10 per Crore (0.0001%) on both BUY and SELL turnover.
  - GST: 18% applied on (Brokerage + Exchange Fees + SEBI Fees).

Broker-Specific Brokerage Comparison:
  - Angel One (BrokerModel.ANGEL_ONE):
    • Lower of Rs. 20 flat or 0.1% per executed order leg (min Rs. 5.00).
    • A 3-leg trade (Entry, Target 1 partial, Runner exit) incurs up to Rs. 60 in raw brokerage + GST.
    • Average friction per trade: ~Rs. 45 - Rs. 55.
  - Kotak Neo (BrokerModel.KOTAK_NEO - RECOMMENDED):
    • Rs. 0.00 Brokerage on automated API intraday orders under Trade Free Youth / Plan.
    • Incurs strictly universal statutory government charges.
    • Average friction per trade: ~Rs. 15.29.
    • Saves Rs. 30 - Rs. 40 in pure alpha per round-trip trade (+3.28% return on capital saved in 23 days!).
```

---

## Empirical 23-Day Forensic Replay Audit (Kotak Neo vs Angel One)

The finalized FCHB Strategy and Risk Engine were rigorously replayed across all 23 historical sessions in `mithron_prod` database (Oct 6 – Nov 6, 2026) across 499 active tradable symbols with starting capital of Rs. 50,000.00:

```
========================================================================================
                      23-DAY REPLAY PERFORMANCE AUDIT RESULTS
========================================================================================
Sessions Replayed        : 23 trading days
Total Executed Trades    : 53 trades (2.3 trades/day average)
Winning Trades           : 33 (62.3% Win Rate)
Losing Trades            : 20 (37.7% Loss Rate)
Gross Realized P&L       : +Rs. 6,157.61
Kotak Neo Charges        : -Rs.   810.21 (Average Rs. 15.29 / trade)
Net Realized P&L         : +Rs. 5,347.40 (+10.69% Return on Capital)
Ending Capital           : Rs. 55,347.40
Profit Factor            : 1.91
Maximum Account Drawdown : Rs. 1,419.81 (2.52%)

Breakdown by Exit Reason:
  • HARD_CLOSE_TIME_CUTOFF (14:55):  8 trades |  8 Wins (100%) | Gross: +Rs. 4,827.20 | Net: +Rs. 4,719.90
  • WIND_DOWN_EXIT (14:45)        : 19 trades | 14 Wins ( 74%) | Gross: +Rs. 2,663.40 | Net: +Rs. 2,389.10
  • STOP_LOSS_HIT                 : 18 trades | 11 Wins ( 61%) | Gross: +Rs. 2,179.80 | Net: +Rs. 1,865.40
  • FAILED_BREAKOUT_EXIT          :  8 trades |  0 Wins (  0%) | Gross: -Rs. 3,512.80 | Net: -Rs. 3,626.90
    (5M Invalidation saved ~Rs. 3,500 by cutting losses at -0.5R instead of -1.0R full stop).

Wind-Down Price Comparison (14:15 vs 14:30 vs 14:45 across 27 Afternoon Trades):
  • Gross P&L if exited at 14:15  : +Rs. 6,096.15 (Net: +Rs. 5,714.20)
  • Gross P&L if exited at 14:30  : +Rs. 7,168.15 (Net: +Rs. 6,785.90)
  • Gross P&L at 14:45 Cutoff     : +Rs. 7,183.70 (Net: +Rs. 7,108.93)
  • Empirical Return Delta        : Exiting at 14:45 captured +Rs. 1,087.55 MORE Gross (+Rs. 1,394.73 Net)
                                    than 14:15. Moving wind-down earlier is counter-productive.
========================================================================================
```

---

## FCHB Hard Filters (In Addition to Common Filters)

```
Filter 9 — Previous close near 15-day resistance:
  distFromResistancePct = (highestClose15d - prevDayClose) / prevDayClose × 100
  If distFromResistancePct < 1.0%:
  Stock is at or near multi-week resistance.
  Breakout above first candle high immediately faces selling
  from that resistance zone. Skip.

Filter 10 — First candle must be bullish:
  firstCandleBullish == false → skip FCHB for this stock today
  A bearish first candle means opening sentiment was negative.
  Covered by firstCandleValid check above.

Filter 11 — Individual stock gap > 1.5%:
  If stock gapped up > 1.5% individually:
  First candle high is artificially elevated by the gap.
  Skip FCHB for this stock today.
  Use VWAP Pullback if this stock shows a breakout-pullback setup.

Filter 12 — Opening 5-Minute Rupee Turnover Floor (>= Rs. 25 Lakhs):
  firstCandleVolume * firstCandleClose >= 25,00,000
  Ensures institutional liquidity and participation on the breakout vehicle.
  Rejects illiquid micro-caps and low-volume traps where wide bid-ask spreads destroy returns.

Filter 13 — Share Price Ceiling (<= Rs. 5,000):
  currentClose <= 5000.0
  Excludes ultra-high ticket stocks where lot size = 1 or minimum position size constraints distort risk-adjusted sizing.

Filter 14 — Entry Window Cutoff (09:30 AM to 10:30 AM):
  Signal must fire between 09:30 AM and 10:30 AM. No new entries permitted after 10:30 AM to avoid late-morning low-volatility chop.
```

---

## FCHB Timeframe Usage

```
5M candles:  Signal generation (RSI, VOL_X, strong candle, BO_%)
             ATR for trailing stop
1M candles:  Entry confirmation candle (must be green)
             Early exit monitoring (candle close back in range)
             ATR for abnormal move detection
15M candles: Trend context (price above 15M VWAP)
Tick data:   Real-time stop loss monitoring
```

---

# PART 4: STRATEGY 3 — OPENING RANGE BREAKOUT (ORB)

---

## Concept

The first 15 minutes of trading (09:15 to 09:30 AM) establishes the battlefield for the day. During this period, buyers and sellers from the previous evening, overnight gap players, and morning news traders all fight it out. The high and low of this battle define the opening range. Whoever wins by breaking convincingly above or below this range with strong volume tends to control the direction of the day.

Why this works: The opening range captures all the noise and manipulation of the first minutes. After 09:30 AM, when the range is established, a genuine breakout above it on high volume with RSI momentum represents real directional conviction that tends to continue.

ORB is most sensitive to high volatility days and expiry days. On high volatility days (Nifty opening range > 0.8%), the opening ranges are so wide that stop losses become too large for the capital. ORB is suspended on these days and on monthly expiry.

---

## Opening Range Building (09:15 to 09:29 AM)

Done before signals can fire. Stored in daily_stock_context.

```
Uses all 15 1M candles: 09:15, 09:16, ..., 09:29

openingRangeHigh = max(high) across all 15 candles
openingRangeLow  = min(low) across all 15 candles

openingRangeSizePct = (high - low) / low × 100

openingRangeVolume = sum(volume) across all 15 candles

Opening range skew:
  avgTypicalPrice = mean of ((high + low + close) / 3)
                    across all 15 one-minute candles
  skewPosition = (avgTypicalPrice - openingRangeLow)
                 / (openingRangeHigh - openingRangeLow)
  Result: 0.0 to 1.0
  0.5 = price action perfectly centered
  < 0.25 = sellers dominated, price near bottom all opening
  > 0.75 = buyers pushed but failed to hold high

Opening range participation:
  baseline = 20-day average cumulative volume at session minute 14
             (09:29 AM) from volume_time_window_baseline
  participationRatio = openingRangeVolume / baseline
```

**Opening range quality filter (if any fail → ORB suspended for this stock today):**
```
openingRangeSizePct < 0.15%:  range too tight, meaningless level
openingRangeSizePct > 2.5%:   range too wide, stop too large
skewPosition < 0.25:          sellers dominated opening, avoid long
skewPosition > 0.75:          buyers pushed but failed, resistance strong
participationRatio < 0.50:    opening range formed on low volume,
                               levels have no significance
baseline is null or zero:     cannot assess participation → invalid
Missing any of 15 candles:    openingRangeReady = false → invalid

Zero range guard:
  if openingRangeHigh <= openingRangeLow → invalid immediately
```

**Nifty-level ORB suspension:**
```
Calculate Nifty's own opening range size:
  niftyORSizePct = (niftyORHigh - niftyORLow) / niftyORLow × 100
  If > 0.8%: highVolatilityDay = true
  → ORB suspended for ALL stocks that day
  Not just one stock — the entire strategy is off
```

---

## Entry Signal Detection (5M candle close, 09:30 AM to 12:30 PM)

```
ALL of these must be true:

1. 5M candle closes ABOVE openingRangeHigh

2. BO_% from opening range high <= 1.0%
   = (close - openingRangeHigh) / openingRangeHigh × 100
   If > 1.0%: too far above the level, chasing

3. VOL_X (time-normalized) >= 1.8
   Signal candle must show significantly above-average volume

4. RSI on 5M between 55 and 72

5. Stock close > stock VWAP
   Confirms market is supportive of this stock's move

6. 15M context: price above 15M VWAP

7. Signal candle is strong bullish:
   bodyRatio >= 0.60, upperWickRatio <= 0.25, rangePct >= 0.15%

8. Nifty above VWAP (master filter)

9. Signal only valid until 12:30 PM
   After 12:30 PM: ORB signals stop

10. highVolatilityDay == false
    Already checked at market open. Just confirm flag before entry.

11. expiryType != MONTHLY
    Monthly expiry = ORB suspended entirely

12. Confidence score >= 60

→ Signal confirmed
→ Wait for 1M confirmation candle to close green above openingRangeHigh
→ Place limit buy at 1M confirmation candle high + 0.1%
→ Auto-cancel if not filled within 2 minutes
```

---

## Stop Loss

```
Stop loss price = openingRangeLow × 0.995

Rationale: if price closes back inside the opening range,
the breakout has completely failed. The range was the battlefield.
Falling back inside it means buyers lost the battle.

Early exit trigger:
  If 1M candle CLOSES back inside the opening range
  (close falls below openingRangeHigh and the close is
  below the range high level)
  → Exit at market IMMEDIATELY
  Do not wait for stop loss level at openingRangeLow.
  The breakout failed. Limit the loss now.

GTT placed at openingRangeLow × 0.995 on Angel One immediately after fill.
```

---

## Targets

```
Target 1 (first exit, 50% of position):
  openingRangeHigh + openingRangeSize × 1.0
  = One full opening range size above the breakout level
  Example: OR high = 500, OR low = 496, OR size = 4
           Target 1 = 500 + 4 = 504

Target 2 (remaining 50%):
  ATR-based trailing stop
  Trail begins after 50% exits at Target 1
  Follow 1M candle lows minus 0.5 × 5M ATR

Minimum R:R gate:
  openingRangeSize must provide at least 1:1.5 R:R after charges.
  If opening range is very narrow, even Target 1 may not cover charges.
  Skip trade if R:R after charges < 1.5.
```

---

## ORB Hard Filters (In Addition to Common Filters)

```
Filter 12 — Multi-day downtrend:
  If consecutiveRedDays >= 4 → skip long ORB signal
  Underlying trend is bearish.
  ORB long is fighting the dominant trend.

Filter 13 — High volatility day:
  If highVolatilityDay == true → ORB suspended all day
  Already set at 09:29 AM. Just check flag before each signal.

Filter 14 — Expiry day:
  MONTHLY: ORB suspended entirely
  WEEKLY: ORB suspended entirely
  (Both types of expiry = no ORB)

Filter 15 — Time gate:
  After 12:30 PM: no new ORB signals regardless of any other condition
```

---

## ORB Timeframe Usage

```
5M candles:  Signal generation (RSI, VOL_X, strong candle, BO_%)
             ATR for trailing stop
1M candles:  Entry confirmation candle (must be green above OR high)
             Early exit monitoring (candle close back inside range)
             ATR for abnormal move detection
             Opening range construction (15 × 1M candles aggregated)
15M candles: Trend context (price above 15M VWAP)
Tick data:   Real-time stop loss monitoring
```

---

# PART 5: ABNORMAL MOVE PROTECTION (ALL STRATEGIES)

This applies to open positions across all three strategies.

```
While any position is open, monitor on every 1M candle:

Trigger: price moves MORE than 2× 1M_ATR against you in ONE 1M candle

Example:
  1M ATR = Rs.1.50
  2× ATR = Rs.3.00
  If price drops Rs.3.00+ in a single 1M candle against your position
  → EXIT AT MARKET IMMEDIATELY
  Do not wait for trailing stop level
  Something abnormal is happening (news, circuit, manipulation)
  Get out first, understand later

Also watch for abnormal volume:
  If 1M candle volume > 5× recent average on your open position
  → Flag as NEWS_SUSPECTED
  → Tighten trailing stop to 0.3× ATR immediately
  → If NEXT candle also shows abnormal volume → exit at market
```

---

# PART 6: WHAT THE APPLICATION DOES ON EACH CANDLE CLOSE

This describes the sequence of actions for your application at each timeframe event.

---

## On Every 1M Candle Close

```
1. Update VWAP for that stock (running cumulative)
2. Update VolumeContext (cumulative volume, direction)
3. Update 1M RSI and ATR
4. Update candle structure (body ratio, wicks, strong flags)
5. Check VWAP_WATCHLIST stocks for pullback status (VWAP Pullback)
6. Check for bounce confirmation on watchlist stocks (VWAP Pullback)
7. Check open positions for early exit conditions:
   - 1M close below VWAP (VWAP Pullback position)
   - 1M close back inside first candle range (FCHB position)
   - 1M close back inside opening range (ORB position)
8. Check open positions for abnormal move (2× ATR trigger)
9. Update trailing stop levels on open positions
10. Update GTT on Angel One if trailing stop moved
11. Derive 5M candle if 5M bucket complete (every 5 candles)
12. Derive 15M candle if 15M bucket complete (every 15 candles)
```

---

## On Every 5M Candle Close

```
1. Calculate 5M RSI and ATR
2. Calculate candle structure for 5M candle
3. Update VOL_X (current session minute lookup)
4. For VWAP Pullback: run breakout scanner on all universe stocks
   Check all stock selection conditions
   Add qualifying stocks to VWAP_WATCHLIST
   Check watchlist expiry triggers for all watchlist stocks
5. For FCHB: run breakout scanner if session is ACTIVE or ORB_CUTOFF
   Check all entry conditions against firstCandleHigh
6. For ORB: run breakout scanner if session is ACTIVE
   Check all entry conditions against openingRangeHigh
7. For any signal that passes:
   Calculate confidence score
   Run risk engine (all 5 checks)
   If all pass: proceed to 1M confirmation candle wait
8. Update MarketState (Nifty VWAP direction, session check)
```

---

## Morning Sequence (Before 09:30 AM)

```
09:15 AM first Nifty tick:
  Calculate Nifty gapPct
  Set dayType (NORMAL / GAP_UP / GAP_DOWN) — fixed for day
  Begin individual stock gap calculations

09:15 to 09:19 AM:
  Build first candle for every stock in universe
  Calculate firstCandleValid for every stock
  Stocks with firstCandleValid = false: FCHB suspended for them today

09:15 to 09:29 AM:
  Build opening range for every stock
  After 09:29 AM 1M candle finalizes:
    Calculate opening range skew, participation, validity
    Calculate Nifty opening range size → set highVolatilityDay flag
    Resolve breakoutReferencePrice for every stock
    Stocks with openingRangeValid = false: ORB suspended for them today
    If highVolatilityDay = true: ORB suspended for ALL stocks today

09:30 AM:
  Session transitions to ACTIVE
  All strategies can now generate signals
  First 5M candle closes at 09:20 (well, 09:19 is last 1M in it)
  Actually: first 5M candle 09:15-09:19 closes at 09:19
  Scanner begins on 09:20 5M candle close (actually 09:24)
  Wait — correct: first 5M signal possible on candle 09:20-09:24,
  which closes at 09:24. But time gate says no entries before 09:30.
  So first possible entry execution is at 09:30 AM.
```

---

# PART 7: SIMULATION RULES

During Layer 2 (live simulation), these additional rules apply.

```
No real orders are placed — simulation only
Track simulated PnL, charges, slippage as if trades were real

Slippage assumption for simulation:
  Entry limit order:    0.10% worse than signal price
  Stop exit market:     0.15% worse than stop level
  Target exit limit:    0.05% worse than target level

Performance metrics calculated on NET PnL (after charges and slippage)

Parameter change rule:
  Any change to any strategy parameter resets the 30-day simulation
  clock to zero. A rule earns real money only by surviving 30
  consecutive unchanged days.

Thresholds to pass before real money:
  Profit Factor > 1.3
  Expectancy positive (any amount)
  Maximum drawdown < 15%
  Consecutive loss streak <= 5
  Win rate > 40% (at 1:2 R:R)
  Minimum 30 trades in simulation period

If ALL six pass → consider real money
If ANY fails → extend simulation
```

---

# PART 8: WHAT EACH STRATEGY IS AND IS NOT

```
VWAP Pullback:
  IS:  A stateful strategy requiring watchlist management
       Stock must pass through stages: breakout → watchlist → pullback → bounce → entry
       The most complex of the three
  NOT: A simple candle-close evaluation
       Cannot be implemented as stateless signal scoring

FCHB:
  IS:  A stateless signal on 5M candle close
       Compares current price to a reference level built at 09:20 AM
       The simplest of the three to implement
  NOT: Valid on gap-up days (must be suspended)
       Valid after 01:00 PM

ORB:
  IS:  A stateless signal on 5M candle close
       Compares current price to a range built at 09:30 AM
       Middle complexity — stateless but with more filters than FCHB
  NOT: Valid on high volatility days, expiry days
       Valid after 12:30 PM
       Valid when opening range quality is poor

Build order recommendation:
  Phase 4 build sequence: FCHB first → ORB second → VWAP Pullback third
  FCHB and ORB are stateless once daily context is built.
  VWAP Pullback requires the watchlist state infrastructure.
  Build stateless strategies first to validate the signal framework,
  then add the stateful watchlist on top of proven infrastructure.
```

---

That is everything. Every trading rule, every condition, every threshold, every filter, every lifecycle rule we designed across all three strategies. Nothing left out.

The one thing not in here is the short strategy — that is Phase 2, designed separately after the long strategies prove profitable in simulation.