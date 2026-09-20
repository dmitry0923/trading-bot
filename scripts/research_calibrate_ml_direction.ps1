param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.60
)

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
        $r = Invoke-RestMethod -Method Get -Uri $full -Headers $headers -TimeoutSec 900
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
$grid =
@(
    @{ name = "ml off (true baseline)"; sq = "mlDirectionEnabled=false" },
    @{ name = "m=0.03";                 sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.03" },
    @{ name = "m=0.05 (default)";       sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.05" },
    @{ name = "m=0.08";                 sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.08" },
    @{ name = "m=0.12";                 sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.12" },
    @{ name = "m=0.20";                 sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.20" },
    @{ name = "hor=10";                 sq = "mlDirectionEnabled=true&mlDirectionHorizonBars=10" },
    @{ name = "hor=15";                 sq = "mlDirectionEnabled=true&mlDirectionHorizonBars=15" },
    @{ name = "hor=3";                  sq = "mlDirectionEnabled=true&mlDirectionHorizonBars=3" },
    @{ name = "minRet=0.10";            sq = "mlDirectionEnabled=true&mlDirectionMinReturnPercent=0.10" },
    @{ name = "minRet=0.02";            sq = "mlDirectionEnabled=true&mlDirectionMinReturnPercent=0.02" },
    @{ name = "blockUnknown";           sq = "mlDirectionEnabled=true&mlDirectionBlockOnUnknown=true" },
    @{ name = "m=0.10+block";           sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.10&mlDirectionBlockOnUnknown=true" },
    @{ name = "m=0.08+hor=10";          sq = "mlDirectionEnabled=true&mlDirectionSignalMargin=0.08&mlDirectionHorizonBars=10" }
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

# ---- WFA для кандидатов (OOS — решающий критерий; ~7 мин каждый) ----
$candidates =
@(
    "mlDirectionEnabled=false",
    "mlDirectionEnabled=true",
    "mlDirectionEnabled=true&mlDirectionBlockOnUnknown=true",
    "mlDirectionEnabled=true&mlDirectionSignalMargin=0.10&mlDirectionBlockOnUnknown=true",
    "mlDirectionEnabled=true&mlDirectionHorizonBars=10"
)

Write-Host "`n[2] WFA validate folds=$Folds conf=$Conf for top candidates..."
$wfaRows = @()
foreach ($sq in $candidates) {
    $suffix = "/api/v1/backtest/$Ticker/validate`?loadHistory=false&days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&$sq"
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