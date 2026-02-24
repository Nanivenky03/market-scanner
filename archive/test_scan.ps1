[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$client = New-Object System.Net.WebClient
$resp = $client.UploadString("http://localhost:8080/scan/execute", "POST", "")
Write-Host "Scan Response: $resp"
