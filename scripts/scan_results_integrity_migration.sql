

UPDATE scan_results
SET rule_version = '1.0'
WHERE rule_version IS NULL;

UPDATE scan_results
SET parameter_snapshot = '{"legacy":true}'
WHERE parameter_snapshot IS NULL;
