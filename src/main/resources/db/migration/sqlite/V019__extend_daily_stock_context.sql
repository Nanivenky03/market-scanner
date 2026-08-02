ALTER TABLE daily_stock_context
ADD COLUMN prev_day_high REAL;

ALTER TABLE daily_stock_context
ADD COLUMN prev_day_low REAL;

ALTER TABLE daily_stock_context
ADD COLUMN prev_day_open REAL;

ALTER TABLE daily_stock_context
ADD COLUMN prev_day_close REAL;

ALTER TABLE daily_stock_context
ADD COLUMN prev_day_range_pct REAL;

ALTER TABLE daily_stock_context
ADD COLUMN consecutive_red_days INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN consecutive_green_days INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN highest_close_15d REAL;

ALTER TABLE daily_stock_context
ADD COLUMN dist_from_resistance_pct REAL;

ALTER TABLE daily_stock_context
ADD COLUMN gap_pct REAL;

ALTER TABLE daily_stock_context
ADD COLUMN corporate_action_flag INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN fo_ban_flag INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN results_last_3d_flag INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN skip_today INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN first_candle_bullish INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN first_candle_valid INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN opening_range_skew REAL;

ALTER TABLE daily_stock_context
ADD COLUMN opening_range_valid INTEGER;

ALTER TABLE daily_stock_context
ADD COLUMN breakout_reference_price REAL;



