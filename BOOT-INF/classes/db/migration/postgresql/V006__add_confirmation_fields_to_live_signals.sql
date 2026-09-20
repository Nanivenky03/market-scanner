ALTER TABLE live_simulation_signal
ADD COLUMN decision_candle_time TEXT;

ALTER TABLE live_simulation_signal
ADD COLUMN confirmation_decision TEXT;

ALTER TABLE live_simulation_signal
ADD COLUMN confirmation_context TEXT;