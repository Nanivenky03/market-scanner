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

This is checked on every 5M candle close.
If Nifty crosses below VWAP mid-session, no new entries for rest of day.
Existing open positions are managed normally — they are not exited
just because Nifty crosses VWAP. Only new entries stop.
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
After 12:30 PM:        ORB signals stop
After 01:00 PM:        FCHB signals stop. VWAP Pullback continues.
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

## Position Sizing Formula

```
riskRupees = totalCapital × 0.015  (1.5% risk per trade)
riskPerShare = entryPrice - stopLossPrice
qtyRaw = riskRupees / riskPerShare
qtyFinal = floor(qtyRaw)

Hard minimum: qtyFinal must be >= 2
  Reason: first partial exit sells 50%. With qty=1 you cannot split.
  If qtyFinal < 2 → skip this trade entirely.

Example at Rs.10,000 capital:
  riskRupees = 10,000 × 0.015 = Rs.150
  If entry = Rs.500, stop = Rs.496:
    riskPerShare = Rs.4
    qtyRaw = 150 / 4 = 37.5
    qtyFinal = 37
```

---

## R:R Check After Charges

```
estimatedCharges = Rs.60 per round trip (buy + sell combined)
  Use SmartAPI estimateCharges if available.
  Fall back to Rs.60 flat if API unavailable.

netRisk = riskRupees + estimatedCharges
netRewardAt1R = riskRupees - estimatedCharges

rrAfterCharges = netRewardAt1R / netRisk

If rrAfterCharges < 1.5 → skip this trade
  At Rs.10,000 capital this filters very tight stops where
  charges eat more than 1/3 of the risk budget.
```

---

## Confidence Scoring System

Every signal is scored 0 to 100 before entry decision.

```
BO_% score (how far above breakout reference):
  0.0 to 0.4%  → 25 points
  0.4 to 0.8%  → 15 points
  0.8 to 1.0%  → 5 points
  > 1.0%       → 0 points (do not enter)

VOL_X score:
  Above 2.5    → 25 points
  1.8 to 2.5   → 15 points
  1.5 to 1.8   → 5 points
  Below 1.5    → 0 points (do not enter)

RSI score (for 5M RSI at signal time):
  Ideal range center (55-65) → 25 points
  Acceptable range (65-70)   → 15 points
  At boundary (70-72)        → 5 points
  Outside range              → 0 points (do not enter)

Nifty VWAP margin score:
  Nifty > VWAP by 0.3% or more → 15 points
  Nifty just above VWAP         → 5 points
  Nifty below VWAP              → master filter already blocked this

Dual timeframe alignment:
  Signal appears on both 5M and 15M simultaneously → 10 points

Total possible: 100 points

Thresholds:
  Trades 1, 2, 3 (standard): score >= 60 required
  Trades 4, 5 (high confidence only): score >= 80 required
```

---

## Risk Engine — Five Checks Before Every Trade

All five must pass. Any single failure → skip trade, no exceptions.

```
Check 1: Open positions < 3
  Maximum 3 concurrent open positions at any time.

Check 2: Total current risk < 3.2% of capital
  Calculated using CURRENT trailing stop levels, not original stops.
  As trailing stop moves up, risk on that position decreases.
  3.2% not 4% — 0.8% buffer for simultaneous position changes.

Check 3: Daily loss < 4% of capital
  Cumulative realized + unrealized loss.
  If >= 4%: kill switch fires, no new trades for rest of day.
  Existing positions managed normally.

Check 4: Total trades today < 5
  Maximum 5 trades per day across all three strategies combined.
  Trades 1-3: standard (score >= 60)
  Trades 4-5: high confidence only (score >= 80)

Check 5: R:R after charges >= 1.5
  Calculated using estimated charges for this specific trade.
```

---

## Universal Trade Lifecycle

Applies identically to all three strategies after entry.

