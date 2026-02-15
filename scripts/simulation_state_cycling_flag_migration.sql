-- Add cycling safety flags to prevent concurrent multi-day simulation runs
ALTER TABLE simulation_state ADD COLUMN is_cycling BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE simulation_state ADD COLUMN cycling_started_at TEXT;
