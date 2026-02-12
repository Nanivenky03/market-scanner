-- Market Intelligence Engine Database Schema v1.1
-- SQLite Database Initialization Script
-- Progressive Quant Architecture - Future Ready

-- Enable WAL mode for better concurrency
PRAGMA journal_mode=WAL;

-- Stock Universe Table
CREATE TABLE IF NOT EXISTS stock_universe (
    symbol TEXT PRIMARY KEY,
    company_name TEXT NOT NULL,
    index_name TEXT NOT NULL,
    sector TEXT,
    is_active INTEGER DEFAULT 1,
    added_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_index_name ON stock_universe(index_name);
CREATE INDEX IF NOT EXISTS idx_is_active ON stock_universe(is_active);

-- Stock Prices Table
CREATE TABLE IF NOT EXISTS stock_prices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    symbol TEXT NOT NULL,
    date TEXT NOT NULL,
    open REAL NOT NULL,
    high REAL NOT NULL,
    low REAL NOT NULL,
    close REAL NOT NULL,
    volume INTEGER NOT NULL,
    adj_close REAL NOT NULL,
    data_source TEXT DEFAULT 'yahoo',
    ingested_at TEXT DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(symbol, date)
);

CREATE INDEX IF NOT EXISTS idx_symbol_date ON stock_prices(symbol, date);
CREATE INDEX IF NOT EXISTS idx_date ON stock_prices(date);
CREATE INDEX IF NOT EXISTS idx_symbol ON stock_prices(symbol);

-- Scan Results Table (Enhanced with forward returns and versioning)
CREATE TABLE IF NOT EXISTS scan_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_date TEXT NOT NULL,
    symbol TEXT NOT NULL,
    classification TEXT NOT NULL,
    confidence TEXT DEFAULT 'MODERATE',
    metadata TEXT,
    
    -- Scanner versioning (institutional standard)
    scanner_version TEXT,
    
    -- Forward return tracking (Phase 2 capability)
    forward_return_7d REAL,
    forward_return_14d REAL,
    forward_return_30d REAL,
    outcome_updated_at TEXT,
    
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(scan_date, symbol, classification)
);

CREATE INDEX IF NOT EXISTS idx_scan_date ON scan_results(scan_date);
CREATE INDEX IF NOT EXISTS idx_classification ON scan_results(classification);
CREATE INDEX IF NOT EXISTS idx_symbol_scan ON scan_results(symbol, scan_date);
CREATE INDEX IF NOT EXISTS idx_scanner_version ON scan_results(scanner_version);

-- Scanner Runs Table
CREATE TABLE IF NOT EXISTS scanner_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    run_date TEXT NOT NULL,
    status TEXT NOT NULL,
    stocks_scanned INTEGER,
    stocks_flagged INTEGER,
    errors TEXT,
    execution_time_ms INTEGER,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_run_date ON scanner_runs(run_date);

