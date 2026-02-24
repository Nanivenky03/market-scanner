# final_concurrency_test.ps1
# PHASE A — CONCURRENCY REGRESSION (MANDATORY PASS)

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$baseUrl = "http://localhost:8080"
$advanceUrl = "$baseUrl/simulation/advance?days=3"
$statusUrl = "$baseUrl/simulation/status"

Write-Host "=== PHASE A: CONCURRENCY REGRESSION ==="
Write-Host "Target: 20 concurrent requests to $advanceUrl"

# 1. Get Initial Offset
try {
    $initialState = Invoke-RestMethod -Uri $statusUrl -Method Get
    $initialOffset = $initialState.tradingOffset
    Write-Host "Initial Offset: $initialOffset"
} catch {
    Write-Host "ERROR: Could not get initial status. Is the app running?"
    exit 1
}

# 2. Spawn 20 concurrent jobs
$jobs = @()
1..20 | ForEach-Object {
    $job = Start-Job -ScriptBlock {
        param($url, $id)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        try {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $resp = Invoke-RestMethod -Uri $url -Method Post
            $sw.Stop()
            return @{
                id = $id
                status = 200
                duration = $sw.ElapsedMilliseconds
                cycles = $resp.cyclesCompleted
                error = $null
            }
        } catch {
            return @{
                id = $id
                status = $_.Exception.Response.StatusCode.value__
                duration = 0
                cycles = 0
                error = $_.Exception.Message
            }
        }
    } -ArgumentList $advanceUrl, $_
    $jobs += $job
}

Write-Host "Requests dispatched. Waiting..."
$results = $jobs | Wait-Job | Receive-Job

# 3. Analyze Results
$successCount = 0
$failCount = 0
$totalCycles = 0

Write-Host ""
Write-Host "--- JOB RESULTS ---"
foreach ($res in $results) {
    if ($res.status -eq 200) {
        Write-Host "Job $($res.id): SUCCESS (Cycles: $($res.cycles), Time: $($res.duration)ms)"
        $successCount++
        $totalCycles += $res.cycles
    } else {
        Write-Host "Job $($res.id): FAILED (Status: $($res.status), Error: $($res.error))"
        $failCount++
    }
}

# 4. Verification
$finalState = Invoke-RestMethod -Uri $statusUrl -Method Get
$finalOffset = $finalState.tradingOffset
$expectedOffset = $initialOffset + 60 # 20 requests * 3 days

Write-Host ""
Write-Host "--- METRICS ---"
Write-Host "Success Rate: $successCount/20"
Write-Host "Initial Offset: $initialOffset"
Write-Host "Final Offset:   $finalOffset"
Write-Host "Expected Offset: $expectedOffset"

if ($successCount -eq 20 -and $finalOffset -eq $expectedOffset) {
    Write-Host "RESULT: PASS"
} else {
    Write-Host "RESULT: FAIL"
    if ($successCount -ne 20) { Write-Host " - Not all requests succeeded." }
    if ($finalOffset -ne $expectedOffset) { Write-Host " - Offset mismatch (Diff: $($finalOffset - $expectedOffset))." }
}

# Cleanup
$jobs | Remove-Job
