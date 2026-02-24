[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient

Write-Host "=== PHASE 5: IDEMPOTENCY ATTACK ==="
Write-Host ""
Write-Host "Running /simulation/advance?days=10 twice (same query, back-to-back)..."

# First run
Write-Host "RUN 1: Advancing 10 days..."
$resp1 = $client.UploadString("http://localhost:8080/simulation/advance?days=10", "POST", "")
$json1 = $resp1 | ConvertFrom-Json
Write-Host "  Cycles: $($json1.cyclesCompleted)"

# Query DB after first run
$sql_check = sqlite3 "d:\projects\market-scanner\data\market_scanner_sim.db" "SELECT 'scanner_runs=' || COUNT(*) FROM scanner_runs; SELECT 'scan_results=' || COUNT(*) FROM scan_results;"
Write-Host "  After Run 1: $sql_check"

# Second run (identical)
Write-Host "RUN 2: Advancing same 10 days again..."
$resp2 = $client.UploadString("http://localhost:8080/simulation/advance?days=10", "POST", "")
$json2 = $resp2 | ConvertFrom-Json
Write-Host "  Cycles: $($json2.cyclesCompleted)"

# Query DB after second run
$sql_check2 = sqlite3 "d:\projects\market-scanner\data\market_scanner_sim.db" "SELECT 'scanner_runs=' || COUNT(*) FROM scanner_runs; SELECT 'scan_results=' || COUNT(*) FROM scan_results;"
Write-Host "  After Run 2: $sql_check2"

Write-Host ""
Write-Host "IDEMPOTENCY TEST RESULT:"
if ($json1.cyclesCompleted -eq 10 -and $json2.cyclesCompleted -eq 10) {
    Write-Host "  Both runs completed 10 cycles: PASS"
} else {
    Write-Host "  Cycle count mismatch: FAIL"
}

# Extract row counts before/after
$before_runs = 0
$after_runs = 0  
if ($sql_check -match "scanner_runs=(\d+)") {
    $before_runs = [int]$matches[1]
}
if ($sql_check2 -match "scanner_runs=(\d+)") {
    $after_runs = [int]$matches[1]
}

Write-Host "  No new scanner_runs created: $(if ($after_runs -eq $before_runs) { "PASS" } else { "FAIL - expected same count, got $before_runs then $after_runs" })"
