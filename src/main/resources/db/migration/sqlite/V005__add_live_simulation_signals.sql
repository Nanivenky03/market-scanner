CREATE TABLE live_simulation_signal (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    timeframe TEXT NOT NULL,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    candle_time TEXT NOT NULL,
    signal_date TEXT NOT NULL,
    lifecycle_status TEXT NOT NULL,
    confirmation_required BOOLEAN NOT NULL DEFAULT FALSE,
    score REAL NOT NULL,
    close_price REAL NOT NULL,
    previous_day_high REAL NOT NULL,
    breakout_percent REAL NOT NULL,
    volume_ratio REAL NOT NULL,
    rsi REAL NOT NULL,
    vwap REAL NOT NULL,
    close_strength REAL NOT NULL,
    signal_context TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT uq_live_signal_identity UNIQUE (strategy_id, symbol, timeframe, candle_time)
);

CREATE INDEX idx_live_signal_created_at ON live_simulation_signal(created_at);
CREATE INDEX idx_live_signal_symbol_timeframe ON live_simulation_signal(symbol, timeframe);
CREATE INDEX idx_live_signal_status ON live_simulation_signal(lifecycle_status);