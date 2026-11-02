# Formal engine validation (step A) для momentum+low-vol на 5 фьючерсах.
# Использует MomentumBacktestSignalGenerator через /backtest?momentumSignalEnabled=true.
# Сервер должен быть запущен с --bt.timeframe=DAY_1 (нативные DAY_1, без ресемпла).
#
# Запуск: & .\scripts\research_engine_momentum.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [int]$Days = 1600,
    [int]$MaxHoldBars = 4,
    [int]$SlPoints = 5000,
    [int]$TpPoints = 5000,
    [double]$RiskPerTradePercent = 100.0,
    [int]$MaxContracts = 1,
    [string]$OutDir = 'artifacts',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [string]$DevStart = '2024-10-24',
    [string]$OosStart = '2022-12-05',
    [string]$OosEnd = '2024-10-23'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

$allTrades = [System.Collections.Generic.List[object]]::new()
$tickerList = $Tickers.Split(',') | Where-Object { $_ -ne '' }
foreach ($ticker in $tickerList) {
    $url = "$BaseUrl/api/v1/backtest/$ticker" +
        "?days=$Days&timeframe=DAY_1&momentumSignalEnabled=true" +
        "&maxHoldBars=$MaxHoldBars&wfaSlPoints=$SlPoints&wfaTpPoints=$TpPoints" +
        "&riskPerTradePercent=$RiskPerTradePercent&futuresMaxContractsPerPosition=$MaxContracts" +
        "&includeTrades=true&loadHistory=false"
    Write-Host "GET $url"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-RestMethod -Uri $url -Headers $headers -TimeoutSec 900
    $sw.Stop()
    Write-Host ("{0}: trades={1} pf={2} ret={3} elapsed={4:N1}s" -f $ticker, $resp.totalTrades, $resp.profitFactor, $resp.totalReturn, $sw.Elapsed.TotalSeconds)
    $resp | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutDir ("engine_momentum_2026-11-02_{0}.json" -f $ticker)) -Encoding utf8
    foreach ($t in @($resp.trades)) {
        $entry = if ($t.entryTime) { [datetime]$t.entryTime } else { $null }
        $exit = if ($t.exitTime) { [datetime]$t.exitTime } else { $null }
        $allTrades.Add([pscustomobject]@{
                ticker = $ticker
                direction = $t.direction
                entryTime = $t.entryTime
                exitTime = $t.exitTime
                exitDate = if ($exit) { $exit.ToString('yyyy-MM-dd') } else { $null }
                closeReason = $t.closeReason
                pnl = [double]$t.pnl
                commission = [double]$t.commission
                funding = [double]$t.funding
                holdBars = [int]$t.holdBars
            })
    }
}

foreach ($t in $allTrades) {
    $entry = [datetime]$t.entryTime
    $t | Add-Member -NotePropertyName period -NotePropertyValue $(if ($entry -ge [datetime]::ParseExact($DevStart, 'yyyy-MM-dd', $inv)) { 'DEV' } elseif ($entry -ge [datetime]::ParseExact($OosStart, 'yyyy-MM-dd', $inv) -and $entry -le [datetime]::ParseExact($OosEnd, 'yyyy-MM-dd', $inv).AddDays(1)) { 'OOS' } else { 'OTHER' }) -Force
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

$devTrades = @($allTrades | Where-Object { $_.period -eq 'DEV' })
$oosTrades = @($allTrades | Where-Object { $_.period -eq 'OOS' })
$devStats = Stats $devTrades
$devBlock = Test-BlockNoEdge $devTrades $BootstrapIterations $Seed
$oosStats = Stats $oosTrades
$oosBlock = Test-BlockNoEdge $oosTrades $BootstrapIterations $Seed
$perInstrument = @{}
foreach ($t in $tickerList) { $perInstrument[$t] = Stats @($devTrades | Where-Object { $_.ticker -eq $t }) }

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    days = $Days
    maxHoldBars = $MaxHoldBars
    slPoints = $SlPoints
    tpPoints = $TpPoints
    riskPerTradePercent = $RiskPerTradePercent
    maxContracts = $MaxContracts
    devStats = $devStats
    devBlockP = [math]::Round($devBlock.p, 4)
    devBlockCi = @([math]::Round($devBlock.ciLow, 2), [math]::Round($devBlock.ciHigh, 2))
    oosStats = $oosStats
    oosBlockP = [math]::Round($oosBlock.p, 4)
    oosBlockCi = @([math]::Round($oosBlock.ciLow, 2), [math]::Round($oosBlock.ciHigh, 2))
    perInstrument = $perInstrument
    note = 'Engine validation: MomentumBacktestSignalGenerator, maxHold=4 bars, wide SL/TP, 1 contract; aggregate by block bootstrap.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'engine_momentum_2026-11-02_summary.json') -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'engine_momentum_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'engine_momentum_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8

Write-Host "=== DEV ==="
$devStats | Format-List
Write-Host ("block p={0} ci=[{1};{2}]" -f $devBlock.p, $devBlock.ciLow, $devBlock.ciHigh)
Write-Host "=== OOS ==="
$oosStats | Format-List
Write-Host ("block p={0} ci=[{1};{2}]" -f $oosBlock.p, $oosBlock.ciLow, $oosBlock.ciHigh)
Write-Host "=== PER INSTRUMENT (DEV) ==="
$tickerList | ForEach-Object { [pscustomobject]@{ ticker = $_; trades = $perInstrument[$_].trades; pf = $perInstrument[$_].pf; net = $perInstrument[$_].net; avgNet = $perInstrument[$_].avgNet } } | Format-Table -AutoSize
