ALTER TABLE market_candles
ADD COLUMN processing_status TEXT NOT NULL DEFAULT 'RELEASED';

ALTER TABLE backfill_job
ADD COLUMN from_time TEXT;

ALTER TABLE backfill_job
ADD COLUMN to_time TEXT;

ALTER TABLE backfill_job
ADD COLUMN lease_until TEXT;

UPDATE backfill_job
SET from_time = trading_date || ' 09:15',
    to_time = trading_date || ' 15:29'
WHERE from_time IS NULL
   OR to_time IS NULL;
