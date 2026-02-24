-- Market Scanner Database Schema v1.3-STABILIZATION



CREATE TABLE IF NOT EXISTS stock_prices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    date TEXT NOT NULL,
    open_price REAL,
    high_price REAL,
    low_price REAL,
    close_price REAL,
    adj_close REAL,
    volume INTEGER,
    UNIQUE(symbol, date)
);

CREATE INDEX IF NOT EXISTS idx_symbol_date ON stock_prices(symbol, date);
CREATE INDEX IF NOT EXISTS idx_date ON stock_prices(date);

CREATE TABLE IF NOT EXISTS scan_execution_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    trading_date TEXT NOT NULL UNIQUE,
    ingestion_status TEXT NOT NULL,
    scan_status TEXT NOT NULL,
    data_source_status TEXT,
    execution_mode TEXT,
    last_ingestion_time TEXT,
    last_scan_time TEXT,
    stocks_ingested INTEGER,
    signals_generated INTEGER,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS scanner_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_date TEXT NOT NULL,
    stocks_scanned INTEGER,
    stocks_flagged INTEGER,
    status TEXT,
    error_message TEXT
);

CREATE TABLE IF NOT EXISTS scan_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    scan_date TEXT NOT NULL,
    rule_name TEXT NOT NULL,
    confidence REAL,
    scanner_version TEXT,
    rule_version TEXT,
    parameter_snapshot TEXT,
    metadata TEXT,
    forward_return_7d REAL,
    forward_return_14d REAL,
    forward_return_30d REAL
);

CREATE INDEX IF NOT EXISTS idx_scan_date ON scan_results(scan_date);


CREATE UNIQUE INDEX IF NOT EXISTS idx_signal_identity ON scan_results(symbol, scan_date, rule_name);


CREATE TABLE IF NOT EXISTS emergency_closure (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL UNIQUE,
    reason TEXT,
    created_at TEXT NOT NULL
);


CREATE TABLE IF NOT EXISTS stock_universe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL UNIQUE,
    exchange TEXT NOT NULL,
    company_name TEXT,
    sector TEXT,
    is_active BOOLEAN DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_symbol_universe ON stock_universe(symbol);
INSERT OR IGNORE INTO stock_universe (symbol, exchange, company_name, sector, is_active) VALUES
('RELIANCE', 'NSE', 'Reliance Industries', 'Energy', 1),
('TCS', 'NSE', 'Tata Consultancy Services', 'IT', 1),
('HDFCBANK', 'NSE', 'HDFC Bank', 'Banking', 1),
('INFY', 'NSE', 'Infosys', 'IT', 1),
('HINDUNILVR', 'NSE', 'Hindustan Unilever', 'FMCG', 1),
('ICICIBANK', 'NSE', 'ICICI Bank', 'Banking', 1),
('SBIN', 'NSE', 'State Bank of India', 'Banking', 1),
('BHARTIARTL', 'NSE', 'Bharti Airtel', 'Telecom', 1),
('KOTAKBANK', 'NSE', 'Kotak Mahindra Bank', 'Banking', 1),
('ITC', 'NSE', 'ITC Limited', 'FMCG', 1),
('LT', 'NSE', 'Larsen & Toubro', 'Infrastructure', 1),
('AXISBANK', 'NSE', 'Axis Bank', 'Banking', 1),
('BAJFINANCE', 'NSE', 'Bajaj Finance', 'Finance', 1),
('ASIANPAINT', 'NSE', 'Asian Paints', 'Paints', 1),
('MARUTI', 'NSE', 'Maruti Suzuki', 'Auto', 1),
('HCLTECH', 'NSE', 'HCL Technologies', 'IT', 1),
('WIPRO', 'NSE', 'Wipro', 'IT', 1),
('ULTRACEMCO', 'NSE', 'UltraTech Cement', 'Cement', 1),
('TITAN', 'NSE', 'Titan Company', 'Consumer', 1),
('SUNPHARMA', 'NSE', 'Sun Pharma', 'Pharma', 1),
('NESTLEIND', 'NSE', 'Nestle India', 'FMCG', 1),
('TATAMOTORS', 'NSE', 'Tata Motors', 'Auto', 1),
('TATASTEEL', 'NSE', 'Tata Steel', 'Metals', 1),
('POWERGRID', 'NSE', 'Power Grid Corp', 'Power', 1),
('NTPC', 'NSE', 'NTPC', 'Power', 1),
('ONGC', 'NSE', 'ONGC', 'Energy', 1),
('COALINDIA', 'NSE', 'Coal India', 'Mining', 1),
('M&M', 'NSE', 'Mahindra & Mahindra', 'Auto', 1),
('BAJAJFINSV', 'NSE', 'Bajaj Finserv', 'Finance', 1),
('TECHM', 'NSE', 'Tech Mahindra', 'IT', 1),
('ADANIPORTS', 'NSE', 'Adani Ports', 'Infrastructure', 1),
('GODREJCP', 'NSE', 'Godrej Consumer', 'FMCG', 1),
('INDIGO', 'NSE', 'InterGlobe Aviation', 'Aviation', 1),
('SIEMENS', 'NSE', 'Siemens', 'Engineering', 1),
('DLF', 'NSE', 'DLF', 'Real Estate', 1),
('GAIL', 'NSE', 'GAIL India', 'Gas', 1),
('ABB', 'NSE', 'ABB India', 'Engineering', 1),
('PIDILITIND', 'NSE', 'Pidilite Industries', 'Chemicals', 1),
('HAVELLS', 'NSE', 'Havells India', 'Consumer', 1),
('BERGEPAINT', 'NSE', 'Berger Paints', 'Paints', 1),
('AMBUJACEM', 'NSE', 'Ambuja Cements', 'Cement', 1),
('ACC', 'NSE', 'ACC Limited', 'Cement', 1),
('TATACONSUM', 'NSE', 'Tata Consumer', 'FMCG', 1),
('DIVISLAB', 'NSE', 'Divis Laboratories', 'Pharma', 1),
('DRREDDY', 'NSE', 'Dr Reddys Labs', 'Pharma', 1),
('CIPLA', 'NSE', 'Cipla', 'Pharma', 1),
('TORNTPHARM', 'NSE', 'Torrent Pharma', 'Pharma', 1),
('APOLLOHOSP', 'NSE', 'Apollo Hospitals', 'Healthcare', 1),
('GRASIM', 'NSE', 'Grasim Industries', 'Cement', 1),
('VEDL', 'NSE', 'Vedanta', 'Metals', 1);
-- Simulation State Table (Single-row table for stateful simulation)
CREATE TABLE IF NOT EXISTS simulation_state (
    id INTEGER PRIMARY KEY,
    version INTEGER,
    base_date TEXT NOT NULL,
    trading_offset INTEGER NOT NULL DEFAULT 0,
    is_cycling INTEGER NOT NULL DEFAULT 0,
    cycling_started_at TEXT
);

-- Initialize simulation state
INSERT OR IGNORE INTO simulation_state (id, version, base_date, trading_offset, is_cycling)
VALUES (1, 0, date('now'), 0, 0);
