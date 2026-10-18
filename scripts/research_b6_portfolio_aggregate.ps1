# Research b6 (PLAN-001 §5.2): portfolio OOS aggregation WFA 730d
#
# Usage:
#   .\scripts\research_b6_portfolio_aggregate.ps1 -CollectOnly -Tickers "USDRUBF,IMOEXF,EURRUBF,CNYRUBF" -Days 730 -Folds 8 -Conf 0.60
#   .\scripts\research_b6_portfolio_aggregate.ps1 -AggregateOnly -Tickers "USDRUBF,IMOEXF,EURRUBF,CNYRUBF" -Days 730 -Folds 8 -Conf 0.60
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Tickers = "",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$Conf = 0.60,
    [double]$CapitalPerTicker = 100000,
    [string]$OutDir = "",
    [int]$TimeoutSec = 10800,
    [int]$BootstrapIters = 2000,
    [switch]$CollectOnly,
    [switch]$AggregateOnly
)

$ErrorActionPreference = "Stop"
[System.Threading.Thread]::CurrentThread.CurrentCulture = [System.Globalization.CultureInfo]::InvariantCulture
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
if (-not $OutDir) { $OutDir = Join-Path $root "artifacts" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (-not $Tickers) { throw "specify -Tickers" }

function Resolve-EnvValue([string]$name, [string]$def = "") {
    if (-not (Test-Path $envPath)) { return $def }
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

$script:accessToken = $null
function Get-Auth {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
        -Body (@{ username = (Resolve-EnvValue "AUTH_USER" "admin"); password = (Resolve-EnvValue "AUTH_PASSWORD" "admin123456") } | ConvertTo-Json)
}

function Invoke-GetWithAuth([string]$uri, [int]$timeout) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        if (-not $script:accessToken) { $script:accessToken = (Get-Auth).accessToken }
        try {
            $resp = Invoke-WebRequest -Method Get -Uri $uri -Headers @{ Authorization = "Bearer $script:accessToken" } -TimeoutSec $timeout
            return $resp.Content
        } catch {
            if ($_.Exception.Response.StatusCode.value__ -eq 401) {
                Write-Host "token expired, re-login"
                $script:accessToken = $null
                continue
            }
            throw
        }
    }
    throw "401 x3: $uri"
}

$hb = Join-Path $OutDir "b6_portfolio_aggregate.heartbeat"
function Write-Hb([string]$msg) {
    Set-Content -Path $hb -Value ("t={0} {1}" -f [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(), $msg)
    Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg)
}

$tickerList = @($Tickers.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ })

if (-not $AggregateOnly) {
    foreach ($t in $tickerList) {
        $json = Join-Path $OutDir ("b6_wfa_{0}_{1}d_f{2}.json" -f $t, $Days, $Folds)
        if (Test-Path $json) { Write-Hb ("SKIP {0}" -f $t); continue }
        Write-Hb ("START validate {0}" -f $t)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $uri = "$BaseUrl/api/v1/backtest/$t/validate?days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf&loadHistory=false&includeOosTrades=true"
        $raw = Invoke-GetWithAuth -uri $uri -timeout $TimeoutSec
        $sw.Stop()
        $parsed = $raw | ConvertFrom-Json
        if (-not ($parsed.PSObject.Properties.Name -contains "oosFoldTrades")) {
            throw "response missing oosFoldTrades - jar missing includeOosTrades"
        }
        Set-Content -Path $json -Value $raw
        Write-Hb ("DONE {0} secs={1}" -f $t, [math]::Round($sw.Elapsed.TotalSeconds,0))
    }
    if ($CollectOnly) { Write-Hb "COLLECT DONE"; exit 0 }
}

$all = New-Object System.Collections.Generic.List[object]
$tickerSummary = @()
foreach ($t in $tickerList) {
    $json = Join-Path $OutDir ("b6_wfa_{0}_{1}d_f{2}.json" -f $t, $Days, $Folds)
    if (-not (Test-Path $json)) { throw ("missing {0}" -f $json) }
    $p = Get-Content $json -Raw | ConvertFrom-Json
    if ($p.oosFoldTrades) {
        foreach ($fold in $p.oosFoldTrades) {
            foreach ($tr in $fold.trades) {
                $exitRaw = [string]$tr.exitTime
                if (-not $exitRaw) { $exitRaw = [string]$tr.entryTime }
                $day = if ($exitRaw.Length -ge 10) { $exitRaw.Substring(0,10) } else { "unknown" }
                $all.Add([pscustomobject]@{ ticker=$t; fold=[int]$fold.fold; exitDay=$day; pnl=[double]$tr.pnl })
            }
        }
    }
    $tickerSummary += [pscustomobject]@{ ticker=$t; oosTrades=[int]$p.oosTrades; oosPF=[math]::Round([double]$p.oosProfitFactor,3); oosRetPct=[math]::Round([double]$p.oosReturn*100,2); consistency=[math]::Round([double]$p.consistency,3); pNoEdge=[math]::Round([double]$p.oosProbabilityOfNoEdge,3); robust=[bool]$p.robust }
}

