# clean_room_concurrency.ps1
# Prepares a clean simulation playground and exercises the advance endpoint concurrently.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Definition
$dataDir = Join-Path $scriptRoot 'data'
$simDb = Join-Path $dataDir 'market_scanner_sim.db'
$initScript = Join-Path $scriptRoot 'scripts/init_db.sql'
$statusUrl = 'http://localhost:8080/simulation/status'
$advanceUrl = 'http://localhost:8080/simulation/advance?days=3'

function Stop-ExistingMarketScanner {
    $candidates = Get-CimInstance -ClassName Win32_Process -Filter \"Name='java.exe'\" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'market-scanner' }

    foreach ($proc in $candidates) {
        Write-Host \"Stopping existing market-scanner process (PID $($proc.ProcessId))\"
        Stop-Process -Id $proc.ProcessId -Force
    }
}

function Assert-ToolAvailable {
    param([string]$Tool)

    if (-not (Get-Command $Tool -ErrorAction SilentlyContinue)) {
        throw \"$Tool is required but not found in PATH.\"
    }
}

Assert-ToolAvailable -Tool 'sqlite3'
Assert-ToolAvailable -Tool 'mvn'

Stop-ExistingMarketScanner

if (Test-Path $simDb) {
    Write-Host "Removing $simDb"
    Remove-Item -Path $simDb -Force
}

if (-not (Test-Path $dataDir)) {
    New-Item -ItemType Directory -Path $dataDir | Out-Null
}

Write-Host "Rebuilding simulation database from $initScript"
Get-Content $initScript | & sqlite3 $simDb

Write-Host "Packaging application (mvn -q -DskipTests package)"
& mvn -q -DskipTests package

$jar = Get-ChildItem -Path (Join-Path $scriptRoot 'target') -Filter 'market-scanner-*.jar' |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw 'Packaged JAR not found under target/.'
}

Write-Host "Starting market-scanner simulation profile (JAR: $($jar.Name))"
$appProcess = Start-Process -FilePath 'java' `
    -ArgumentList '-jar', $jar.FullName, '--spring.profiles.active=simulation' `
    -WorkingDirectory $scriptRoot `
    -PassThru

try {
    $status = $null
    for ($i = 0; $i -lt 15; $i++) {
        Start-Sleep -Seconds (if ($i -lt 3) { 1 } else { 2 })
        try {
            $status = Invoke-RestMethod -Uri $statusUrl -Method Get -TimeoutSec 2
            break
        } catch {
            Write-Host 'Waiting for /simulation/status to become reachable...'
        }
    }

    if (-not $status) {
        throw 'Simulation status endpoint did not become available after waiting.'
    }

    Write-Host \"Initial trading offset: $($status.tradingOffset)\"
    if ($status.tradingOffset -ne 0) {
        throw 'Simulation offset is not zero after recreation.'
    }

    Write-Host 'Dispatching 20 parallel POST /simulation/advance?days=3 requests...'
    $jobs = 1..20 | ForEach-Object {
        Start-Job -ScriptBlock {
            param($url, $id)
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            try {
                $timer = [System.Diagnostics.Stopwatch]::StartNew()
                $response = Invoke-RestMethod -Uri $url -Method Post
                $timer.Stop()
                return @{
                    id = $id
                    status = 200
                    duration = $timer.ElapsedMilliseconds
                    cycles = $response.cyclesCompleted
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
    }

    Write-Host 'Waiting for all jobs to finish...'
    $results = $jobs | Wait-Job | Receive-Job
    $jobs | Remove-Job

    $successCount = ($results | Where-Object status -eq 200).Count
    $final = Invoke-RestMethod -Uri $statusUrl -Method Get
    $expectedOffset = 20 * 3

    Write-Host ''
    Write-Host '--- CLEAN ROOM RESULTS ---'
    Write-Host \"Successful advance jobs : $successCount/20\"
    Write-Host \"Final trading offset   : $($final.tradingOffset)\"
    Write-Host \"Expected trading offset: $expectedOffset\"

    if ($successCount -ne 20) {
        Write-Host 'Warning: Not all advance requests completed successfully.'
    }

    if ($final.tradingOffset -ne $expectedOffset) {
        Write-Host 'Warning: Offset after concurrent advances did not reach expected value.'
    }

} finally {
    if ($appProcess -and -not $appProcess.HasExited) {
        Write-Host "Stopping started application (PID $($appProcess.Id))"
        Stop-Process -Id $appProcess.Id -Force
        $appProcess.WaitForExit()
    }
}
