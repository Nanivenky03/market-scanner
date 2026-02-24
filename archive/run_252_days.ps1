[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient
Write-Host "Advancing simulation by 252 trading days..."
Write-Host "This may take several minutes. Starting..."
$startTime = Get-Date

$resp = $client.UploadString("http://localhost:8080/simulation/advance?days=252", "POST", "")
$endTime = Get-Date
$duration = ($endTime - $startTime).TotalSeconds

Write-Host "Response: $resp"
Write-Host "Duration: $($duration) seconds"
