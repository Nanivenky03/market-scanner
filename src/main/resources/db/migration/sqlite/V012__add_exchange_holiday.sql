CREATE TABLE exchange_holiday (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    exchange TEXT NOT NULL,
    trading_date TEXT NOT NULL,
    description TEXT,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT uq_exchange_holiday_exchange_date UNIQUE (exchange, trading_date)
);

CREATE INDEX idx_exchange_holiday_exchange
ON exchange_holiday(exchange);

CREATE INDEX idx_exchange_holiday_trading_date
ON exchange_holiday(trading_date);