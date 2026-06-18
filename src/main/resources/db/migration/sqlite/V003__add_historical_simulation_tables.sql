CREATE TABLE simulation_run_group (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    from_date TEXT NOT NULL,
    to_date TEXT NOT NULL,
    created_at TEXT NOT NULL,
    completed_at TEXT,
    status TEXT NOT NULL,
    notes TEXT,
    created_by TEXT,
    CONSTRAINT ck_simulation_run_group_status CHECK (
        status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX idx_simulation_run_group_status ON simulation_run_group(status);
CREATE INDEX idx_simulation_run_group_created_at ON simulation_run_group(created_at);

CREATE TABLE simulation_variant (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_group_id INTEGER NOT NULL,
    strategy_id TEXT NOT NULL,
    strategy_version TEXT NOT NULL,
    strategy_display_name TEXT NOT NULL,
    strategy_status TEXT NOT NULL,
    timeframe TEXT NOT NULL,
    variant_key TEXT NOT NULL,
    variant_name TEXT NOT NULL,
    simulation_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    live_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    capital_per_trade REAL NOT NULL DEFAULT 10000,
    slippage_bps INTEGER NOT NULL DEFAULT 0,
    charge_per_trade REAL NOT NULL DEFAULT 0,
    min_trading_days INTEGER NOT NULL DEFAULT 20,
    min_trade_count INTEGER NOT NULL DEFAULT 10,
    created_at TEXT NOT NULL,
    completed_at TEXT,
    status TEXT NOT NULL,
    total_trades INTEGER NOT NULL DEFAULT 0,
    winning_trades INTEGER NOT NULL DEFAULT 0,
    losing_trades INTEGER NOT NULL DEFAULT 0,
    gross_pnl REAL NOT NULL DEFAULT 0,
    net_pnl REAL NOT NULL DEFAULT 0,
    notes TEXT,
    CONSTRAINT uq_simulation_variant_group_key UNIQUE (run_group_id, variant_key),
    CONSTRAINT fk_simulation_variant_group FOREIGN KEY (run_group_id) REFERENCES simulation_run_group(id),
    CONSTRAINT ck_simulation_variant_status CHECK (
        status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    )
);

CREATE INDEX idx_simulation_variant_run_group_id ON simulation_variant(run_group_id);
CREATE INDEX idx_simulation_variant_strategy_id ON simulation_variant(strategy_id);
CREATE INDEX idx_simulation_variant_status ON simulation_variant(status);

CREATE TABLE simulation_trade (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    variant_id INTEGER NOT NULL,
    symbol TEXT NOT NULL,
    signal_date TEXT NOT NULL,
    entry_date TEXT NOT NULL,
    exit_date TEXT,
    entry_price REAL NOT NULL,
    exit_price REAL,
    quantity INTEGER NOT NULL,
    initial_stop_loss REAL,
    final_stop_loss REAL,
    initial_target REAL,
    final_target REAL,
    day_close_price REAL,
    confidence REAL,
    gross_pnl REAL,
    net_pnl REAL,
    fees REAL,
    trade_status TEXT NOT NULL DEFAULT 'OPEN',
    trade_result TEXT NOT NULL DEFAULT 'UNKNOWN',
    exit_reason TEXT,
    entry_context TEXT,
    exit_context TEXT,
    evaluation_context TEXT,
    created_at TEXT NOT NULL,
    CONSTRAINT fk_simulation_trade_variant FOREIGN KEY (variant_id) REFERENCES simulation_variant(id),
    CONSTRAINT ck_simulation_trade_status CHECK (
        trade_status IN ('OPEN', 'CLOSED')
    ),
    CONSTRAINT ck_simulation_trade_result CHECK (
        trade_result IN ('WIN', 'LOSS', 'FLAT', 'UNKNOWN')
    )
);

CREATE INDEX idx_simulation_trade_variant_id ON simulation_trade(variant_id);
CREATE INDEX idx_simulation_trade_symbol ON simulation_trade(symbol);
CREATE INDEX idx_simulation_trade_signal_date ON simulation_trade(signal_date);
CREATE INDEX idx_simulation_trade_status ON simulation_trade(trade_status);