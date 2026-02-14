# SIMULATION MODE - USER GUIDE

## 🎯 **WHAT IS SIMULATION MODE?**

Simulation Mode allows you to **replay historical trading days** without waiting for real calendar time.

**Use Cases:**
- Stress-test the scanner with historical data
- Validate ingestion pipeline
- Test indicator calculations
- Verify rule engine behavior
- Accelerate learning and iteration

**NOT for:**
- Production trading
- Live signal generation
- Backtesting (Phase 2 feature)

---

## 🔐 **SAFETY GUARANTEES**

### **Complete Isolation**

✅ **Separate Database**
- Production: `market_scanner_prod.db`
- Simulation: `market_scanner_sim.db`
- Zero cross-contamination risk

✅ **Separate Profiles**
- Production: Uses system clock
- Simulation: Uses controllable clock
- Cannot run both simultaneously

✅ **Scheduler Disabled**
- No automatic execution in simulation
- Manual control only via API

✅ **Forward-Only Timeline**
- Cannot rewind time
- Protects indicator correctness
- Prevents temporal paradoxes

---

## 🚀 **QUICK START**

### **1. Create Simulation Database**

```bash
# Initialize simulation database
sqlite3 data/market_scanner_sim.db < scripts/init_db.sql

# Add simulation state table
sqlite3 data/market_scanner_sim.db < scripts/migration_simulation.sql
```

### **2. Run in Simulation Mode**

```bash
# Build
mvn clean package

# Run with simulation profile
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation
```

**Application starts with:**
- Simulation database
- Fixed clock at base date (2023-01-01 by default)
- Scheduler disabled
- Simulation endpoints enabled

### **3. Check Simulation Status**

```bash
curl http://localhost:8080/simulation/status

# Response:
{
  "baseDate": "2023-01-01",
  "offsetDays": 0,
  "currentDate": "2023-01-01"
}
```

### **4. Load Historical Data**

Visit http://localhost:8080 and click **"Load Historical Data"**

- This loads data into simulation database
- Takes 20-30 minutes (same as production)
- Only needed once

### **5. Execute Simulation Steps**

```bash
# Execute step (ingest + scan for current date)
curl -X POST http://localhost:8080/simulation/step

# Response:
{
  "success": true,
  "currentDate": "2023-01-01",
  "message": "Simulation step completed"
}
```

### **6. Advance Timeline**

```bash
# Advance by one day
curl -X POST http://localhost:8080/simulation/advance-day

# Response:
{
  "success": true,
  "previousDate": "2023-01-01",
  "currentDate": "2023-01-02",
  "offsetDays": 1,
  "message": "Simulation advanced. Restart app to load new date."
}
```

### **7. Restart App**

```bash
# Stop app (Ctrl+C)

# Restart with simulation profile
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation
```

**App loads with new date (2023-01-02)**

### **8. Repeat Steps 5-7**

Each cycle:
1. Execute step (ingest + scan)
2. Advance day
3. Restart app
4. Repeat

---

## ⚙️ **CONFIGURATION**

### **application-simulation.properties**

```properties
# Database (separate from production)
spring.datasource.url=jdbc:sqlite:data/market_scanner_sim.db

# Simulation base date (starting point)
simulation.baseDate=2023-01-01

# Exchange timezone
exchange.timezone=Asia/Kolkata

# Historical reload (enabled for simulation)
scanner.allowHistoricalReload=true
scanner.historical.reload.confirm=true

# Provider settings (same as production)
provider.publishBufferHours=3
provider.circuitBreaker.failureThreshold=5
```

---

## 🔧 **SIMULATION ENDPOINTS**

### **GET /simulation/status**

Get current simulation state

**Response:**
```json
{
  "baseDate": "2023-01-01",
  "offsetDays": 5,
  "currentDate": "2023-01-06"
}
```

### **POST /simulation/step**

Execute full simulation step for current date:
1. Ingest daily data
2. Execute scanner
3. Generate signals

**Response:**
```json
{
  "success": true,
  "currentDate": "2023-01-06",
  "message": "Simulation step completed"
}
```

### **POST /simulation/advance-day**

Advance simulation timeline by one day

