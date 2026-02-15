CREATE TABLE IF NOT EXISTS emergency_closure (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL UNIQUE,
    reason TEXT,
    created_at TEXT NOT NULL
);
