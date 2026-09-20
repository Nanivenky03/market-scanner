ALTER TABLE live_feed_state
    ADD COLUMN health_status TEXT NOT NULL DEFAULT 'HEALTHY';

ALTER TABLE live_feed_state
    ADD COLUMN subscription_active BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE live_feed_state
    ADD COLUMN stale_since TEXT;

ALTER TABLE live_feed_state
    ADD COLUMN last_health_transition_at TEXT;

ALTER TABLE live_feed_state
    ADD COLUMN stale_alerted_at TEXT;

ALTER TABLE live_feed_state
    ADD COLUMN recovered_at TEXT;

ALTER TABLE live_feed_state
    ADD COLUMN consecutive_recovery_ticks INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_live_feed_state_subscription
    ON live_feed_state(trading_date, subscription_active);



