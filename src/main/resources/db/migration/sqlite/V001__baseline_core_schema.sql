CREATE TABLE stock_universe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL,
    company_name TEXT NOT NULL,
    sector TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_stock_universe_symbol_exchange UNIQUE (symbol, exchange),
    CONSTRAINT ck_stock_universe_exchange CHECK (exchange IN ('NSE'))
);

CREATE INDEX idx_stock_universe_active ON stock_universe(is_active);
CREATE INDEX idx_stock_universe_symbol ON stock_universe(symbol);

CREATE TABLE stock_prices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    date TEXT NOT NULL,
    open_price REAL,
    high_price REAL,
    low_price REAL,
    close_price REAL,
    adj_close REAL,
    volume INTEGER,
    CONSTRAINT uq_stock_prices_symbol_date UNIQUE (symbol, date)
);

CREATE INDEX idx_stock_prices_symbol_date ON stock_prices(symbol, date);
CREATE INDEX idx_stock_prices_date ON stock_prices(date);

CREATE TABLE scan_execution_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trading_date TEXT NOT NULL,
    ingestion_status TEXT NOT NULL,
    scan_status TEXT NOT NULL,
    data_source_status TEXT,
    execution_mode TEXT,
    last_ingestion_time TEXT,
    last_scan_time TEXT,
    stocks_ingested INTEGER NOT NULL DEFAULT 0,
    signals_generated INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    CONSTRAINT uq_scan_execution_state_trading_date UNIQUE (trading_date),
    CONSTRAINT ck_scan_execution_state_ingestion_status CHECK (
        ingestion_status IN ('PENDING', 'IN_PROGRESS', 'SUCCESS', 'SUCCESS_NO_DATA', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_scan_execution_state_scan_status CHECK (
        scan_status IN ('PENDING', 'IN_PROGRESS', 'SUCCESS', 'SUCCESS_NO_DATA', 'FAILED', 'SKIPPED')
    ),
    CONSTRAINT ck_scan_execution_state_data_source_status CHECK (
        data_source_status IS NULL OR data_source_status IN ('HEALTHY', 'NO_DATA', 'DEGRADED', 'UNAVAILABLE', 'UNKNOWN')
    ),
    CONSTRAINT ck_scan_execution_state_execution_mode CHECK (
        execution_mode IS NULL OR execution_mode IN ('MANUAL', 'SCHEDULED', 'API')
    )
);

CREATE INDEX idx_scan_execution_state_ingestion_status ON scan_execution_state(ingestion_status);
CREATE INDEX idx_scan_execution_state_scan_status ON scan_execution_state(scan_status);

CREATE TABLE scanner_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_date TEXT NOT NULL,
    stocks_scanned INTEGER NOT NULL DEFAULT 0,
    stocks_flagged INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    error_message TEXT
);

CREATE INDEX idx_scanner_runs_run_date ON scanner_runs(run_date);
CREATE INDEX idx_scanner_runs_status ON scanner_runs(status);

CREATE TABLE scan_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    scan_date TEXT NOT NULL,
    rule_name TEXT NOT NULL,
    confidence REAL NOT NULL,
    scanner_version TEXT NOT NULL,
    rule_version TEXT NOT NULL,
    parameter_snapshot TEXT NOT NULL,
    metadata TEXT,
    forward_return_7d REAL,
    forward_return_14d REAL,
    forward_return_30d REAL,
    CONSTRAINT uq_scan_results_signal_identity UNIQUE (symbol, scan_date, rule_name)
);

CREATE INDEX idx_scan_results_scan_date ON scan_results(scan_date);
CREATE INDEX idx_scan_results_symbol ON scan_results(symbol);
CREATE INDEX idx_scan_results_rule_name ON scan_results(rule_name);

CREATE TABLE signal_outcomes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    signal_id INTEGER NOT NULL,
    horizon_days INTEGER NOT NULL,
    entry_price REAL NOT NULL,
    exit_price REAL NOT NULL,
    forward_return REAL NOT NULL,
    mfe REAL,
    mae REAL,
    computed_at TEXT NOT NULL,
    CONSTRAINT uq_signal_outcomes_signal_horizon UNIQUE (signal_id, horizon_days),
    CONSTRAINT fk_signal_outcomes_signal
        FOREIGN KEY (signal_id) REFERENCES scan_results(id)
);

CREATE INDEX idx_signal_outcomes_signal_id ON signal_outcomes(signal_id);
CREATE INDEX idx_signal_outcomes_horizon_days ON signal_outcomes(horizon_days);

CREATE TABLE simulation_state (
    id INTEGER PRIMARY KEY,
    version INTEGER NOT NULL,
    base_date TEXT NOT NULL,
    trading_offset INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_simulation_state_singleton CHECK (id = 1)
);

CREATE TABLE emergency_closure (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    reason TEXT,
    created_at TEXT NOT NULL,
    CONSTRAINT uq_emergency_closure_date UNIQUE (date)
);

CREATE INDEX idx_emergency_closure_date ON emergency_closure(date);