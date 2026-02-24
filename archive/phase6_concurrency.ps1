[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Write-Host "=== PHASE 6: CONCURRENCY STRESS ==="
Write-Host ""
Write-Host "Spawning 5 concurrent /simulation/advance?days=5 requests..."

# Create 5 concurrent jobs
$jobs = @()
1..5 | ForEach-Object {
    $job = Start-Job -ScriptBlock {
        param($n)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $c = New-Object System.Net.WebClient
        try {
            $resp = $c.UploadString("http://localhost:8080/simulation/advance?days=5", "POST", "")
            $json = $resp | ConvertFrom-Json
            return @{
                job = $n
                cycles = $json.cyclesCompleted
                duration = $json.totalDurationMs
                success = $true
                error = $null
            }
        } catch {
            return @{
                job = $n
                success = $false
                error = $_.Exception.Message
            }
        }
    } -ArgumentList $_
    $jobs += $job
}

Write-Host "Waiting for all 5 concurrent requests to complete..."
$results = $jobs | Wait-Job | Receive-Job

Write-Host ""
Write-Host "CONCURRENCY TEST RESULTS:"
$successCount = 0
$totalCycles = 0
foreach ($result in $results) {
    if ($result.success) {
        Write-Host "  Job $($result.job): $($result.cycles) cycles in $($result.duration)ms"
        $successCount++
        $totalCycles += $result.cycles
    } else {
        Write-Host "  Job $($result.job): FAILED - $($result.error)"
    }
}

Write-Host ""
Write-Host "Concurrent Requests Succeeded: $successCount/5"
Write-Host "Total Cycles Executed: $totalCycles"

if ($successCount -eq 5) {
    Write-Host "CONCURRENCY: PASS"
} else {
    Write-Host "CONCURRENCY: FAIL ($successCount/5 succeeded)"
}

# Clean up jobs
$jobs | Stop-Job
$jobs | Remove-Job
