CREATE TABLE runtime_alert_state (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_key TEXT NOT NULL,
    severity TEXT NOT NULL,
    status TEXT NOT NULL,
    message TEXT NOT NULL,
    details TEXT,
    first_triggered_at TEXT NOT NULL,
    last_triggered_at TEXT NOT NULL,
    last_resolved_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    CONSTRAINT uq_runtime_alert_state_key UNIQUE (alert_key)
);

CREATE INDEX idx_runtime_alert_state_status
ON runtime_alert_state(status);

CREATE INDEX idx_runtime_alert_state_severity
ON runtime_alert_state(severity);