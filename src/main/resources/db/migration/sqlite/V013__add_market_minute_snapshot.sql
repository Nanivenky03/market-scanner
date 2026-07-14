CREATE TABLE market_minute_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    minute_time TEXT NOT NULL,
    latest_tick_time TEXT NOT NULL,
    trading_date TEXT NOT NULL,
    broker_token TEXT,
    subscription_mode INTEGER,
    exchange_type INTEGER,
    last_price REAL,
    last_traded_quantity INTEGER,
    average_traded_price REAL,
    volume_traded_for_day INTEGER,
    total_buy_quantity INTEGER,
    total_sell_quantity INTEGER,
    open_interest INTEGER,
    open_interest_change_percent REAL,
    upper_circuit_limit REAL,
    lower_circuit_limit REAL,
    fifty_two_week_high_price REAL,
    fifty_two_week_low_price REAL,
    last_traded_timestamp_epoch INTEGER,
    exchange_timestamp_epoch INTEGER,
    sequence_number INTEGER,
    is_finalized INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT uq_market_minute_snapshot_symbol_exchange_minute
        UNIQUE (symbol, exchange, minute_time)
);

CREATE INDEX idx_market_minute_snapshot_trading_date_minute
    ON market_minute_snapshot(trading_date, minute_time);

CREATE INDEX idx_market_minute_snapshot_updated_at
    ON market_minute_snapshot(updated_at);

CREATE INDEX idx_market_minute_snapshot_broker_token_minute
    ON market_minute_snapshot(broker_token, minute_time);