# 🎯 Market Intelligence Engine

**Professional Stock Scanner for Confirmed Breakout Setups**

V1.0 - Production Ready

---

## 📋 Overview

This is a production-grade market scanning system designed for systematic discretionary trading. It automatically scans 100 high-quality Indian stocks (Nifty 50 + Next 50) daily and identifies confirmed breakout setups using strict rule-based criteria.

**Philosophy:** Quality signals. Calm execution. Evidence-based evolution.

---

## ✨ Features

- ✅ **Strict Breakout Detection** - Filters noise, delivers 2-5 high-quality signals per week
- ✅ **Automated Daily Scanning** - Runs at 7 PM IST automatically
- ✅ **Professional Dashboard** - Clean UI to review signals
- ✅ **Historical Data Storage** - Builds learning database for pattern analysis
- ✅ **Rule-Based Engine** - No ML complexity, pure structural analysis
- ✅ **SQLite Database** - Zero maintenance, runs locally
- ✅ **Free Data Source** - Yahoo Finance (no subscriptions needed)

---

## 🚀 Quick Start (30 Minutes)

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Internet connection (for data fetching)
- 2GB disk space minimum

### Installation

**1. Clone/Download this project**

**2. Navigate to project directory**
```bash
cd market-scanner
```

**3. Initialize Database**
```bash
sqlite3 data/market_scanner.db < scripts/init_db.sql
```

**4. Build the project**
```bash
mvn clean package
```

**5. Run the application**
```bash
java -jar target/market-scanner-1.0.0.jar
```

**6. Open browser**
```
http://localhost:8080
```

**7. Load Historical Data (ONE-TIME SETUP)**

Click "Load Historical Data" button on dashboard. This will:
- Fetch 5 years of price data for all 100 stocks
- Take 15-30 minutes depending on internet speed
- Required for scanner to work properly

**8. Run First Scan**

After historical data loads, click "Run Scanner Now" button.

---

## 📊 Daily Workflow

### Automated (Recommended)

Scanner runs automatically at **7:00 PM IST** every day.

### Morning Routine (15 minutes)

1. Open dashboard at http://localhost:8080
2. Review signals flagged from previous night
3. Click "View Chart" for each signal
4. Apply manual judgment
5. Make trade decisions

---

## 🔧 Configuration

### Scanner Settings

Edit `src/main/resources/scanner-config.yaml`:

```yaml
scanner:
  mode: strict  # strict | ultra_strict
  
  rules:
    strict:
      volume_threshold: 1.4          # Volume spike required
      compression_days_min: 10       # Minimum compression period
      atr_compression_ratio: 0.70    # ATR shrinkage threshold
      breakout_buffer: 1.01          # Price breakout buffer (1%)
      close_in_range_top: 0.80       # Strong close requirement
```

### Schedule

Edit `src/main/resources/application.properties`:

```properties
scanner.schedule.cron=0 0 19 * * *  # 7 PM daily
```

Cron format: `second minute hour day month weekday`

Examples:
- `0 0 19 * * *` = Every day 7 PM
- `0 30 18 * * *` = Every day 6:30 PM
- `0 0 9 * * MON-FRI` = Weekdays 9 AM

---

## 📂 Project Structure

```
market-scanner/
├── src/
│   ├── main/
│   │   ├── java/com/trading/scanner/
│   │   │   ├── model/              # Database entities
│   │   │   ├── repository/         # Data access layer
│   │   │   ├── service/
│   │   │   │   ├── data/           # Yahoo Finance provider
│   │   │   │   ├── indicators/     # SMA, ATR, Volume calculators
│   │   │   │   └── scanner/        # Scanner engine & rules
│   │   │   ├── controller/         # Web dashboard
│   │   │   └── scheduler/          # Daily automation
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── scanner-config.yaml
│   │       └── templates/dashboard.html
│   └── test/                       # Unit tests
├── data/
│   ├── market_scanner.db           # SQLite database
│   └── logs/scanner.log            # Application logs
├── scripts/
│   └── init_db.sql                 # Database initialization
├── pom.xml                         # Maven dependencies
└── README.md                       # This file
```

---

## 🎯 Scanner Rules (STRICT MODE)

### BREAKOUT_CONFIRMED

A stock must satisfy ALL conditions:

1. **Price Breakout**
   - Close > 20-day high × 1.01 (1% buffer)

2. **Strong Close**
   - Close in top 20% of daily range

3. **Volume Confirmation**
   - Today's volume > 1.4× average volume (20-day)

4. **Volatility Compression**
   - ATR compressed for 10+ days
   - Current ATR < 70% of ATR from 30 days ago

5. **Trend Alignment**
   - Price > 50 SMA
   - 50 SMA > 200 SMA (Golden Cross)
   - 50 SMA trending up (slope > 0)

6. **Liquidity Filter**
   - Average volume > 100,000 shares

### Confidence Grading

- **HIGH**: Volume ratio ≥ 1.8 AND Compression ≥ 15 days
- **MODERATE**: Volume ratio ≥ 1.4 AND Compression ≥ 10 days

---

## 📈 Expected Performance

**Signal Frequency:**
- Strict Mode: 2-5 signals per week
- ~10-20 signals per month

**Win Rate Target:**
- 60-70% after manual validation
- Remember: Quality > Quantity

**False Positives:**
- Some signals will fail (markets are probabilistic)
- Use historical scan results to learn patterns
- Refine judgment over time

