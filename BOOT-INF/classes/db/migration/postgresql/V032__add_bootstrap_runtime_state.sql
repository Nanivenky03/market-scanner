INSERT INTO runtime_setting
(
    setting_key,
    setting_value,
    value_type,
    scope,
    description,
    is_active,
    updated_at,
    updated_by,
    version
)
VALUES
(
    'runtime.bootstrap.status',
    'REQUIRED',
    'STRING',
    'GLOBAL',
    'One-time historical bootstrap lifecycle state',
    TRUE,
    now()::text,
    'system',
    0
),
(
    'runtime.bootstrap.message',
    'First-run historical bootstrap is required',
    'STRING',
    'GLOBAL',
    'One-time historical bootstrap lifecycle message',
    TRUE,
    now()::text,
    'system',
    0
),
(
    'runtime.bootstrap.date',
    '',
    'DATE',
    'GLOBAL',
    'Date on which the bootstrap state was last updated',
    TRUE,
    now()::text,
    'system',
    0
)
ON CONFLICT (setting_key, scope) DO NOTHING;



