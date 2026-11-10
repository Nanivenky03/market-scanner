-- V037: EOD Backfill, Automated Backup, and Dual-Broker Runtime Hardening

-- 1. Seed Dual-Broker Settings (Live vs Paper)
INSERT INTO runtime_setting (name, value, value_type, description, is_active, updated_at)
SELECT 'trading.broker.live', 'KOTAK_NEO', 'STRING', 'Active broker model for live real-money order routing and statutory friction (KOTAK_NEO, ANGEL_ONE)', TRUE, now()::text
WHERE NOT EXISTS (SELECT 1 FROM runtime_setting WHERE name = 'trading.broker.live');

INSERT INTO runtime_setting (name, value, value_type, description, is_active, updated_at)
SELECT 'trading.broker.paper', 'KOTAK_NEO', 'STRING', 'Active broker model for simulation and paper trading order routing and statutory friction (KOTAK_NEO, ANGEL_ONE)', TRUE, now()::text
WHERE NOT EXISTS (SELECT 1 FROM runtime_setting WHERE name = 'trading.broker.paper');

-- 2. Seed Baseline Simulation Capital Setting
INSERT INTO runtime_setting (name, value, value_type, description, is_active, updated_at)
SELECT 'simulation.initial.capital', '50000.00', 'DOUBLE', 'Default baseline opening capital in INR for paper trading simulation', TRUE, now()::text
WHERE NOT EXISTS (SELECT 1 FROM runtime_setting WHERE name = 'simulation.initial.capital');

-- 3. Extend Historical Data Retention to 60 Days
UPDATE runtime_setting
SET value = '60', updated_at = now()::text
WHERE name = 'retention.candles.days';

UPDATE runtime_setting
SET value = '60', updated_at = now()::text
WHERE name = 'retention.live.signals.days';

-- 4. Optimize Historical Lookback Index
CREATE INDEX IF NOT EXISTS idx_stock_prices_symbol_date ON stock_prices (symbol, date);
