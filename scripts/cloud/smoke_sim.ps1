# Smoke-проверка SIM-деплоя в Yandex Cloud (docs/55).
#
# Пример:
#   pwsh -File scripts/cloud/smoke_sim.ps1 `
#     -BaseUrl http://<vm-ip>:8080 -AuthUser admin -Password '<AUTH_PASSWORD>'
#
# Проверяет публичные probes (Docker healthcheck), вход по JWT и два
# read-only API-эндпоинта. Секреты не печатает.
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,
    [string]$AuthUser = 'admin',
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [int]$TimeoutSec = 30
)

$ErrorActionPreference = 'Stop'
$base = $BaseUrl.TrimEnd('/')
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $results.Add([pscustomobject]@{ Check = $Name; Ok = $Ok; Detail = $Detail })
    $status = if ($Ok) { 'PASS' } else { 'FAIL' }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail)
}

function Invoke-Check {
    param([string]$Name, [scriptblock]$Action)
    try {
        $detail = & $Action
        Add-Result -Name $Name -Ok $true -Detail ([string]$detail)
        return $true
    } catch {
        Add-Result -Name $Name -Ok $false -Detail $_.Exception.Message
        return $false
    }
}

$probes = @(
    @{ Name = 'liveness'; Path = '/actuator/health/liveness' },
    @{ Name = 'readiness'; Path = '/actuator/health/readiness' },
    @{ Name = 'health'; Path = '/actuator/health' }
)
foreach ($probe in $probes) {
    Invoke-Check "actuator/$($probe.Name)" {
        $response = Invoke-WebRequest -Uri "$base$($probe.Path)" -TimeoutSec $TimeoutSec -UseBasicParsing
        if ([int]$response.StatusCode -ne 200) { throw "HTTP $($response.StatusCode)" }
        "HTTP 200"
    } | Out-Null
}

$token = $null
$loginOk =
    Invoke-Check 'auth/login' {
        $body = @{ username = $AuthUser; password = $Password } | ConvertTo-Json
        $response =
            Invoke-RestMethod -Uri "$base/api/v1/auth/login" -Method Post `
                -ContentType 'application/json' -Body $body -TimeoutSec $TimeoutSec
        $script:token = $response.accessToken
        if ([string]::IsNullOrWhiteSpace($script:token)) { throw 'empty accessToken' }
        'accessToken received'
    }

if ($loginOk) {
    $headers = @{ Authorization = "Bearer $token" }
    Invoke-Check 'api/trading/status' {
        $response = Invoke-RestMethod -Uri "$base/api/v1/trading/status" -Headers $headers -TimeoutSec $TimeoutSec
        if ($null -eq $response) { throw 'empty response' }
        'JSON received'
    } | Out-Null
    Invoke-Check 'api/dashboard' {
        $response = Invoke-RestMethod -Uri "$base/api/v1/dashboard" -Headers $headers -TimeoutSec $TimeoutSec
        if ($null -eq $response) { throw 'empty response' }
        'JSON received'
    } | Out-Null
}

$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ''
Write-Host ("Итого: {0}/{1} проверок пройдено" -f ($results.Count - $failed.Count), $results.Count)
if ($failed.Count -gt 0) {
    Write-Host 'Провалено:' -ForegroundColor Red
    $failed | ForEach-Object { Write-Host ("  - {0}: {1}" -f $_.Check, $_.Detail) -ForegroundColor Red }
    exit 1
}
Write-Host 'SMOKE OK' -ForegroundColor Green
