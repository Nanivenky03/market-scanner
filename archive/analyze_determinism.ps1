# Load both responses
Write-Host "Loading baseline and replay responses..."
$baseline = Get-Content "d:\projects\market-scanner\PHASE3_BASELINE_RESPONSE.json" | ConvertFrom-Json
$replay = Get-Content "d:\projects\market-scanner\PHASE4_REPLAY_RESPONSE.json" | ConvertFrom-Json

Write-Host ""
Write-Host "=== DETERMINISM ANALYSIS ==="
Write-Host ""

# Compare cycle counts
if ($baseline.cyclesCompleted -eq $replay.cyclesCompleted) {
    Write-Host "Cycle Count Match: $($baseline.cyclesCompleted) (PASS)"
} else {
    Write-Host "Cycle Count Mismatch: baseline=$($baseline.cyclesCompleted), replay=$($replay.cyclesCompleted) (FAIL)"
}

# Compare cycle success patterns
$baselineSuccesses = $baseline.cycleResults | ForEach-Object { $_.success }
$replaySuccesses = $replay.cycleResults | ForEach-Object { $_.success }

$matchCount = 0
for ($i = 0; $i -lt $baselineSuccesses.Count; $i++) {
    if ($baselineSuccesses[$i] -eq $replaySuccesses[$i]) {
        $matchCount++
    }
}
Write-Host "Success Pattern Match: $matchCount/$($baselineSuccesses.Count) cycles"

# Compare timing patterns
$baselineTimings = $baseline.cycleResults | ForEach-Object { $_.durationMs }
$replayTimings = $replay.cycleResults | ForEach-Object { $_.durationMs }

$timingVariance = 0
for ($i = 0; $i -lt $baselineTimings.Count; $i++) {
    $diff = [Math]::Abs($baselineTimings[$i] - $replayTimings[$i])
    $timingVariance += $diff
}
Write-Host "Timing Variance: $timingVariance ms total (avg: $('{0:F2}' -f ($timingVariance/$baselineTimings.Count)) ms/cycle)"

# Compare signals
$baselineSignals = $baseline.cycleResults | Measure-Object -Property signalsGenerated -Sum
$replaySignals = $replay.cycleResults | Measure-Object -Property signalsGenerated -Sum

if ($baselineSignals.Sum -eq $replaySignals.Sum) {
    Write-Host "Total Signals Match: $($baselineSignals.Sum) (PASS)"
} else {
    Write-Host "Total Signals Mismatch: baseline=$($baselineSignals.Sum), replay=$($replaySignals.Sum) (FAIL)"
}

# Compare ingested stocks
$baselineIngested = $baseline.cycleResults | Measure-Object -Property stocksIngested -Sum
$replayIngested = $replay.cycleResults | Measure-Object -Property stocksIngested -Sum

if ($baselineIngested.Sum -eq $replayIngested.Sum) {
    Write-Host "Total Ingested Match: $($baselineIngested.Sum) (PASS)"
} else {
    Write-Host "Total Ingested Mismatch: baseline=$($baselineIngested.Sum), replay=$($replayIngested.Sum) (FAIL)"
}

Write-Host ""
Write-Host "First 5 cycles comparison:"
for ($i = 0; $i -lt 5; $i++) {
    $b = $baseline.cycleResults[$i]
    $r = $replay.cycleResults[$i]
    Write-Host "Cycle $($i+1):"
    Write-Host "  Baseline: date=$($b.cycleDate), duration=$($b.durationMs)ms, signals=$($b.signalsGenerated), success=$($b.success)"
    Write-Host "  Replay:   date=$($r.cycleDate), duration=$($r.durationMs)ms, signals=$($r.signalsGenerated), success=$($r.success)"
}
