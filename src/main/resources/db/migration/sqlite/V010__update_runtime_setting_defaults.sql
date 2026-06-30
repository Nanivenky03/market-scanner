UPDATE runtime_setting
SET setting_value = '08:50', value_type = 'TIME', updated_at = datetime('now'), updated_by = 'system', version = version + 1
WHERE setting_key = 'angelone.login.time' AND scope = 'GLOBAL';

UPDATE runtime_setting
SET setting_value = '09:00', value_type = 'TIME', updated_at = datetime('now'), updated_by = 'system', version = version + 1
WHERE setting_key = 'websocket.connect.time' AND scope = 'GLOBAL';

UPDATE runtime_setting
SET setting_value = '15:40', value_type = 'TIME', updated_at = datetime('now'), updated_by = 'system', version = version + 1
WHERE setting_key = 'websocket.disconnect.time' AND scope = 'GLOBAL';

UPDATE runtime_setting
SET setting_value = '16:00', value_type = 'TIME', updated_at = datetime('now'), updated_by = 'system', version = version + 1
WHERE setting_key = 'angelone.disconnect.time' AND scope = 'GLOBAL';

INSERT OR IGNORE INTO runtime_setting
(setting_key, setting_value, value_type, scope, description, is_active, updated_at, updated_by, version)
VALUES
('websocket.idle.close.minutes', '10', 'INTEGER', 'GLOBAL', 'Close websocket if no ticks received for these many minutes after close-check time', TRUE, datetime('now'), 'system', 0),
('websocket.close.recheck.minutes', '10', 'INTEGER', 'GLOBAL', 'Recheck interval in minutes if ticks are still being received', TRUE, datetime('now'), 'system', 0);