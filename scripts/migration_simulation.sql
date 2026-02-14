-- Market Scanner - Simulation Mode Migration
-- Add simulation_state table

CREATE TABLE IF NOT EXISTS simulation_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    base_date TEXT NOT NULL,
    offset_days INTEGER NOT NULL DEFAULT 0
);

-- Initialize simulation state (only if running in simulation mode)
-- This will be created automatically by Spring if using simulation profile
-- Manual insert if needed:
-- INSERT OR IGNORE INTO simulation_state (id, base_date, offset_days) VALUES (1, '2023-01-01', 0);
