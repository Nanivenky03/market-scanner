-- V034: Cleanup legacy prototype simulation and scan tables
DROP TABLE IF EXISTS live_simulation_signal CASCADE;
DROP TABLE IF EXISTS simulation_trade CASCADE;
DROP TABLE IF EXISTS simulation_variant CASCADE;
DROP TABLE IF EXISTS simulation_run_group CASCADE;
DROP TABLE IF EXISTS scan_results CASCADE;
DROP TABLE IF EXISTS signal_outcomes CASCADE;
DROP TABLE IF EXISTS scanner_runs CASCADE;
DROP TABLE IF EXISTS scan_execution_state CASCADE;