---

## 🗄️ Database Queries

### Today's Breakouts
```sql
SELECT symbol, confidence, metadata 
FROM scan_results 
WHERE scan_date = date('now') 
AND classification = 'BREAKOUT_CONFIRMED'
ORDER BY confidence DESC;
```

### Stock History
```sql
SELECT scan_date, classification, confidence 
FROM scan_results 
WHERE symbol = 'RELIANCE' 
ORDER BY scan_date DESC 
LIMIT 30;
```

### Scanner Performance
```sql
SELECT run_date, stocks_scanned, stocks_flagged, execution_time_ms
FROM scanner_runs 
ORDER BY run_date DESC 
LIMIT 30;
```

---

## 🔍 Troubleshooting

### Scanner finds 0 signals

**Normal.** Markets don't always provide perfect setups. Be patient.

### Data fetch fails for some stocks

**Yahoo Finance can be unreliable.** Check logs. Re-run data ingestion.

### Database locked error

**SQLite issue.** Stop application, restart.

### High memory usage

**Large dataset.** Increase JVM heap: `java -Xmx2G -jar target/market-scanner-1.0.0.jar`

---

## 📝 Logs

Application logs are in:
```
data/logs/scanner.log
```

Scanner execution details:
```bash
tail -f data/logs/scanner.log
```

---

## 🎓 Learning & Evolution

### Phase 1 (Months 1-3): Data Gathering

- Run scanner daily
- Document every trade decision
- Build pattern recognition
- **Goal:** 30-50 total signals

### Phase 2 (Months 4-6): Refinement

- Analyze which setups worked
- Consider tightening to Ultra Strict mode
- Scale position sizing
- **Goal:** High-conviction execution

### Phase 3 (Months 7+): Mastery

- You understand your edge
- Calm systematic execution
- Evidence-based rule tuning
- **Goal:** Consistent profitability

---

## ⚠️ Important Reminders

1. **Scanner finds setups. You make decisions.**
   - Apply manual judgment
   - Check charts visually
   - Consider market context

2. **Not all signals are trades**
   - Be selective
   - Skip low-conviction setups
   - Quality over quantity

3. **Position sizing matters**
   - Risk 1-2% per trade
   - Limit simultaneous positions
   - Capital preservation first

4. **Backtest limitations**
   - Survivorship bias exists
   - Past performance ≠ future results
   - Use for pattern learning only

---

## 🔧 Customization

### Add More Stocks

Edit `scripts/init_db.sql` and add to `stock_universe` table:

```sql
INSERT INTO stock_universe (symbol, company_name, index_name, sector) VALUES
('NEWSTOCK', 'New Company Ltd', 'NIFTYNEXT50', 'Sector');
```

Then re-run init script or manually insert.

### Change Scanner Strictness

Edit `scanner-config.yaml`:

```yaml
scanner:
  mode: ultra_strict  # More strict (fewer signals)
```

Or

```yaml
scanner:
  mode: strict  # Default (balanced)
```

### Add New Rules

1. Create new class in `service/scanner/rules/`
2. Implement `ScannerRule` interface
3. Add `@Component` annotation
4. Spring will auto-detect it

---

## 🚀 Production Deployment

### Option 1: Local Machine (Recommended for V1)

Keep running on your laptop/desktop. Zero cloud costs.

### Option 2: Cloud Server

Deploy to Oracle Cloud Free Tier:
- 1 VM instance (Always Free)
- 24/7 uptime
- SSH access

Steps:
1. Create Oracle Cloud account
2. Launch Ubuntu VM
3. Install Java 17
4. Copy project files
5. Run as systemd service

---

## 📊 API Endpoints

### Get Today's Results (JSON)
```
GET http://localhost:8080/api/results/today
```

### Health Check
```
GET http://localhost:8080/health
```

### Trigger Manual Scan
```
POST http://localhost:8080/scan/trigger
```

---

## 🛡️ Risk Management

**Implement these BEFORE going live:**

1. **Fixed Risk Per Trade**
   - Example: 1% of capital
   - Calculate position size accordingly

2. **Max Positions**
   - Example: 3-5 simultaneous trades
   - Prevents overexposure

3. **Portfolio Heat Limit**
   - Example: 5% total risk
   - Sum of all position risks

4. **Stop Losses**
   - ALWAYS use stops
   - Set before entry
   - Never move against yourself

**Remember:** Scanner finds opportunity. Risk model ensures survival.

---

## 📚 Additional Resources

- **Technical Analysis:** "Technical Analysis of Financial Markets" by John Murphy
- **Trading Psychology:** "Trading in the Zone" by Mark Douglas
- **Risk Management:** "The New Market Wizards" by Jack Schwager
- **Code:** Spring Boot Documentation (spring.io)

---

## 🤝 Support

For issues:
1. Check logs in `data/logs/scanner.log`
2. Review this README
3. Verify database exists and has data

---

## 📜 License

This is a personal trading tool. Use at your own risk.

**No warranty. No guarantees. Markets are probabilistic.**

---

## 🎯 Final Words

This is not a get-rich-quick tool.

This is **infrastructure for systematic discretionary trading.**

Success requires:
- Patience
- Discipline
- Continuous learning
- Proper risk management

**Trade small. Learn fast. Build edge.**

**Good luck. 🚀**

---

**Version:** 1.0.0  
**Last Updated:** February 2026  
**Status:** Production Ready
