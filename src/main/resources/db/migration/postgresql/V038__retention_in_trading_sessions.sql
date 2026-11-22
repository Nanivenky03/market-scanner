-- V038: Candle/context/baseline retention is now counted in trading sessions (not calendar days).
-- 150 sessions keeps the ~110 sessions needed for replay research plus growth headroom.
INSERT INTO runtime_setting (name, value, value_type, description, is_active, updated_at)
SELECT 'retention.candles.sessions', '150', 'INTEGER',
       'Retention for candles, daily contexts and volume baselines, in trading sessions', TRUE, now()::text
WHERE NOT EXISTS (SELECT 1 FROM runtime_setting WHERE name = 'retention.candles.sessions');

-- The superseded day-based key is no longer read by any code; deactivate it so it cannot mislead.
UPDATE runtime_setting
SET is_active = FALSE, updated_at = now()::text
WHERE name = 'retention.candles.days';