**Response:**
```json
{
  "success": true,
  "previousDate": "2023-01-06",
  "currentDate": "2023-01-07",
  "offsetDays": 6,
  "message": "Simulation advanced. Restart app to load new date."
}
```

**NOTE:** App restart required after advancing

### **POST /simulation/reset**

Reset simulation to base date

**⚠️ DANGEROUS - Only for testing**

**Response:**
```json
{
  "success": true,
  "currentDate": "2023-01-01",
  "message": "Simulation reset to base date. Restart app."
}
```

---

## 📋 **TYPICAL WORKFLOW**

### **Scenario: Test Scanner Over 30 Days**

```bash
# 1. Initial setup (one-time)
sqlite3 data/market_scanner_sim.db < scripts/init_db.sql
sqlite3 data/market_scanner_sim.db < scripts/migration_simulation.sql

# 2. Start app
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation

# 3. Load historical data (one-time, 30 min)
# Via UI: http://localhost:8080 → "Load Historical Data"

# 4. For each day (repeat 30 times):

# Execute step for current date
curl -X POST http://localhost:8080/simulation/step

# Check results in database
sqlite3 data/market_scanner_sim.db "SELECT * FROM scan_results WHERE scan_date = '2023-01-01';"

# Advance to next day
curl -X POST http://localhost:8080/simulation/advance-day

# Restart app
# (Stop with Ctrl+C, then run again)
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation

# 5. After 30 days, analyze results:
sqlite3 data/market_scanner_sim.db "SELECT COUNT(*) FROM scan_results;"
sqlite3 data/market_scanner_sim.db "SELECT scan_date, COUNT(*) FROM scan_results GROUP BY scan_date;"
```

---

## 🔍 **VERIFICATION**

### **Confirm Simulation Mode Active**

```bash
# Check logs
tail -f data/logs/scanner_simulation.log

# Should see:
# SIMULATION MODE: Using fixed clock at 2023-01-01
# SIMULATION MODE: Base date: 2023-01-01, Offset: 0 days
```

### **Verify Database Separation**

```bash
# Production DB
sqlite3 data/market_scanner_prod.db "SELECT COUNT(*) FROM stock_prices;"

# Simulation DB  
sqlite3 data/market_scanner_sim.db "SELECT COUNT(*) FROM stock_prices;"

# Should be different (or one empty if new)
```

### **Check Simulation State**

```bash
sqlite3 data/market_scanner_sim.db "SELECT * FROM simulation_state;"

# Should show:
# 1|2023-01-01|5  (if offset_days = 5)
```

---

## ⚠️ **LIMITATIONS & KNOWN ISSUES**

### **App Restart Required**

**Current Implementation:**
- ExchangeClock is initialized at startup with fixed instant
- Advancing timeline updates database only
- Clock remains fixed until restart

**Workaround:**
- Restart app after each `advance-day` call

**Future Enhancement (V2):**
- Dynamic clock that reads SimulationState on each call
- No restart required

### **Provider Rate Limiting**

Even in simulation:
- Yahoo Finance API has rate limits
- 500ms delay between stocks (configurable)
- Ingestion still takes 2-3 minutes for 50 stocks

### **Weekend / Holiday Data**

Provider may return empty for:
- Saturdays
- Sundays
- Exchange holidays

**Behavior:**
- System marks as `SUCCESS_NO_DATA`
- Scanner skips (no data to analyze)
- This is correct - matches production

---

## 📊 **MONITORING**

### **Logs**

```bash
# Simulation logs
tail -f data/logs/scanner_simulation.log

# Watch for:
# - SIMULATION MODE indicators
# - Current simulated date
# - Ingestion success/failure
# - Scanner execution
# - Signal generation
```

### **Database Queries**

```bash
# Execution state
sqlite3 data/market_scanner_sim.db \
  "SELECT trading_date, ingestion_status, scan_status, stocks_ingested, signals_generated 
   FROM scan_execution_state ORDER BY trading_date DESC LIMIT 10;"

# Signals generated
sqlite3 data/market_scanner_sim.db \
  "SELECT scan_date, symbol, rule_name, confidence 
   FROM scan_results ORDER BY scan_date DESC LIMIT 20;"

# Simulation timeline
sqlite3 data/market_scanner_sim.db \
  "SELECT base_date, offset_days, 
   date(base_date, '+' || offset_days || ' days') as current_date 
   FROM simulation_state;"
```

