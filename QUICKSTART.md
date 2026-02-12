# ⚡ QUICK START GUIDE

## First Time Setup (30 minutes)

### 1. Prerequisites Check
```bash
java -version    # Must be 17+
mvn -version     # Must be 3.6+
```

### 2. Build Project
```bash
cd market-scanner
mvn clean package
```

### 3. Initialize Database
```bash
mkdir -p data/logs
sqlite3 data/market_scanner.db < scripts/init_db.sql
```

### 4. Start Application
```bash
java -jar target/market-scanner-1.0.0.jar
```

Wait for: `Started ScannerApplication in X seconds`

### 5. Open Dashboard
```
http://localhost:8080
```

### 6. Load Historical Data
Click "Load Historical Data (First Time Setup)" button

**IMPORTANT:** This takes 15-30 minutes. DON'T close the application.

Watch logs:
```bash
tail -f data/logs/scanner.log
```

### 7. First Scan
After historical data completes, click "Run Scanner Now"

---

## Daily Usage (5 minutes)

### Morning Workflow

1. Open http://localhost:8080
2. Review overnight signals
3. For each signal:
   - Click "View Chart"
   - Apply manual judgment
   - Make trade decision

### Manual Scan
```bash
# Open http://localhost:8080
# Click "Run Scanner Now" button
```

---

## Common Commands

### Start Application
```bash
java -jar target/market-scanner-1.0.0.jar
```

### Start with More Memory
```bash
java -Xmx2G -jar target/market-scanner-1.0.0.jar
```

### View Logs
```bash
tail -f data/logs/scanner.log
```

### Check Database
```bash
sqlite3 data/market_scanner.db
sqlite> SELECT COUNT(*) FROM stock_universe;
sqlite> SELECT COUNT(*) FROM stock_prices;
sqlite> .exit
```

---

## Troubleshooting

### "Port 8080 already in use"
```bash
# Change port in application.properties
server.port=8081
```

### "Database is locked"
```bash
# Stop application
# Delete lock files
rm data/market_scanner.db-shm
rm data/market_scanner.db-wal
# Restart
```

### "No signals found"
This is normal. Scanner is strict. Be patient.

### "Data fetch failed"
Yahoo Finance can be flaky. Check internet. Try again later.

---

## Configuration Quick Reference

### Change Scan Time
Edit `application.properties`:
```properties
scanner.schedule.cron=0 0 19 * * *
```

### Make Scanner More Strict
Edit `scanner-config.yaml`:
```yaml
scanner:
  mode: ultra_strict
```

### Make Scanner Less Strict
Edit `scanner-config.yaml`:
```yaml
scanner:
  mode: strict
  rules:
    strict:
      volume_threshold: 1.3  # Lower from 1.4
      compression_days_min: 8  # Lower from 10
```

---

## Database Queries

### Today's Breakouts
```sql
sqlite3 data/market_scanner.db
SELECT symbol, confidence FROM scan_results 
WHERE scan_date = date('now') 
AND classification = 'BREAKOUT_CONFIRMED';
```

### Recent Scans
```sql
SELECT run_date, stocks_flagged FROM scanner_runs 
ORDER BY run_date DESC LIMIT 10;
```

---

## Next Steps After V1

1. **Run for 3 months**
   - Gather 30-50 signals
   - Learn patterns
   - Build intuition

2. **Analyze Results**
   - Which setups worked?
   - What failed?
   - Patterns emerging?

3. **Refine Rules**
   - Tighten thresholds if too noisy
   - Add forward return tracking
   - Consider ultra_strict mode

4. **Advanced Features**
   - Add compression watch list
   - Implement weak structure filter
   - AI summaries (Phase 2)

---

## Production Checklist

Before going live with real money:

- [ ] Scanner running reliably for 1 month
- [ ] Understood all signals
- [ ] Validated rules on charts
- [ ] Risk management defined
- [ ] Position sizing calculated
- [ ] Stop loss strategy set
- [ ] Trade journal ready
- [ ] Emotional discipline tested

---

## Support

1. Check `README.md` for detailed documentation
2. Review logs in `data/logs/scanner.log`
3. Verify database has data
4. Test with small positions first

---

**Remember:**
- Start small
- Learn systematically
- Build edge over time
- Markets reward patience

**Good luck! 🚀**
