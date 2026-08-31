UPDATE market_candles SET quality_status = 'LIVE' WHERE quality_status = 'VALID';
UPDATE market_candles SET quality_status = 'REPAIRED' WHERE quality_status = 'RECOVERED';

ALTER TABLE daily_candle_summary ADD COLUMN live_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_candle_summary ADD COLUMN repaired_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_candle_summary ADD COLUMN reconciled_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE daily_candle_summary ADD COLUMN suspect_count INTEGER NOT NULL DEFAULT 0;


