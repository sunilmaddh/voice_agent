$ErrorActionPreference = 'Stop'
$queries = @('Mount Everest', 'what is Kotlin', 'weather in Bangalore', 'latest India news today', 'live Reliance Industries share price')
$headers = @{'User-Agent' = 'AikoAssistant/1.0'}
$rows = foreach ($query in $queries) {
    $url = 'https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=' + [uri]::EscapeDataString($query)
    $provider = 'DUCKDUCKGO_INSTANT_ANSWERS'
    if ($query -eq 'weather in Bangalore') {
        $provider = 'OPEN_METEO'
        $url = 'https://geocoding-api.open-meteo.com/v1/search?name=Bengaluru&count=10&countryCode=IN&language=en&format=json'
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $data = Invoke-RestMethod $url -Headers $headers -TimeoutSec 8
        if ($provider -eq 'OPEN_METEO') {
            $place = $data.results | Where-Object { $_.country_code -eq 'IN' -and $_.admin1 -eq 'Karnataka' -and $_.name -in @('Bengaluru','Bangalore') } | Select-Object -First 1
            if (-not $place) { throw 'No Bengaluru, Karnataka, India match' }
            $geocodingUrl = $url
            $url = "https://api.open-meteo.com/v1/forecast?latitude=$($place.latitude)&longitude=$($place.longitude)&current=temperature_2m,apparent_temperature,weather_code&temperature_unit=celsius&timezone=auto"
            $data = Invoke-RestMethod $url -Headers $headers -TimeoutSec 8
            $row = @{query=$query; provider=$provider; url=$url; geocodingUrl=$geocodingUrl; place=$place; current=$data.current; timezone=$data.timezone; status='HTTP_SUCCESS'}
        } else {
            $row = @{query=$query; provider=$provider; url=$url; abstract=$data.AbstractText; source=$data.AbstractURL; relatedTopics=$data.RelatedTopics; status='HTTP_SUCCESS'}
        }
    } catch {
        $row = @{query=$query; provider=$provider; url=$url; status='REQUEST_FAILED'; error=$_.Exception.Message}
    }
    $watch.Stop()
    $row.elapsedMs = $watch.ElapsedMilliseconds
    $row.observedAt = [DateTimeOffset]::Now.ToString('o')
    $row.scope = 'WORKSTATION_PROVIDER_PROBE_NOT_LOCKED_PHONE'
    [pscustomobject]$row
}
$outputPath = Join-Path $PSScriptRoot '../docs/benchmarks/locked-web-provider-probe.json'
$rows | ConvertTo-Json -Depth 15 | Set-Content -Encoding utf8 $outputPath
$rows | Select-Object query, status, elapsedMs, abstract, source, error, current | ConvertTo-Json -Depth 5