-- Insert Initial Stock Universe (Nifty 50 + Next 50)
INSERT OR IGNORE INTO stock_universe (symbol, company_name, index_name, sector) VALUES
-- NIFTY 50
('RELIANCE', 'Reliance Industries Ltd', 'NIFTY50', 'Energy'),
('TCS', 'Tata Consultancy Services Ltd', 'NIFTY50', 'IT'),
('HDFCBANK', 'HDFC Bank Ltd', 'NIFTY50', 'Banking'),
('INFY', 'Infosys Ltd', 'NIFTY50', 'IT'),
('HINDUNILVR', 'Hindustan Unilever Ltd', 'NIFTY50', 'FMCG'),
('ICICIBANK', 'ICICI Bank Ltd', 'NIFTY50', 'Banking'),
('SBIN', 'State Bank of India', 'NIFTY50', 'Banking'),
('BHARTIARTL', 'Bharti Airtel Ltd', 'NIFTY50', 'Telecom'),
('KOTAKBANK', 'Kotak Mahindra Bank Ltd', 'NIFTY50', 'Banking'),
('ITC', 'ITC Ltd', 'NIFTY50', 'FMCG'),
('LT', 'Larsen & Toubro Ltd', 'NIFTY50', 'Infrastructure'),
('AXISBANK', 'Axis Bank Ltd', 'NIFTY50', 'Banking'),
('BAJFINANCE', 'Bajaj Finance Ltd', 'NIFTY50', 'NBFC'),
('ASIANPAINT', 'Asian Paints Ltd', 'NIFTY50', 'Consumer Durables'),
('MARUTI', 'Maruti Suzuki India Ltd', 'NIFTY50', 'Automobile'),
('HCLTECH', 'HCL Technologies Ltd', 'NIFTY50', 'IT'),
('WIPRO', 'Wipro Ltd', 'NIFTY50', 'IT'),
('ULTRACEMCO', 'UltraTech Cement Ltd', 'NIFTY50', 'Cement'),
('TITAN', 'Titan Company Ltd', 'NIFTY50', 'Consumer Durables'),
('SUNPHARMA', 'Sun Pharmaceutical Industries Ltd', 'NIFTY50', 'Pharma'),
('NESTLEIND', 'Nestle India Ltd', 'NIFTY50', 'FMCG'),
('TATAMOTORS', 'Tata Motors Ltd', 'NIFTY50', 'Automobile'),
('TATASTEEL', 'Tata Steel Ltd', 'NIFTY50', 'Metals'),
('POWERGRID', 'Power Grid Corporation Ltd', 'NIFTY50', 'Power'),
('NTPC', 'NTPC Ltd', 'NIFTY50', 'Power'),
('ONGC', 'Oil and Natural Gas Corporation', 'NIFTY50', 'Energy'),
('COALINDIA', 'Coal India Ltd', 'NIFTY50', 'Mining'),
('M&M', 'Mahindra & Mahindra Ltd', 'NIFTY50', 'Automobile'),
('BAJAJFINSV', 'Bajaj Finserv Ltd', 'NIFTY50', 'NBFC'),
('TECHM', 'Tech Mahindra Ltd', 'NIFTY50', 'IT'),

-- NIFTY NEXT 50
('ADANIPORTS', 'Adani Ports and SEZ Ltd', 'NIFTYNEXT50', 'Infrastructure'),
('GODREJCP', 'Godrej Consumer Products Ltd', 'NIFTYNEXT50', 'FMCG'),
('INDIGO', 'InterGlobe Aviation Ltd', 'NIFTYNEXT50', 'Aviation'),
('SIEMENS', 'Siemens Ltd', 'NIFTYNEXT50', 'Capital Goods'),
('DLF', 'DLF Ltd', 'NIFTYNEXT50', 'Real Estate'),
('GAIL', 'GAIL (India) Ltd', 'NIFTYNEXT50', 'Energy'),
('ABB', 'ABB India Ltd', 'NIFTYNEXT50', 'Capital Goods'),
('PIDILITIND', 'Pidilite Industries Ltd', 'NIFTYNEXT50', 'Chemicals'),
('HAVELLS', 'Havells India Ltd', 'NIFTYNEXT50', 'Consumer Durables'),
('BERGEPAINT', 'Berger Paints India Ltd', 'NIFTYNEXT50', 'Consumer Durables'),
('AMBUJACEM', 'Ambuja Cements Ltd', 'NIFTYNEXT50', 'Cement'),
('ACC', 'ACC Ltd', 'NIFTYNEXT50', 'Cement'),
('TATACONSUM', 'Tata Consumer Products Ltd', 'NIFTYNEXT50', 'FMCG'),
('DIVISLAB', 'Divi''s Laboratories Ltd', 'NIFTYNEXT50', 'Pharma'),
('DRREDDY', 'Dr. Reddy''s Laboratories Ltd', 'NIFTYNEXT50', 'Pharma'),
('CIPLA', 'Cipla Ltd', 'NIFTYNEXT50', 'Pharma'),
('TORNTPHARM', 'Torrent Pharmaceuticals Ltd', 'NIFTYNEXT50', 'Pharma'),
('APOLLOHOSP', 'Apollo Hospitals Enterprise Ltd', 'NIFTYNEXT50', 'Healthcare'),
('GRASIM', 'Grasim Industries Ltd', 'NIFTYNEXT50', 'Cement'),
('VEDL', 'Vedanta Ltd', 'NIFTYNEXT50', 'Metals');

-- Add more stocks to reach ~100 total as needed

-- Verification
SELECT 'Database initialized. Stock universe count: ' || COUNT(*) as status FROM stock_universe;
