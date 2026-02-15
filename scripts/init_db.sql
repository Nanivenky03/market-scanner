-- Market Scanner Database Schema v1.3-STABILIZATION

CREATE TABLE IF NOT EXISTS stock_universe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    exchange TEXT NOT NULL DEFAULT 'NSE',
    company_name TEXT NOT NULL,
    sector TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    UNIQUE(symbol, exchange)
);

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

INSERT OR IGNORE INTO stock_universe (symbol, exchange, company_name, sector) VALUES
('RELIANCE', 'NSE', 'Reliance Industries', 'Energy'),
('TCS', 'NSE', 'Tata Consultancy Services', 'IT'),
('HDFCBANK', 'NSE', 'HDFC Bank', 'Banking'),
('INFY', 'NSE', 'Infosys', 'IT'),
('HINDUNILVR', 'NSE', 'Hindustan Unilever', 'FMCG'),
('ICICIBANK', 'NSE', 'ICICI Bank', 'Banking'),
('SBIN', 'NSE', 'State Bank of India', 'Banking'),
('BHARTIARTL', 'NSE', 'Bharti Airtel', 'Telecom'),
('KOTAKBANK', 'NSE', 'Kotak Mahindra Bank', 'Banking'),
('ITC', 'NSE', 'ITC Limited', 'FMCG'),
('LT', 'NSE', 'Larsen & Toubro', 'Infrastructure'),
('AXISBANK', 'NSE', 'Axis Bank', 'Banking'),
('BAJFINANCE', 'NSE', 'Bajaj Finance', 'Finance'),
('ASIANPAINT', 'NSE', 'Asian Paints', 'Paints'),
('MARUTI', 'NSE', 'Maruti Suzuki', 'Auto'),
('HCLTECH', 'NSE', 'HCL Technologies', 'IT'),
('WIPRO', 'NSE', 'Wipro', 'IT'),
('ULTRACEMCO', 'NSE', 'UltraTech Cement', 'Cement'),
('TITAN', 'NSE', 'Titan Company', 'Consumer'),
('SUNPHARMA', 'NSE', 'Sun Pharma', 'Pharma'),
('NESTLEIND', 'NSE', 'Nestle India', 'FMCG'),
('TATAMOTORS', 'NSE', 'Tata Motors', 'Auto'),
('TATASTEEL', 'NSE', 'Tata Steel', 'Metals'),
('POWERGRID', 'NSE', 'Power Grid Corp', 'Power'),
('NTPC', 'NSE', 'NTPC', 'Power'),
('ONGC', 'NSE', 'ONGC', 'Energy'),
('COALINDIA', 'NSE', 'Coal India', 'Mining'),
('M&M', 'NSE', 'Mahindra & Mahindra', 'Auto'),
('BAJAJFINSV', 'NSE', 'Bajaj Finserv', 'Finance'),
('TECHM', 'NSE', 'Tech Mahindra', 'IT'),
('ADANIPORTS', 'NSE', 'Adani Ports', 'Infrastructure'),
('GODREJCP', 'NSE', 'Godrej Consumer', 'FMCG'),
('INDIGO', 'NSE', 'InterGlobe Aviation', 'Aviation'),
('SIEMENS', 'NSE', 'Siemens', 'Engineering'),
('DLF', 'NSE', 'DLF', 'Real Estate'),
('GAIL', 'NSE', 'GAIL India', 'Gas'),
('ABB', 'NSE', 'ABB India', 'Engineering'),
('PIDILITIND', 'NSE', 'Pidilite Industries', 'Chemicals'),
('HAVELLS', 'NSE', 'Havells India', 'Consumer'),
('BERGEPAINT', 'NSE', 'Berger Paints', 'Paints'),
('AMBUJACEM', 'NSE', 'Ambuja Cements', 'Cement'),
('ACC', 'NSE', 'ACC Limited', 'Cement'),
('TATACONSUM', 'NSE', 'Tata Consumer', 'FMCG'),
('DIVISLAB', 'NSE', 'Divis Laboratories', 'Pharma'),
('DRREDDY', 'NSE', 'Dr Reddys Labs', 'Pharma'),
('CIPLA', 'NSE', 'Cipla', 'Pharma'),
('TORNTPHARM', 'NSE', 'Torrent Pharma', 'Pharma'),
('APOLLOHOSP', 'NSE', 'Apollo Hospitals', 'Healthcare'),
('GRASIM', 'NSE', 'Grasim Industries', 'Cement'),
('VEDL', 'NSE', 'Vedanta', 'Metals');
