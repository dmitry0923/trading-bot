param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60,
    [switch]$SkipWfa,
    [switch]$SkipIs
)

# IS-скрининг + WFA-калибровка входных фильтров (docs/17 этап 4c, pt.2):
# session-фильтр (вход только в торговые фазы) и pullback-фильтр к EMA
# (не гнаться за ценой). Query-параметры `sessionFilter*`/`pullbackFilter*`
# переопределяют `bt.*` (паттерн funding-veto/ml-direction).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

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

Write-Host "Authenticating as $authUser..."
$loginBody = @{ username = $authUser; password = $authPassword } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Invoke-Bt([string]$suffix) {
    $full = "$BaseUrl$suffix"
    Write-Host "  GET $full"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-RestMethod -Method Get -Uri $full -Headers $headers -TimeoutSec 1200
        $sw.Stop()
        return [pscustomobject]@{
            ok = $true; ret = $r.totalReturn; pf = $r.profitFactor; trades = $r.totalTrades
            mdd = $r.maxDrawdown; sharpe = $r.sharpeRatio; secs = [math]::Round($sw.Elapsed.TotalSeconds, 1)
            robust = $r.robust; consistency = $r.consistency; oosRet = $r.oosReturn
            oosSharpe = $r.oosSharpe; oosPf = $r.oosProfitFactor; oosTrades = $r.oosTrades
            oosEdge = $r.oosEdgeStatisticallySignificant
        }
    } catch {
        $sw.Stop()
        Write-Host "  FAILED: $($_.Exception.Message)"
        return [pscustomobject]@{ ok = $false; ret = $null; pf = $null; trades = $null; mdd = $null; sharpe = $null; secs = [math]::Round($sw.Elapsed.TotalSeconds, 1); robust = $null; consistency = $null; oosRet = $null; oosSharpe = $null; oosPf = $null; oosTrades = $null; oosEdge = $null }
    }
}

# ---- IS-скрининг: /backtest по сетке (быстрый, история уже в БД) ----
$rows = @()
if (-not $SkipIs) {
$grid =
@(
    @{ name = "baseline (фильтры off)";        sq = "" },
    @{ name = "session 10:30-16:45";           sq = "sessionFilterEnabled=true&sessionFilterStartMinutes=630&sessionFilterEndMinutes=1005" },
    @{ name = "session 11:00-16:00";           sq = "sessionFilterEnabled=true&sessionFilterStartMinutes=660&sessionFilterEndMinutes=960" },
    @{ name = "session 10:00-14:00 (утро)";    sq = "sessionFilterEnabled=true&sessionFilterStartMinutes=600&sessionFilterEndMinutes=840" },
    @{ name = "session 14:00-18:00 (день)";    sq = "sessionFilterEnabled=true&sessionFilterStartMinutes=840&sessionFilterEndMinutes=1080" },
    @{ name = "pb ema20 dev=0.3%";             sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.3" },
    @{ name = "pb ema20 dev=0.5%";             sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5" },
    @{ name = "pb ema20 dev=1.0%";             sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=1.0" },
    @{ name = "pb ema20 dev=2.0%";             sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=2.0" },
    @{ name = "pb ema20 dev1.0 +blockUnknown"; sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=1.0&pullbackBlockOnUnknown=true" },
    @{ name = "pb ema10 dev=0.5%";             sq = "pullbackFilterEnabled=true&pullbackEmaPeriod=10&pullbackMaxDeviationPercent=0.5" },
    @{ name = "session 11-16 + pb ema20 0.5";  sq = "sessionFilterEnabled=true&sessionFilterStartMinutes=660&sessionFilterEndMinutes=960&pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5" }
)

Write-Host "`n[1] IS-screen /backtest days=$Days..."
$rows = @()
foreach ($g in $grid) {
    $suffix = "/api/v1/backtest/$Ticker`?loadHistory=false&days=$Days"
    if ($g.sq) { $suffix += "&" + $g.sq }
    $r = Invoke-Bt $suffix
    if ($r.ok) {
        $rows += [pscustomobject]@{
            Config = $g.name; Trades = $r.trades; PF = [math]::Round($r.pf, 2)
            RetPct = [math]::Round([double]$r.ret * 100, 2); MDDpct = [math]::Round([double]$r.mdd * 100, 2)
            Sharpe = [math]::Round($r.sharpe, 2); Secs = $r.secs
        }
    }
}
$rows | Format-Table -AutoSize
}

if ($SkipWfa) { Write-Host "Done (IS only)."; return }

# ---- WFA для кандидатов (OOS — решающий критерий; ~5-7 мин каждый) ----
$candidates =
@(
    "baseline",
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.3",
    "pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5",
    "sessionFilterEnabled=true&sessionFilterStartMinutes=840&sessionFilterEndMinutes=1080",
    "sessionFilterEnabled=true&sessionFilterStartMinutes=840&sessionFilterEndMinutes=1080&pullbackFilterEnabled=true&pullbackEmaPeriod=20&pullbackMaxDeviationPercent=0.5"
)

Write-Host "`n[2] WFA validate folds=$Folds conf=$Conf for top candidates..."
$wfaRows = @()
foreach ($sq in $candidates) {
    $query = if ($sq -eq "baseline") { "" } else { "&$sq" }
    $suffix = "/api/v1/backtest/$Ticker/validate`?loadHistory=false&days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf$query"
    $r = Invoke-Bt $suffix
    if ($r.ok) {
        $wfaRows += [pscustomobject]@{
            Config = $sq; Robust = $r.robust; Consistency = $r.consistency
            RetPct = [math]::Round([double]$r.oosRet * 100, 2); Sharpe = [math]::Round([double]$r.oosSharpe, 2)
            PF = [math]::Round([double]$r.oosPf, 2); Trades = $r.oosTrades; Secs = $r.secs
        }
    }
}
if ($wfaRows.Count) { $wfaRows | Format-Table -AutoSize }
Write-Host "Done."