$n = $all.Count
if ($n -eq 0) { throw "no OOS trades" }
$pnls = @($all | ForEach-Object { $_.pnl })
$sumPos = ($pnls | Where-Object { $_ -gt 0 } | Measure-Object -Sum).Sum
$sumNeg = ($pnls | Where-Object { $_ -lt 0 } | Measure-Object -Sum).Sum
if (-not $sumPos) { $sumPos = 0.0 }
if (-not $sumNeg) { $sumNeg = 0.0 }
$pf = if ($sumNeg -lt 0) { [math]::Round($sumPos / [math]::Abs($sumNeg), 4) } else { [double]::PositiveInfinity }
$pnlTotal = [math]::Round(($pnls | Measure-Object -Sum).Sum, 2)
$retPct = [math]::Round(100.0 * $pnlTotal / ($tickerList.Count * $CapitalPerTicker), 3)

$foldsArr = @()
for ($k = 0; $k -lt $Folds; $k++) {
    $fp = ($all | Where-Object { $_.fold -eq $k } | ForEach-Object { $_.pnl } | Measure-Object -Sum).Sum
    if (-not $fp) { $fp = 0.0 }
    $foldsArr += [pscustomobject]@{ fold=$k; trades=@($all | Where-Object { $_.fold -eq $k }).Count; pnl=[math]::Round($fp,2) }
}
$consistency = [math]::Round(@($foldsArr | Where-Object { $_.pnl -gt 0 }).Count / [double]$Folds, 3)

function Get-Percentile([double[]]$sorted, [double]$q) {
    $idx = [int][math]::Floor($q * ($sorted.Count - 1))
    return $sorted[$idx]
}

$rng = [System.Random]::new(42)
$naiveMeans = New-Object double[] $BootstrapIters
for ($it = 0; $it -lt $BootstrapIters; $it++) {
    $s = 0.0
    for ($j = 0; $j -lt $n; $j++) { $s += $pnls[$rng.Next($n)] }
    $naiveMeans[$it] = $s / $n
}
$naiveSorted = @($naiveMeans | Sort-Object)
$naivePNoEdge = [math]::Round(@($naiveMeans | Where-Object { $_ -le 0 }).Count / [double]$BootstrapIters, 4)
$naiveCiLow = [math]::Round((Get-Percentile $naiveSorted 0.025), 3)
$naiveCiHigh = [math]::Round((Get-Percentile $naiveSorted 0.975), 3)

$dayGroups = @($all | Group-Object -Property exitDay)
$dayCount = $dayGroups.Count
$blockMeans = New-Object double[] $BootstrapIters
for ($it = 0; $it -lt $BootstrapIters; $it++) {
    $s = 0.0; $c = 0
    for ($b = 0; $b -lt $dayCount; $b++) {
        $grp = $dayGroups[$rng.Next($dayCount)]
        foreach ($tr in $grp.Group) { $s += $tr.pnl; $c++ }
    }
    $blockMeans[$it] = if ($c -gt 0) { $s / $c } else { 0.0 }
}
$blockSorted = @($blockMeans | Sort-Object)
$blockPNoEdge = [math]::Round(@($blockMeans | Where-Object { $_ -le 0 }).Count / [double]$BootstrapIters, 4)
$blockCiLow = [math]::Round((Get-Percentile $blockSorted 0.025), 3)
$blockCiHigh = [math]::Round((Get-Percentile $blockSorted 0.975), 3)

$criteria = @(
    [pscustomobject]@{ name = "oosTrades>=100"; value = $n; passed = ($n -ge 100) },
    [pscustomobject]@{ name = "pf>=1.30"; value = $pf; passed = ($pf -ge 1.30) },
    [pscustomobject]@{ name = "pNoEdge(block)<0.05"; value = $blockPNoEdge; passed = ($blockPNoEdge -lt 0.05) },
    [pscustomobject]@{ name = "ci95Low(block)>0"; value = $blockCiLow; passed = ($blockCiLow -gt 0) },
    [pscustomobject]@{ name = "consistency>=0.60"; value = $consistency; passed = ($consistency -ge 0.60) },
    [pscustomobject]@{ name = "return>=0"; value = $retPct; passed = ($retPct -ge 0) }
)
$verdict = if (@($criteria | Where-Object { -not $_.passed }).Count -eq 0) { "WFA_PART_PASS" } else { "REJECTED" }

$result = [pscustomobject]@{
    config        = @{ tickers = $tickerList; days = $Days; folds = $Folds; conf = $Conf; capitalPerTicker = $CapitalPerTicker; bootstrapIters = $BootstrapIters }
    portfolio     = [pscustomobject]@{
        tradesTotal = $n; pnlTotalRub = $pnlTotal; returnPct = $retPct; profitFactor = $pf
        consistency = $consistency; blockDays = $dayCount
        naive       = [pscustomobject]@{ pNoEdge = $naivePNoEdge; ci95Low = $naiveCiLow; ci95High = $naiveCiHigh }
        block       = [pscustomobject]@{ pNoEdge = $blockPNoEdge; ci95Low = $blockCiLow; ci95High = $blockCiHigh }
    }
    folds         = $foldsArr
    perTicker     = $tickerSummary
    criteria      = $criteria
    verdict       = $verdict
    timestamp     = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
}
$outJson = Join-Path $OutDir ("b6_portfolio_{0}d_f{1}.json" -f $Days, $Folds)
$result | ConvertTo-Json -Depth 8 | Set-Content -Path $outJson
Write-Hb ("ALL DONE verdict={0}" -f $verdict)