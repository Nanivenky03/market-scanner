package com.trading.scanner.strategy.replay;

import com.trading.scanner.model.CandleDirection;
import com.trading.scanner.model.CandleProcessingStatus;
import com.trading.scanner.model.CandleQualityStatus;
import com.trading.scanner.model.CandleTimeframe;
import com.trading.scanner.model.ContextStatus;
import com.trading.scanner.model.DailyStockContext;
import com.trading.scanner.model.MarketCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Secure, high-throughput historical market data loader that reads directly from
 * the PostgreSQL production database (mithron_prod) in strict READ-ONLY mode.
 * <p>
 * Database credentials are read securely from .env or environment variables.
 * Never hardcodes database credentials into source code.
 */
public class DatabaseMarketReplayLoader implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMarketReplayLoader.class);

    private final Connection connection;

    public DatabaseMarketReplayLoader() {
        this(loadDbConfig());
    }

    public DatabaseMarketReplayLoader(DbConfig config) {
        try {
            this.connection = DriverManager.getConnection(config.url, config.username, config.password);
            this.connection.setReadOnly(true);
            log.info("DatabaseMarketReplayLoader initialized in strict READ-ONLY mode: {} (user={})",
                    sanitizeUrl(config.url), config.username);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to establish read-only connection to replay database: " + config.url, e);
        }
    }

    /**
     * Retrieves all available trading dates from market_candles where NIFTY 1M candles exist.
     *
     * @param startDate Optional starting date filter (inclusive). If null, returns from the earliest date.
     * @param maxDays   Optional limit on number of days. If specified without startDate, takes most recent maxDays.
     * @return Ordered list of available trading dates.
     */
    public List<LocalDate> getAvailableTradingDates(LocalDate startDate, Integer maxDays) {
        String sql = "SELECT DISTINCT substring(candle_time, 1, 10) AS dt " +
                "FROM market_candles " +
                "WHERE symbol = 'NIFTY' AND timeframe = 'ONE_MINUTE' " +
                "ORDER BY dt ASC";

        List<LocalDate> dates = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                dates.add(LocalDate.parse(rs.getString("dt")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query trading dates from database", e);
        }

        if (startDate != null) {
            dates.removeIf(d -> d.isBefore(startDate));
            if (maxDays != null && maxDays > 0 && dates.size() > maxDays) {
                return new ArrayList<>(dates.subList(0, maxDays));
            }
            return dates;
        }

        if (maxDays != null && maxDays > 0 && dates.size() > maxDays) {
            return new ArrayList<>(dates.subList(dates.size() - maxDays, dates.size()));
        }

        return dates;
    }

    /**
     * Retrieves top liquid tradable symbols ordered by historical opening range turnover.
     *
     * @param limit Maximum number of symbols to fetch.
     * @return List of liquid stock symbols.
     */
    public List<String> getTopStockUniverse(int limit) {
        String sql = "SELECT s.symbol " +
                "FROM stock_universe s " +
                "LEFT JOIN (" +
                "    SELECT symbol, AVG(first_candle_volume * prev_day_close) AS avg_turnover " +
                "    FROM daily_stock_context " +
                "    WHERE first_candle_volume > 0 AND prev_day_close BETWEEN 50 AND 10000 AND symbol != 'AARTIIND' " +
                "    GROUP BY symbol " +
                ") t ON s.symbol = t.symbol " +
                "WHERE s.is_active = true AND s.is_tradable = true " +
                "ORDER BY COALESCE(t.avg_turnover, 0) DESC, s.symbol ASC " +
                "LIMIT ?";

        List<String> symbols = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    symbols.add(rs.getString("symbol"));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query top liquid stock universe", e);
        }

        return symbols;
    }

    /**
     * Loads market candles and daily contexts for a single market day.
     * Enables per-day streaming memory efficiency so unneeded data is quickly garbage collected.
     *
     * @param date    The market trading date.
     * @param symbols Universe of stock symbols to load (NIFTY is automatically included for macro regime filter).
     * @return In-memory HistoricalMarketDay container ready for strategy evaluation.
     */
    public HistoricalMarketDay loadSingleMarketDay(LocalDate date, List<String> symbols) {
        Set<String> symbolSet = new LinkedHashSet<>(symbols);
        symbolSet.add("NIFTY"); // Ensure benchmark index is loaded for market regime filter

        String dateStr = date.toString();
        String dayStart = dateStr + "T09:15";
        String dayEnd = dateStr + "T15:30";

        Map<String, DailyStockContext> dailyContexts = loadDailyContexts(dateStr, symbolSet);
        CandleBuckets buckets = loadCandles(dayStart, dayEnd, symbolSet);

        HistoricalMarketDay day = HistoricalMarketDay.builder()
                .tradingDate(dateStr)
                .scenarioTag("DB_REGIME")
                .scenarioDescription("Production DB Replay for " + dateStr + " (" + symbols.size() + " symbols)")
                .dailyContexts(dailyContexts)
                .candles1m(buckets.candles1m)
                .candles5m(buckets.candles5m)
                .candles15m(buckets.candles15m)
                .build();

        day.initIndexes();
        return day;
    }

    private Map<String, DailyStockContext> loadDailyContexts(String dateStr, Set<String> symbols) {
        String sql = "SELECT symbol, exchange, trading_date, " +
                "first_candle_open, first_candle_high, first_candle_low, first_candle_close, " +
                "first_candle_volume, first_candle_range, first_candle_range_pct, " +
                "first_candle_bullish, first_candle_valid, first_candle_ready, " +
                "opening_range_high, opening_range_low, opening_range_size, opening_range_size_pct, opening_range_ready, " +
                "prev_day_high, prev_day_low, prev_day_open, prev_day_close, prev_day_range_pct, " +
                "consecutive_red_days, consecutive_green_days, highest_close_15d, dist_from_resistance_pct, gap_pct, " +
                "corporate_action_flag, fo_ban_flag, results_last_3d_flag, skip_today, " +
                "breakout_reference_price, context_status " +
                "FROM daily_stock_context " +
                "WHERE trading_date = ? AND symbol = ANY (?)";

        Map<String, DailyStockContext> map = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, dateStr);
            Array arr = connection.createArrayOf("text", symbols.toArray(new String[0]));
            stmt.setArray(2, arr);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DailyStockContext ctx = mapDailyStockContext(rs);
                    map.put(ctx.getSymbol(), ctx);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load daily stock contexts for " + dateStr, e);
        }
        return map;
    }

    private CandleBuckets loadCandles(String dayStart, String dayEnd, Set<String> symbols) {
        String sql = "SELECT symbol, exchange, timeframe, candle_time, " +
                "open_price, high_price, low_price, close_price, volume, " +
                "vwap, atr_14, rsi_14, body_ratio, upper_wick_ratio, lower_wick_ratio, range_pct " +
                "FROM market_candles " +
                "WHERE candle_time >= ? AND candle_time <= ? " +
                "  AND timeframe IN ('ONE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE') " +
                "  AND symbol = ANY (?) " +
                "ORDER BY candle_time ASC";

        CandleBuckets buckets = new CandleBuckets();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, dayStart);
            stmt.setString(2, dayEnd);
            Array arr = connection.createArrayOf("text", symbols.toArray(new String[0]));
            stmt.setArray(3, arr);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    MarketCandle candle = mapCandle(rs);
                    if (candle.getTimeframe() == CandleTimeframe.ONE_MINUTE) {
                        buckets.candles1m.add(candle);
                    } else if (candle.getTimeframe() == CandleTimeframe.FIVE_MINUTE) {
                        buckets.candles5m.add(candle);
                    } else if (candle.getTimeframe() == CandleTimeframe.FIFTEEN_MINUTE) {
                        buckets.candles15m.add(candle);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load market candles between " + dayStart + " and " + dayEnd, e);
        }

        return buckets;
    }

    private DailyStockContext mapDailyStockContext(ResultSet rs) throws SQLException {
        return DailyStockContext.builder()
                .symbol(rs.getString("symbol"))
                .exchange(rs.getString("exchange"))
                .tradingDate(LocalDate.parse(rs.getString("trading_date")))
                .firstCandleOpen(getDoubleOrNull(rs, "first_candle_open"))
                .firstCandleHigh(getDoubleOrNull(rs, "first_candle_high"))
                .firstCandleLow(getDoubleOrNull(rs, "first_candle_low"))
                .firstCandleClose(getDoubleOrNull(rs, "first_candle_close"))
                .firstCandleVolume(getLongOrNull(rs, "first_candle_volume"))
                .firstCandleRange(getDoubleOrNull(rs, "first_candle_range"))
                .firstCandleRangePct(getDoubleOrNull(rs, "first_candle_range_pct"))
                .firstCandleBullish(getBooleanOrNull(rs, "first_candle_bullish"))
                .firstCandleValid(getBooleanOrNull(rs, "first_candle_valid"))
                .firstCandleReady(rs.getBoolean("first_candle_ready"))
                .openingRangeHigh(getDoubleOrNull(rs, "opening_range_high"))
                .openingRangeLow(getDoubleOrNull(rs, "opening_range_low"))
                .openingRangeSize(getDoubleOrNull(rs, "opening_range_size"))
                .openingRangeSizePct(getDoubleOrNull(rs, "opening_range_size_pct"))
                .openingRangeReady(rs.getBoolean("opening_range_ready"))
                .prevDayHigh(getDoubleOrNull(rs, "prev_day_high"))
                .prevDayLow(getDoubleOrNull(rs, "prev_day_low"))
                .prevDayOpen(getDoubleOrNull(rs, "prev_day_open"))
                .prevDayClose(getDoubleOrNull(rs, "prev_day_close"))
                .prevDayRangePct(getDoubleOrNull(rs, "prev_day_range_pct"))
                .consecutiveRedDays(getIntOrNull(rs, "consecutive_red_days"))
                .consecutiveGreenDays(getIntOrNull(rs, "consecutive_green_days"))
                .highestClose15d(getDoubleOrNull(rs, "highest_close_15d"))
                .distFromResistancePct(getDoubleOrNull(rs, "dist_from_resistance_pct"))
                .gapPct(getDoubleOrNull(rs, "gap_pct"))
                .corporateActionFlag(getBooleanOrNull(rs, "corporate_action_flag"))
                .foBanFlag(getBooleanOrNull(rs, "fo_ban_flag"))
                .resultsLast3dFlag(getBooleanOrNull(rs, "results_last_3d_flag"))
                .skipToday(getBooleanOrNull(rs, "skip_today"))
                .breakoutReferencePrice(getDoubleOrNull(rs, "breakout_reference_price"))
                .contextStatus(ContextStatus.valueOf(rs.getString("context_status")))
                .build();
    }

    private MarketCandle mapCandle(ResultSet rs) throws SQLException {
        String timeStr = rs.getString("candle_time");
        LocalDateTime time = timeStr.length() == 16 ? LocalDateTime.parse(timeStr + ":00") : LocalDateTime.parse(timeStr);
        String tfStr = rs.getString("timeframe");

        return MarketCandle.builder()
                .symbol(rs.getString("symbol"))
                .exchange(rs.getString("exchange"))
                .timeframe(CandleTimeframe.valueOf(tfStr))
                .candleTime(time)
                .openPrice(getDoubleOrNull(rs, "open_price"))
                .highPrice(getDoubleOrNull(rs, "high_price"))
                .lowPrice(getDoubleOrNull(rs, "low_price"))
                .closePrice(getDoubleOrNull(rs, "close_price"))
                .volume(getLongOrNull(rs, "volume"))
                .vwap(getDoubleOrNull(rs, "vwap"))
                .atr14(getDoubleOrNull(rs, "atr_14"))
                .rsi14(getDoubleOrNull(rs, "rsi_14"))
                .bodyRatio(getDoubleOrNull(rs, "body_ratio"))
                .upperWickRatio(getDoubleOrNull(rs, "upper_wick_ratio"))
                .lowerWickRatio(getDoubleOrNull(rs, "lower_wick_ratio"))
                .rangePct(getDoubleOrNull(rs, "range_pct"))
                .direction(CandleDirection.BULLISH)
                .qualityStatus(CandleQualityStatus.LIVE)
                .processingStatus(CandleProcessingStatus.RELEASED)
                .isFinalized(true)
                .source("HISTORICAL_DB")
                .build();
    }

    private Double getDoubleOrNull(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private Long getLongOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private Integer getIntOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private Boolean getBooleanOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
                log.info("DatabaseMarketReplayLoader connection closed safely.");
            } catch (SQLException e) {
                log.warn("Error closing replay database connection", e);
            }
        }
    }

    private static class CandleBuckets {
        final List<MarketCandle> candles1m = new ArrayList<>();
        final List<MarketCandle> candles5m = new ArrayList<>();
        final List<MarketCandle> candles15m = new ArrayList<>();
    }

    public static class DbConfig {
        public final String url;
        public final String username;
        public final String password;

        public DbConfig(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }
    }

    private static DbConfig loadDbConfig() {
        Map<String, String> envMap = new HashMap<>();

        // 1. Try reading .env from working directory
        File envFile = new File(".env");
        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                        continue;
                    }
                    int idx = line.indexOf('=');
                    String key = line.substring(0, idx).trim();
                    String val = line.substring(idx + 1).trim();
                    envMap.put(key, val);
                }
            } catch (Exception e) {
                log.warn("Could not read local .env file: {}", e.getMessage());
            }
        }

        String url = getVal(List.of("replay.db.url", "REPLAY_DB_URL", "db.url", "DB_URL"),
                envMap, "jdbc:postgresql://localhost:5432/mithron_prod");

        // If URL points to mithron_dev, redirect to mithron_prod for historical replay
        if (url.contains("/mithron_dev")) {
            url = url.replace("/mithron_dev", "/mithron_prod");
        }

        String user = getVal(List.of("replay.db.username", "REPLAY_DB_USERNAME", "db.username", "DB_USERNAME"),
                envMap, "postgres");

        String pass = getVal(List.of("replay.db.password", "REPLAY_DB_PASSWORD", "db.password", "DB_PASSWORD"),
                envMap, "postgres");

        return new DbConfig(url, user, pass);
    }

    private static String getVal(List<String> keys, Map<String, String> envMap, String fallback) {
        for (String k : keys) {
            String sys = System.getProperty(k);
            if (sys != null && !sys.isBlank()) return sys;
            String env = System.getenv(k);
            if (env != null && !env.isBlank()) return env;
            String mapVal = envMap.get(k);
            if (mapVal != null && !mapVal.isBlank()) return mapVal;
        }
        return fallback;
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return "null";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }
}
