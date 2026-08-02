ALTER TABLE daily_stock_context
ADD COLUMN opening_range_volume INTEGER;

CREATE TABLE volume_daily_baseline (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    trading_date TEXT NOT NULL,
    avg_daily_volume_20 INTEGER NOT NULL,
    sample_days INTEGER NOT NULL,
    computed_at TEXT NOT NULL,
    CONSTRAINT uq_volume_daily_baseline_symbol_exchange_trading_date
        UNIQUE (symbol, exchange, trading_date)
);

CREATE INDEX idx_volume_daily_baseline_trading_date
    ON volume_daily_baseline(trading_date);

CREATE INDEX idx_volume_daily_baseline_symbol_trading_date
    ON volume_daily_baseline(symbol, trading_date);

CREATE TABLE volume_time_window_baseline (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    trading_date TEXT NOT NULL,
    session_minute INTEGER NOT NULL,
    avg_cumulative_volume_20 INTEGER NOT NULL,
    sample_days INTEGER NOT NULL,
    computed_at TEXT NOT NULL,
    CONSTRAINT uq_volume_time_window_baseline_symbol_exchange_trading_date_minute
        UNIQUE (symbol, exchange, trading_date, session_minute)
);

CREATE INDEX idx_volume_time_window_baseline_trading_date_minute
    ON volume_time_window_baseline(trading_date, session_minute);

CREATE INDEX idx_volume_time_window_baseline_symbol_trading_date
    ON volume_time_window_baseline(symbol, trading_date);



