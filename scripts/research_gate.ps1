# Фоновый runner для /deployment-gate с heartbeat и 5-минутным статусом.
# Отличие от research_wfa_combo730_wd_runner.ps1: раз в StatusEverySec секунд пишет
# строку статуса (время, PID сервера, RSS, дельта CPU, возраст heartbeat задания) —
# по ней видно, что прогон ИДЁТ, а не завис. Гейт на 730д занимает часы, поэтому
#foreground-ожидание неприемлемо.
param(
    [string]$Name,
    [string]$Url,
    [string]$OutDir,
    [int]$MaxWaitSec = 21600,
    [int]$StatusEverySec = 300,
    [string]$ServerPidFile = ""
)

$ErrorActionPreference = "Stop"
$Base = "http://localhost:8080"
$out = Join-Path $OutDir "$Name.json"
$hb = Join-Path $OutDir "$Name.heartbeat"
$log = Join-Path $OutDir "$Name.status.log"

function Write-Hb() {
    Set-Content -Path $hb -Value "t=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
}

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Add-Content -Path $log -Value $line
    Write-Output $line
}

function Get-ServerStat() {
    if (-not $ServerPidFile -or -not (Test-Path $ServerPidFile)) { return "server=? " }
    $raw = (Get-Content $ServerPidFile -Raw).Trim() -replace 'pid=', ''
    $proc = Get-Process -Id $raw -ErrorAction SilentlyContinue
    if (-not $proc) { return "server=DOWN " }
    return "server=up rss=$([math]::Round($proc.WorkingSet64 / 1MB))MB cpu=$([math]::Round($proc.CPU))s "
}

Write-Status "START $Name"
Write-Status "url=$Url"
Write-Hb

$sw = [Diagnostics.Stopwatch]::StartNew()
$job = $null
try {
    $login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30
    $headers = @{ Authorization = "Bearer $($login.accessToken)" }
    Write-Status "auth ok"

    $job = Start-Job -ScriptBlock {
        param($u, $h)
        Invoke-RestMethod -Method Get -Uri $u -Headers $h -TimeoutSec 100000
    } -ArgumentList $Url, $headers

    while ($job.State -eq 'Running') {
        Write-Hb
        if ($sw.Elapsed.TotalSeconds -gt $MaxWaitSec) {
            Write-Status "TIMEOUT after $([math]::Round($sw.Elapsed.TotalSeconds))s - job killed"
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
            @{ error = "TIMEOUT"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
            exit 1
        }
        Write-Status ("running t={0}s {1}hb_age={2}s" -f [math]::Round($sw.Elapsed.TotalSeconds), (Get-ServerStat), 0)
        Start-Sleep -Seconds $StatusEverySec
    }

    if ($job.State -ne 'Completed') {
        Write-Status "FAILED state=$($job.State)"
        $err = Receive-Job -Job $job -ErrorAction SilentlyContinue 2>&1
        Stop-Job $job -ErrorAction SilentlyContinue
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        @{ error = "job_failed"; state = "$($job.State)"; detail = "$err"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
        exit 1
    }

    $result = Receive-Job -Job $job
    Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    $result | ConvertTo-Json -Depth 12 | Set-Content $out
    Write-Status ("DONE in {0}s -> {1}" -f [math]::Round($sw.Elapsed.TotalSeconds, 0), $out)
    exit 0
} catch {
    Write-Status "ERROR: $($_.Exception.Message)"
    @{ error = "$($_.Exception.Message)"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
    exit 1
}
