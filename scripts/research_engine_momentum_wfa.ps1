# WFA-часть шага A: momentum+low-vol на 5 фьючерсах через /validate.
# folds=3 (не 8): при 1116 DAY_1 барах и warmup 270 баров 8 фолдов дают
# сегмент ~139 < warmup -> 0 OOS-сделок (урок docs/26 §11.9). folds=3 даёт
# сегмент ~372 бара и ~100 сигнальных баров на фолд.
#
# Запуск: & .\scripts\research_engine_momentum_wfa.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [int]$Days = 2000,
    [int]$Folds = 3,
    [int]$MaxHoldBars = 4,
    [int]$SlPoints = 5000,
    [int]$TpPoints = 5000,
    [double]$RiskPerTradePercent = 100.0,
    [int]$MaxContracts = 1,
    [string]$OutDir = 'artifacts',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$allTrades = [System.Collections.Generic.List[object]]::new()
$tickerList = $Tickers.Split(',') | Where-Object { $_ -ne '' }
$perTicker = @{}
foreach ($ticker in $tickerList) {
    $url = "$BaseUrl/api/v1/backtest/$ticker/validate" +
        "?days=$Days&timeframe=DAY_1&folds=$Folds&momentumSignalEnabled=true" +
        "&maxHoldBars=$MaxHoldBars&wfaSlPoints=$SlPoints&wfaTpPoints=$TpPoints" +
        "&riskPerTradePercent=$RiskPerTradePercent&futuresMaxContractsPerPosition=$MaxContracts" +
        "&includeOosTrades=true&loadHistory=false"
    Write-Host "GET $url"
    $resp = Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec 900
    $perTicker[$ticker] = [pscustomobject]@{
        folds = $resp.folds
        consistency = $resp.consistency
        oosTrades = $resp.oosTrades
        oosPF = $resp.oosProfitFactor
        oosP = $resp.oosProbabilityOfNoEdge
        ciLow = $resp.oosMeanTradeCI95Low
        ciHigh = $resp.oosMeanTradeCI95High
    }
    Write-Host ("{0}: folds={1} cons={2} oosTrades={3} oosPF={4} oosP={5}" -f $ticker, $resp.folds, $resp.consistency, $resp.oosTrades, $resp.oosProfitFactor, $resp.oosProbabilityOfNoEdge)
    foreach ($fold in @($resp.oosFoldTrades)) {
        foreach ($t in @($fold.trades)) {
            $entry = [datetime]$t.entryTime
            $exit = if ($t.exitTime) { [datetime]$t.exitTime } else { $null }
            $allTrades.Add([pscustomobject]@{
                    ticker = $ticker
                    fold = $fold.fold
                    direction = $t.direction
                    entryTime = $t.entryTime
                    exitTime = $t.exitTime
                    exitDate = if ($exit) { $exit.ToString('yyyy-MM-dd') } else { $null }
                    closeReason = $t.closeReason
                    pnl = [double]$t.pnl
                })
        }
    }
}

function Quantile([double[]]$values, [double]$q) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return $s[$idx]
}

function Test-BlockNoEdge($items, [int]$iterations, [int]$seed) {
    $arr = @($items)
    if ($arr.Count -lt 2) { return [pscustomobject]@{ p = 1.0; ciLow = 0; ciHigh = 0; blocks = 0 } }
    $blocks = @($arr | Group-Object exitDate | ForEach-Object { , [double[]]@($_.Group | ForEach-Object { $_.pnl }) })
    if ($blocks.Count -lt 2) { return [pscustomobject]@{ p = 1.0; ciLow = 0; ciHigh = 0; blocks = 0 } }
    $rnd = [System.Random]::new($seed)
    $means = [double[]]::new($iterations)
    for ($k = 0; $k -lt $iterations; $k++) {
        $sum = 0.0; $n = 0
        for ($b = 0; $b -lt $blocks.Count; $b++) {
            $block = $blocks[$rnd.Next(0, $blocks.Count)]
            foreach ($x in $block) { $sum += $x; $n++ }
        }
        $means[$k] = if ($n -gt 0) { $sum / $n } else { 0.0 }
    }
    $le = 0
    foreach ($m in $means) { if ($m -le 0) { $le++ } }
    return [pscustomobject]@{
        p = $le / [double]$iterations
        ciLow = Quantile $means 0.025
        ciHigh = Quantile $means 0.975
        blocks = $blocks.Count
    }
}

function Stats($items) {
    $arr = @($items)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{ trades = 0; net = 0; pf = 0; winRate = 0; avgNet = 0; medianNet = 0; mddPct = 0 }
    }
    $pnls = [double[]]@($arr | ForEach-Object { $_.pnl })
    $wins = @($arr | Where-Object { $_.pnl -gt 0 })
    $losses = @($arr | Where-Object { $_.pnl -le 0 })
    $sumWin = ($wins | Measure-Object -Property pnl -Sum).Sum
    $sumLoss = [math]::Abs(($losses | Measure-Object -Property pnl -Sum).Sum)
    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    $net = ($pnls | Measure-Object -Sum).Sum
    $avg = ($pnls | Measure-Object -Average).Average
    $median = Quantile $pnls 0.5
    $cum = 0.0; $peak = 0.0; $mdd = 0.0
    foreach ($x in $pnls) { $cum += $x; if ($cum -gt $peak) { $peak = $cum }; $dd = $peak - $cum; if ($dd -gt $mdd) { $mdd = $dd } }
    [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round($net, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins.Count / $arr.Count, 1)
        avgNet = [math]::Round($avg, 2)
        medianNet = [math]::Round($median, 2)
        mddPct = [math]::Round($mdd / 1000.0, 2)
    }
}

$stats = Stats $allTrades
$block = Test-BlockNoEdge $allTrades $BootstrapIterations $Seed
$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    folds = $Folds
    days = $Days
    maxHoldBars = $MaxHoldBars
    slPoints = $SlPoints
    tpPoints = $TpPoints
    perTicker = $perTicker
    portfolio = $stats
    blockP = [math]::Round($block.p, 4)
    blockCi = @([math]::Round($block.ciLow, 2), [math]::Round($block.ciHigh, 2))
    blocks = $block.blocks
    note = 'WFA folds=3 (geometry: 1116 DAY_1 bars, warmup 270; folds=8 degenerate). Portfolio = aggregate OOS fold trades.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'engine_momentum_wfa_2026-11-02_summary.json') -Encoding utf8
$allTrades | Export-Csv -LiteralPath (Join-Path $OutDir 'engine_momentum_wfa_2026-11-02_trades.csv') -NoTypeInformation -Encoding utf8

Write-Host "=== PORTFOLIO WFA ==="
$stats | Format-List
Write-Host ("block p={0} ci=[{1};{2}] blocks={3}" -f $block.p, $block.ciLow, $block.ciHigh, $block.blocks)
