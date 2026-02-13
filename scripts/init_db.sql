-- Market Scanner Database Schema v1.2-PRODUCTION

CREATE TABLE IF NOT EXISTS stock_universe (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL UNIQUE,
    company_name TEXT NOT NULL,
    sector TEXT,
    is_active BOOLEAN DEFAULT TRUE
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
    metadata TEXT,
    forward_return_7d REAL,
    forward_return_14d REAL,
    forward_return_30d REAL
);

CREATE INDEX IF NOT EXISTS idx_scan_date ON scan_results(scan_date);

INSERT OR IGNORE INTO stock_universe (symbol, company_name, sector) VALUES
('RELIANCE', 'Reliance Industries', 'Energy'),
('TCS', 'Tata Consultancy Services', 'IT'),
('HDFCBANK', 'HDFC Bank', 'Banking'),
('INFY', 'Infosys', 'IT'),
('HINDUNILVR', 'Hindustan Unilever', 'FMCG'),
('ICICIBANK', 'ICICI Bank', 'Banking'),
('SBIN', 'State Bank of India', 'Banking'),
('BHARTIARTL', 'Bharti Airtel', 'Telecom'),
('KOTAKBANK', 'Kotak Mahindra Bank', 'Banking'),
('ITC', 'ITC Limited', 'FMCG'),
('LT', 'Larsen & Toubro', 'Infrastructure'),
('AXISBANK', 'Axis Bank', 'Banking'),
('BAJFINANCE', 'Bajaj Finance', 'Finance'),
('ASIANPAINT', 'Asian Paints', 'Paints'),
('MARUTI', 'Maruti Suzuki', 'Auto'),
('HCLTECH', 'HCL Technologies', 'IT'),
('WIPRO', 'Wipro', 'IT'),
('ULTRACEMCO', 'UltraTech Cement', 'Cement'),
('TITAN', 'Titan Company', 'Consumer'),
('SUNPHARMA', 'Sun Pharma', 'Pharma'),
('NESTLEIND', 'Nestle India', 'FMCG'),
('TATAMOTORS', 'Tata Motors', 'Auto'),
('TATASTEEL', 'Tata Steel', 'Metals'),
('POWERGRID', 'Power Grid Corp', 'Power'),
('NTPC', 'NTPC', 'Power'),
('ONGC', 'ONGC', 'Energy'),
('COALINDIA', 'Coal India', 'Mining'),
('M&M', 'Mahindra & Mahindra', 'Auto'),
('BAJAJFINSV', 'Bajaj Finserv', 'Finance'),
('TECHM', 'Tech Mahindra', 'IT'),
('ADANIPORTS', 'Adani Ports', 'Infrastructure'),
('GODREJCP', 'Godrej Consumer', 'FMCG'),
('INDIGO', 'InterGlobe Aviation', 'Aviation'),
('SIEMENS', 'Siemens', 'Engineering'),
('DLF', 'DLF', 'Real Estate'),
('GAIL', 'GAIL India', 'Gas'),
('ABB', 'ABB India', 'Engineering'),
('PIDILITIND', 'Pidilite Industries', 'Chemicals'),
('HAVELLS', 'Havells India', 'Consumer'),
('BERGEPAINT', 'Berger Paints', 'Paints'),
('AMBUJACEM', 'Ambuja Cements', 'Cement'),
('ACC', 'ACC Limited', 'Cement'),
('TATACONSUM', 'Tata Consumer', 'FMCG'),
('DIVISLAB', 'Divis Laboratories', 'Pharma'),
('DRREDDY', 'Dr Reddys Labs', 'Pharma'),
('CIPLA', 'Cipla', 'Pharma'),
('TORNTPHARM', 'Torrent Pharma', 'Pharma'),
('APOLLOHOSP', 'Apollo Hospitals', 'Healthcare'),
('GRASIM', 'Grasim Industries', 'Cement'),
('VEDL', 'Vedanta', 'Metals');