```
Entry price = 0R reference point
Initial stop loss = -1R

Price reaches 0.8R:
  Move stop loss UP to entry price (0R = breakeven)
  Worst case from this point forward = zero loss
  Stop never comes back down from here

Price reaches 1R:
  EXIT 50% of position at market
  Remaining 50% stop stays at entry price (0R)
  ATR-based trailing stop begins on remaining 50%

Trailing stop on remaining 50% (ATR-based):
  On every 1M candle close that is HIGHER than previous:
    new_stop = that_candle_low - (0.5 × 5M_ATR)
    Only update if new_stop > current trailing stop
    Stop never moves down, only up

Price hits stop at any stage:
  Market order exit, full remaining quantity
  GTT on Angel One server executes if application is down

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
After signal confirmed on 5M candle close:
  Wait for next 1M candle to close in signal direction
  If 1M confirmation candle is green → proceed
  If 1M confirmation candle is red → signal cancelled, skip

Entry order type: LIMIT order
Entry price: confirmation candle high + 0.1%
  This avoids chasing — if price has already run far above,
  limit order does not fill and trade is skipped

Auto-cancel: if limit order not filled within 2 minutes → cancel
  Do not hold open limit orders — they can fill at wrong moment

On fill:
  Immediately calculate exact stop loss price
  Immediately place GTT stop loss order on Angel One server
  GTT is your safety net if application crashes or loses connection
  GTT must be updated every time trailing stop moves up
```

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

Done before signals can fire. Stored in daily_stock_context.

```
Uses five 1M candles: 09:15, 09:16, 09:17, 09:18, 09:19

firstCandleOpen   = open of 09:15 1M candle
firstCandleHigh   = max(high) across all 5 candles
firstCandleLow    = min(low) across all 5 candles
firstCandleClose  = close of 09:19 1M candle
firstCandleVolume = sum(volume) across all 5 candles

firstCandleRangePct = (high - low) / low × 100
firstCandleBullish  = close > open (strict greater than)
```

**First candle quality filter (if any fail → FCHB suspended for this stock today):**
```
firstCandleRangePct < 0.15%: range too tight, level meaningless
firstCandleRangePct > 3.0%:  chaotic opening, stop too wide
firstCandleVolume < 50,000:  insufficient participation
firstCandleBullish == false: bearish or doji first candle
                              negative opening sentiment
Any of the 5 candles missing: not ready, invalid
```

---

## Entry Signal Detection (5M candle close, 09:30 AM to 01:00 PM)

```
ALL of these must be true:

1. 5M candle closes ABOVE firstCandleHigh

2. BO_% from first candle high <= 0.8%
   = (close - firstCandleHigh) / firstCandleHigh × 100
   If > 0.8%: too far above the level, chasing

3. Signal candle volume > 2.0 × firstCandleVolume
   This comparison is specific to FCHB — different from VOL_X
   The signal candle must show more than twice the participation
   of the opening itself

4. VOL_X (time-normalized) >= 1.8
   Volume also above 20-day time-normalized average

5. RSI on 5M between 55 and 72

6. Stock close > stock VWAP

7. 15M context: price above 15M VWAP (trend support)

8. Signal candle is strong bullish:
   bodyRatio >= 0.60, upperWickRatio <= 0.25, rangePct >= 0.15%

9. Nifty above VWAP (master filter)

10. Signal only valid until 01:00 PM
    After 01:00 PM: first candle reference loses intraday relevance

11. Confidence score >= 60

→ Signal confirmed
→ Wait for 1M confirmation candle to close green above firstCandleHigh
→ Place limit buy at 1M confirmation candle high + 0.1%
→ Auto-cancel if not filled within 2 minutes
```

---

## Stop Loss

```
Stop loss price = firstCandleLow × 0.995

Rationale: if price closes back inside the first candle range,
the breakout has completely failed. The first candle established
a range; breaking back into it means buyers could not hold the level.

Early exit trigger:
  If 1M candle CLOSES back inside the first candle range
  (below firstCandleHigh and the candle close is within the range)
  → Exit at market IMMEDIATELY
  Do not wait for stop loss level to be hit.
  The breakout has failed and early exit saves more money.

GTT placed at firstCandleLow × 0.995 on Angel One immediately after fill.
```

---

## Targets

```
Target 1 (first exit, 50% of position):
  Entry + 1R
  1R = entry price - stop loss price

Target 2 (remaining 50%):
  ATR-based trailing stop
  Trail begins after 50% exits at 1R
  Follow 1M candle lows minus 0.5 × 5M ATR

Minimum R:R gate before entering:
  firstCandleRange must provide at least 1:1.5 R:R after charges.
  If first candle range is too small (stop too close to entry),
  the trade does not meet the minimum. Skip it.
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