Write-Host "PHASE 9: Persistence Correctness (NULL/aggregate checks)"
Write-Host ""

# Check for NULLs in critical columns
$nullCheck1 = sqlite3 "d:\projects\market-scanner\data\market_scanner_sim.db" "SELECT COUNT(*) FROM stock_prices WHERE symbol IS NULL OR date IS NULL;"
$nullCheck2 = sqlite3 "d:\projects\market-scanner\data\market_scanner_sim.db" "SELECT COUNT(*) FROM scanner_runs WHERE scan_date IS NULL;"
$nullCheck3 = sqlite3 "d:\projects\market-scanner\data\market_scanner_sim.db" "SELECT COUNT(*) FROM scan_results WHERE scanner_run_id IS NULL OR signal_symbol IS NULL;"

Write-Host "stock_prices NULL violations: $nullCheck1"
Write-Host "scanner_runs NULL violations: $nullCheck2"
Write-Host "scan_results NULL violations: $nullCheck3"

if ([int]$nullCheck1 -eq 0 -and [int]$nullCheck2 -eq 0 -and [int]$nullCheck3 -eq 0) {
    Write-Host ""
    Write-Host "PHASE 9: PASS (no NULL violations)"
} else {
    Write-Host ""
    Write-Host "PHASE 9: FAIL (found NULL violations)"
}
