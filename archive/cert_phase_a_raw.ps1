# cert_phase_a_raw.ps1
# PHASE A — CONCURRENCY RAW PROOF

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$baseUrl = "http://localhost:8080"
$advanceUrl = "$baseUrl/simulation/advance?days=3"
$statusUrl = "$baseUrl/simulation/status"
$dbPath = "data/market_scanner_sim.db"

Write-Host "=== PHASE A: CONCURRENCY RAW PROOF ==="

# 1. Capture Initial State
$initialState = Invoke-RestMethod -Uri $statusUrl -Method Get
$initialOffset = $initialState.tradingOffset
$initialResults = (& sqlite3 $dbPath "SELECT COUNT(*) FROM scan_results;")
$initialRuns = (& sqlite3 $dbPath "SELECT COUNT(*) FROM scanner_runs;")

Write-Host "Initial Offset: $initialOffset"
Write-Host "Initial Results: $initialResults"
Write-Host "Initial Runs: $initialRuns"

# 2. Fire 20 concurrent requests
$jobs = @()
$sw = [System.Diagnostics.Stopwatch]::StartNew()
1..20 | ForEach-Object {
    $job = Start-Job -ScriptBlock {
        param($url, $id)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        try {
            $resp = Invoke-WebRequest -Uri $url -Method Post -UseBasicParsing -ErrorAction Stop
            return @{ id = $id; status = [int]$resp.StatusCode; cycles = ($resp.Content | ConvertFrom-Json).cyclesCompleted }
        } catch {
            $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 500 }
            return @{ id = $id; status = $code; error = $_.Exception.Message }
        }
    } -ArgumentList $advanceUrl, $_
    $jobs += $job
}

Write-Host "Requests dispatched. Waiting for completion..."
$results = $jobs | Wait-Job | Receive-Job
$sw.Stop()

# 3. Capture Final State
$finalState = Invoke-RestMethod -Uri $statusUrl -Method Get
$finalOffset = $finalState.tradingOffset
$finalResults = (& sqlite3 $dbPath "SELECT COUNT(*) FROM scan_results;")
$finalRuns = (& sqlite3 $dbPath "SELECT COUNT(*) FROM scanner_runs;")

# 4. Raw Report
Write-Host ""
Write-Host "--- RAW PROOF REPORT ---"
Write-Host "Total Execution Time: $($sw.ElapsedMilliseconds)ms"
Write-Host ""
Write-Host "HTTP STATUS CODES:"
$results | Sort-Object id | ForEach-Object {
    $msg = if ($_.error) { " | Error: $($_.error)" } else { " | Cycles: $($_.cycles)" }
    Write-Host "  Request $($_.id): $($_.status)$msg"
}

Write-Host ""
Write-Host "STATE METRICS:"
Write-Host "  Offset Before: $initialOffset"
Write-Host "  Offset After:  $finalOffset"
Write-Host "  Delta:         $($finalOffset - $initialOffset)"
Write-Host "  Expected Delta: 60"
Write-Host ""
Write-Host "  Results Before: $initialResults"
Write-Host "  Results After:  $finalResults"
Write-Host "  Runs Before:    $initialRuns"
Write-Host "  Runs After:     $finalRuns"

# 5. Extract Exception Logs (Last 100 lines)
Write-Host ""
Write-Host "--- LOG SCAN (SEARCHING FOR EXCEPTIONS) ---"
# Check if logs exist - assuming data/logs/ or standard output
# Assuming the app outputs to console or standard file if redirected.
# Let's try to get last few lines of log if we redirected it, or skip if not.
# If we used Start-Process without redirection, we might not have them easily.
# But we can look for 'optimistic' or 'constraint' in current log file if it exists.
$logFile = (Get-ChildItem -Path "data/logs" -Filter "*.log" | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
if ($logFile) {
    Get-Content $logFile -Tail 100 | Where-Object { $_ -match "Exception" -or $_ -match "Error" -or $_ -match "Conflict" }
} else {
    Write-Host "  No log file found in data/logs."
}

# Cleanup
$jobs | Remove-Job
