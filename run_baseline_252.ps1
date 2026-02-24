[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient
$resp = $client.UploadString("http://localhost:8080/simulation/advance?days=252", "POST", "")
Write-Host "Raw Response:"
Write-Host $resp.Substring(0, [Math]::Min(500, $resp.Length))
Write-Host ""
Write-Host "Parsing JSON..."
$json = $resp | ConvertFrom-Json
Write-Host "Cycles Requested: $($json.cyclesRequested)"
Write-Host "Cycles Completed: $($json.cyclesCompleted)"
Write-Host "Total Duration: $($json.totalDurationMs) ms"
