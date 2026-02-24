param(
    [string]$DbPath = "d:\projects\market-scanner\data\market_scanner_sim.db"
)

$sql = @"
SELECT 
    'scanner_runs' as table_name,
    COUNT(*) as row_count
FROM scanner_runs
UNION ALL
SELECT 
    'scan_results',
    COUNT(*)
FROM scan_results
UNION ALL
SELECT 
    'simulation_state',
    COUNT(*)
FROM simulation_state;
"@

$sqlite = "C:\Windows\System32\wsl.exe"
if (-not (Test-Path $sqlite)) {
    # Fallback: use sqlite3 via PowerShell if WSL not available
    Write-Host "Using direct SQLite query..."
    Add-Type -AssemblyName System.Data.SQLite
    $conn = New-Object System.Data.SQLite.SQLiteConnection "Data Source=$DbPath"
    $conn.Open()
    $cmd = $conn.CreateCommand()
    $cmd.CommandText = $sql
    $reader = $cmd.ExecuteReader()
    while ($reader.Read()) {
        Write-Host "$($reader[0]): $($reader[1]) rows"
    }
    $conn.Close()
} else {
    Write-Host "SQLite query via command..."
    sqlite3 $DbPath $sql
}
