ALTER TABLE market_candles
ADD COLUMN body_ratio REAL;

ALTER TABLE market_candles
ADD COLUMN upper_wick_ratio REAL;

ALTER TABLE market_candles
ADD COLUMN lower_wick_ratio REAL;

ALTER TABLE market_candles
ADD COLUMN range_pct REAL;

ALTER TABLE market_candles
ADD COLUMN direction TEXT;

ALTER TABLE market_candles
ADD COLUMN strong_bullish INTEGER;

ALTER TABLE market_candles
ADD COLUMN strong_bearish INTEGER;



