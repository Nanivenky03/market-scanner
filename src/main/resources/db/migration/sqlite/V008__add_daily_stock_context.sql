CREATE TABLE daily_stock_context (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    trading_date TEXT NOT NULL,

    first_candle_open REAL,
    first_candle_high REAL,
    first_candle_low REAL,
    first_candle_close REAL,
    first_candle_volume INTEGER,
    first_candle_range REAL,
    first_candle_range_pct REAL,
    first_candle_ready BOOLEAN NOT NULL DEFAULT FALSE,

    opening_range_high REAL,
    opening_range_low REAL,
    opening_range_size REAL,
    opening_range_size_pct REAL,
    opening_range_ready BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    CONSTRAINT uq_daily_stock_context UNIQUE (symbol, exchange, trading_date)
);

CREATE INDEX idx_daily_stock_context_trading_date
ON daily_stock_context(trading_date);

CREATE INDEX idx_daily_stock_context_symbol_date
ON daily_stock_context(symbol, trading_date);