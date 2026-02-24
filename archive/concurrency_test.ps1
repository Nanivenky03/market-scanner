$ScriptBlock = {
    param($i)
    $startTime = Get-Date
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/simulation/advance?days=3" -Method Post -UseBasicParsing
        $endTime = Get-Date
        $duration = $endTime - $startTime
        [PSCustomObject]@{
            Thread = $i
            StatusCode = $response.StatusCode
            StatusDescription = $response.StatusDescription
            Duration = $duration.TotalSeconds
            Content = $response.Content
            Error = $null
        }
    }
    catch {
        $endTime = Get-Date
        $duration = $endTime - $startTime
        [PSCustomObject]@{
            Thread = $i
            StatusCode = $_.Exception.Response.StatusCode.value__
            StatusDescription = $_.Exception.Response.StatusDescription
            Duration = $duration.TotalSeconds
            Content = $null
            Error = $_.Exception.Message
        }
    }
}

$jobs = 1..20 | ForEach-Object {
    Start-Job -ScriptBlock $ScriptBlock -ArgumentList $_
}

$results = $jobs | Wait-Job | Receive-Job

$results | ConvertTo-Json
