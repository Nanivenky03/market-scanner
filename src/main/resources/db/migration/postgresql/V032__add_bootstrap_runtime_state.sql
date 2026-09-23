INSERT INTO runtime_setting
(
    name,
    value,
    value_type,
    description,
    is_active,
    updated_at
)
VALUES
(
    'runtime.bootstrap.status',
    'REQUIRED',
    'STRING',
    'One-time historical bootstrap lifecycle state',
    TRUE,
    now()::text
),
(
    'runtime.bootstrap.message',
    'First-run historical bootstrap is required',
    'STRING',
    'One-time historical bootstrap lifecycle message',
    TRUE,
    now()::text
),
(
    'runtime.bootstrap.date',
    '',
    'DATE',
    'Date on which the bootstrap state was last updated',
    TRUE,
    now()::text
)
ON CONFLICT (name) DO NOTHING;