---

## 🎯 **TESTING SCENARIOS**

### **1. Validate Ingestion Pipeline**

**Goal:** Ensure self-healing gap detection works

**Steps:**
1. Start at 2023-01-01
2. Execute step
3. Advance to 2023-01-02
4. Advance to 2023-01-05 (skip 3-4)
5. Execute step
6. Verify backfill of 2023-01-03, 2023-01-04

### **2. Test Scanner Over Market Events**

**Goal:** See how scanner behaves during volatile periods

**Steps:**
1. Set base date to known volatile period (e.g., 2023-03-01)
2. Run 30-day simulation
3. Analyze signal frequency
4. Check confidence scores

### **3. Stress Test Circuit Breaker**

**Goal:** Verify circuit breaker opens on failures

**Steps:**
1. Disable internet connection
2. Execute step
3. Verify circuit breaker opens
4. Check CRITICAL logs
5. Re-enable internet
6. Verify recovery

---

## 🔄 **SWITCHING BETWEEN MODES**

### **Production → Simulation**

```bash
# 1. Stop production app

# 2. Start simulation
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation
```

### **Simulation → Production**

```bash
# 1. Stop simulation app

# 2. Start production (no profile or explicit production)
java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=production

# Or simply:
java -jar target/market-scanner-1.2.0-PRODUCTION.jar
```

**Databases remain separate - no data loss**

---

## 📈 **SIMULATION BEST PRACTICES**

### **1. Start with Clean Simulation DB**

```bash
# Reset simulation database
rm data/market_scanner_sim.db
sqlite3 data/market_scanner_sim.db < scripts/init_db.sql
sqlite3 data/market_scanner_sim.db < scripts/migration_simulation.sql
```

### **2. Choose Appropriate Base Date**

- Recent dates: Less historical data needed
- Older dates: More data for backtesting
- Avoid weekends as base date
- Consider market volatility periods

### **3. Batch Simulation Runs**

```bash
#!/bin/bash
# sim_30days.sh - Run 30-day simulation

for i in {1..30}; do
  echo "Day $i"
  curl -X POST http://localhost:8080/simulation/step
  curl -X POST http://localhost:8080/simulation/advance-day
  # Restart app
  pkill -f scanner
  sleep 5
  java -jar target/market-scanner-1.2.0-PRODUCTION.jar --spring.profiles.active=simulation &
  sleep 30  # Wait for startup
done
```

### **4. Save Simulation Results**

```bash
# Export results
sqlite3 data/market_scanner_sim.db \
  ".mode csv" \
  ".headers on" \
  ".output simulation_results.csv" \
  "SELECT * FROM scan_results;" \
  ".quit"
```

---

## ✅ **VERIFICATION CHECKLIST**

Before running simulation:

- [ ] Simulation database created
- [ ] simulation_state table exists
- [ ] Historical data loaded
- [ ] App started with simulation profile
- [ ] Scheduler NOT running (check logs)
- [ ] Simulation endpoints accessible

During simulation:

- [ ] Current date advances correctly
- [ ] Ingestion executes
- [ ] Scanner executes
- [ ] Signals generated
- [ ] State persists across restarts

After simulation:

- [ ] Results in simulation database
- [ ] Production database unchanged
- [ ] Can switch back to production cleanly

---

## 🎯 **SUMMARY**

**Simulation Mode enables:**
- ✅ Accelerated testing without waiting for calendar time
- ✅ Safe experimentation (separate database)
- ✅ Stress testing of ingestion pipeline
- ✅ Validation of scanner behavior
- ✅ Controlled, reproducible scenarios

**Current Limitation:**
- App restart required after advancing timeline

**Future Enhancement (V2):**
- Dynamic clock for restart-free simulation

**Production Safety:**
- ✅ Complete isolation
- ✅ Zero risk to production data
- ✅ Separate profiles prevent cross-contamination

---

**Version:** 1.2.0-SIMULATION  
**Status:** Ready for testing  
**Safety:** Production-isolated

🚀 **Start simulating and accelerate your learning!**
