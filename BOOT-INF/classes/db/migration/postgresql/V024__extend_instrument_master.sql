ALTER TABLE instrument_master ADD COLUMN tick_size REAL;
ALTER TABLE instrument_master ADD COLUMN lot_size INTEGER;
ALTER TABLE instrument_master ADD COLUMN expiry TEXT;
ALTER TABLE instrument_master ADD COLUMN strike_price REAL;
ALTER TABLE instrument_master ADD COLUMN metadata_source TEXT;


