-- V036: Create Stock Sector Classification Schema (Zero SQL defaults, Java domain managed)

CREATE TABLE IF NOT EXISTS stock_sector_classification (
    id BIGSERIAL PRIMARY KEY,
    symbol TEXT NOT NULL UNIQUE,
    company_name TEXT,
    macro_sector TEXT NOT NULL,
    sector_index_symbol TEXT NOT NULL,
    industry TEXT,
    isin TEXT,
    is_active BOOLEAN NOT NULL,
    source TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_stock_sector_symbol ON stock_sector_classification(symbol);
CREATE INDEX IF NOT EXISTS idx_stock_sector_index ON stock_sector_classification(sector_index_symbol);
