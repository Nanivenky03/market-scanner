ALTER TABLE daily_stock_context
ADD COLUMN prev_day_high DOUBLE PRECISION,
ADD COLUMN prev_day_low DOUBLE PRECISION,
ADD COLUMN prev_day_open DOUBLE PRECISION,
ADD COLUMN prev_day_close DOUBLE PRECISION,
ADD COLUMN prev_day_range_pct DOUBLE PRECISION,
ADD COLUMN consecutive_red_days INTEGER,
ADD COLUMN consecutive_green_days INTEGER,
ADD COLUMN highest_close_15d DOUBLE PRECISION,
ADD COLUMN dist_from_resistance_pct DOUBLE PRECISION,
ADD COLUMN gap_pct DOUBLE PRECISION,
ADD COLUMN corporate_action_flag BOOLEAN,
ADD COLUMN fo_ban_flag BOOLEAN,
ADD COLUMN results_last_3d_flag BOOLEAN,
ADD COLUMN skip_today BOOLEAN,
ADD COLUMN first_candle_bullish BOOLEAN,
ADD COLUMN first_candle_valid BOOLEAN,
ADD COLUMN opening_range_skew DOUBLE PRECISION,
ADD COLUMN opening_range_valid BOOLEAN,
ADD COLUMN breakout_reference_price DOUBLE PRECISION;



