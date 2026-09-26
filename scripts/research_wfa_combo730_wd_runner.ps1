# Watchdog-раннер одного WFA-прогона (730д, folds=8).
# HTTP-вызов выполняется в фоновом job'е; основной поток пишет heartbeat.
# Если job не завершился за $MaxWaitSec -> раннер сам выходит с error TIMEOUT.
param(
    [string]$Name,
    [string]$Url,
    [string]$OutDir,
    [int]$MaxWaitSec = 10500
)

$ErrorActionPreference = "Stop"
$Base = "http://localhost:8080"
$out = Join-Path $OutDir "$Name.json"
$hb = Join-Path $OutDir "$Name.heartbeat"

function Write-Hb() {
    Set-Content -Path $hb -Value "t=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
}
Write-Hb

function Get-Tok {
    $l = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 15
    return @{ Authorization = "Bearer $($l.accessToken)" }
}

$sw = [Diagnostics.Stopwatch]::StartNew()
try {
    $headers = Get-Tok
    $job = $null
    try {
        $job = Start-Job -ScriptBlock {
            param($u, $h)
            for ($i = 0; $i -lt 4; $i++) {
                try {
                    $r = Invoke-RestMethod -Method Get -Uri $u -Headers $h -TimeoutSec 100000
                    if ($null -ne $r) { return $r }
                } catch {
                    $resp = $_.Exception.Response
                    if ($resp -and [int]$resp.StatusCode -eq 401) { throw "auth_expired" }
                    if ($i -eq 3) { throw }
                    Start-Sleep -Seconds 3
                }
            }
            throw "no result"
        } -ArgumentList $Url, $headers

        while ($job.State -eq 'Running') {
            Write-Hb
            if ($sw.Elapsed.TotalSeconds -gt $MaxWaitSec) {
                Stop-Job $job -ErrorAction SilentlyContinue
                Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
                @{ error = "TIMEOUT"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
                exit 1
            }
            Start-Sleep -Seconds 60
        }
        if ($job.State -ne 'Completed') {
            $err = Receive-Job -Job $job -ErrorAction SilentlyContinue
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
            @{ error = "job_failed: $err"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
            exit 1
        }
        $r = Receive-Job -Job $job
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        $sw.Stop()
        if ($null -eq $r) {
            @{ error = "no result"; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
            exit 1
        }
        @{
            name = $Name
            consistency = $r.consistency
            oosReturn = [math]::Round([double]$r.oosReturn * 100, 2)
            oosPF = [math]::Round([double]$r.oosProfitFactor, 2)
            oosTrades = $r.oosTrades
            probNoEdge = [math]::Round([double]$r.oosProbabilityOfNoEdge, 3)
            robust = $r.robust
            secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        } | ConvertTo-Json | Set-Content $out
        exit 0
    } finally {
        if ($job) {
            Stop-Job $job -ErrorAction SilentlyContinue
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    $sw.Stop()
    @{ error = $_.Exception.Message; secs = [math]::Round($sw.Elapsed.TotalSeconds, 0) } | ConvertTo-Json | Set-Content $out
    exit 1
}