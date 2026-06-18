CREATE TABLE market_candles (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    timeframe TEXT NOT NULL,
    candle_time TEXT NOT NULL,
    open_price REAL NOT NULL,
    high_price REAL NOT NULL,
    low_price REAL NOT NULL,
    close_price REAL NOT NULL,
    volume INTEGER,
    open_interest INTEGER,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    is_finalized BOOLEAN NOT NULL DEFAULT TRUE,
    quality_status TEXT NOT NULL DEFAULT 'VALID',
    CONSTRAINT uq_market_candles_symbol_exchange_timeframe_time
        UNIQUE (symbol, exchange, timeframe, candle_time),
    CONSTRAINT ck_market_candles_exchange CHECK (exchange IN ('NSE')),
    CONSTRAINT ck_market_candles_timeframe CHECK (
        timeframe IN ('ONE_MINUTE', 'FIVE_MINUTE', 'FIFTEEN_MINUTE', 'THIRTY_MINUTE', 'ONE_HOUR', 'DAILY')
    ),
    CONSTRAINT ck_market_candles_quality_status CHECK (
        quality_status IN ('VALID', 'SUSPECT', 'RECOVERED')
    )
);

CREATE INDEX idx_market_candles_symbol_timeframe_time
    ON market_candles(symbol, timeframe, candle_time);

CREATE INDEX idx_market_candles_timeframe_time
    ON market_candles(timeframe, candle_time);

CREATE INDEX idx_market_candles_symbol_time
    ON market_candles(symbol, candle_time);