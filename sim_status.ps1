[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient
$resp = $client.DownloadString("http://localhost:8080/simulation/status")
Write-Host "SIMULATION STATUS:"
Write-Host $resp
