param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60
)

# WFA-калибровка входных фильтров (docs/17 этап 4c, pt.2): session-фильтр (вход
# только в торговые фазы) и pullback-фильтр к EMA (не гнаться за ценой).
# Query-параметры `sessionFilter*`/`pullbackFilter*` переопределяют `bt.*`.
$ErrorActionPreference = "Stop"
$envPath = Join-Path (Split-Path -Parent $PSScriptRoot) ".env"

if (-not (Test-Path $envPath)) {
    throw ".env not found at $envPath"
}
function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"
if (-not $authUser -or -not $authPassword) {
    throw "AUTH_USER/AUTH_PASSWORD not set in $envPath"
}

function Get-Token {
    $loginBody = @{ username = $authUser; password = $authPassword } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body $loginBody
    return @{ Authorization = "Bearer $($login.accessToken)" }
}

$headers = Get-Token

# Кандидаты: baseline (фильтры off) и сетки session/pullback.
$candidates =
@(
    "baseline"
    "sessionFilterEnabled=true&sessionFilterStartMinutes=630&sessionFilterEndMinutes=1005"
    "sessionFilterEnabled=true&sessionFilterStartMinutes=660&sessionFilterEndMinutes=960"
    "sessionFilterEnabled=true&sessionFilterStartMinutes=600&sessionFilterEndMinutes=840"
    "sessionFilterEnabled=true&sessionFilterStartMinutes=840&sessionFilterEndMinutes=1080"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.3"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=1.0"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=2.0"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=1.0&pullbackBlockOnUnknown=true"
    "pullbackFilterEnabled=true&pullbackEmaPeriod=10&pullbackMaxDeviationPercent=0.5"
    "sessionFilterEnabled=true&sessionFilterStartMinutes=660&sessionFilterEndMinutes=960&pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5"
)

$rows = @()
foreach ($sq in $candidates) {
    $query = if ($sq -eq "baseline") { "" } else { "&$sq" }
    $suffix = "/api/v1/backtest/$Ticker/validate`?loadHistory=false&days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf$query"
    $full = "$BaseUrl$suffix"
    Write-Host "GET $full"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $full -Headers $headers -TimeoutSec 1200
        $sw.Stop()
        $rows += [pscustomobject]@{
            Config = $sq
            Robust = $r.robust
            Consistency = $r.consistency
            RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
            Sharpe = [math]::Round([double]$r.oosSharpe, 2)
            PF = [math]::Round([double]$r.oosProfitFactor, 2)
            Trades = $r.oosTrades
            Edge = $r.oosEdgeStatisticallySignificant
            Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
        }
    } catch {
        # Токен мог истечь (JWT короткий) — перелогиниться и повторить один раз.
        $headers = Get-Token
        try {
            $r = Invoke-RestMethod -Method Get -Uri $full -Headers $headers -TimeoutSec 1200
            $sw.Stop()
            $rows += [pscustomobject]@{
                Config = $sq
                Robust = $r.robust
                Consistency = $r.consistency
                RetPct = [math]::Round([double]$r.oosReturn * 100, 2)
                Sharpe = [math]::Round([double]$r.oosSharpe, 2)
                PF = [math]::Round([double]$r.oosProfitFactor, 2)
                Trades = $r.oosTrades
                Edge = $r.oosEdgeStatisticallySignificant
                Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
        } catch {
            $sw.Stop()
            Write-Host "  FAILED: $($_.Exception.Message)"
            $rows += [pscustomobject]@{
                Config = $sq; Robust = $null; Consistency = $null; RetPct = $null
                Sharpe = $null; PF = $null; Trades = $null; Edge = $null; Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
            }
        }
    }
}

$rows | Format-Table -AutoSize