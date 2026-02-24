[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient

Write-Host "Triggering determinism replay (252 identical days)..."
$resp = $client.UploadString("http://localhost:8080/simulation/advance?days=252", "POST", "")

# Save replay response
$resp | Out-File -FilePath d:\projects\market-scanner\PHASE4_REPLAY_RESPONSE.json -Encoding UTF8

$json = $resp | ConvertFrom-Json

# Extract summary
Write-Host "=== PHASE 4: DETERMINISM REPLAY ==="
Write-Host "Total Cycles: $($json.cyclesCompleted)/$($json.cyclesRequested)"
Write-Host "Total Duration: $($json.totalDurationMs) ms"
Write-Host "Average per Cycle: $('{0:F2}' -f ($json.totalDurationMs / $json.cyclesCompleted)) ms"

# Check cycle details
$failed = $json.cycleResults | Where-Object { $_.success -eq $false }
if ($failed) {
    Write-Host "FAILED CYCLES: $($failed.Count)"
    $failed | ForEach-Object { Write-Host "  - Offset $($_.tradingOffset): $($_.failureReason)" }
} else {
    Write-Host "Failed Cycles: 0 (PASS)"
}

# Timing stats
$durations = $json.cycleResults | ForEach-Object { $_.durationMs }
Write-Host "Min Cycle Duration: $(($durations | Measure-Object -Minimum).Minimum) ms"
Write-Host "Max Cycle Duration: $(($durations | Measure-Object -Maximum).Maximum) ms"
Write-Host "Avg Cycle Duration: $('{0:F2}' -f ($durations | Measure-Object -Average).Average) ms"

# Date progression
Write-Host "First Cycle Date: $($json.cycleResults[0].cycleDate)"
Write-Host "Last Cycle Date: $($json.cycleResults[-1].cycleDate)"

# Signals check
$totalSignals = ($json.cycleResults | Measure-Object -Property signalsGenerated -Sum).Sum
Write-Host "Total Signals Generated: $totalSignals"

Write-Host ""
Write-Host "Replay complete. Comparing with baseline for determinism proof..."
