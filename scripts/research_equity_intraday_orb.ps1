# Pre-screen intraday ORB на акциях TQBR (docs/51, 2026-11-02).
# Один вариант: opening range 10:00-10:30, вход на пробое, выход по close дня.
# Pooled по 9 инструментам; dev 70% / OOS 30% по календарным датам.
# Издержки: 0.05%/сторона + 1 bp (stress: 0.10%/сторона + 3 bp).
#
# Запуск: & .\scripts\research_equity_intraday_orb.ps1
param(
    [string]$Tickers = 'GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN,VTBR',
    [string]$StartDate = '2024-10-24',
    [double]$Notional = 100000,
    [double]$CommissionPctPerSide = 0.05,
    [double]$SlippageBps = 1.0,
    [double]$StressCommissionPctPerSide = 0.10,
    [double]$StressSlippageBps = 3.0,
    [double]$DevFraction = 0.70,
    [int]$MinBarsPerDay = 5,
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [string]$OutDir = 'artifacts',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$tickerList = @($Tickers.Split(',') | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
$orStart = [timespan]::FromHours(10)
$orEnd = [timespan]::FromMinutes(630)
$baseCommRate = $CommissionPctPerSide / 100.0
$baseSlipRate = $SlippageBps / 10000.0
$stressCommRate = $StressCommissionPctPerSide / 100.0
$stressSlipRate = $StressSlippageBps / 10000.0

function Get-Quantile([double[]]$values, [double]$q) {
    if ($null -eq $values -or $values.Count -eq 0) { return 0.0 }
    $s = @($values | Sort-Object)
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return [double]$s[$idx]
}

function Get-Stats($trades) {
    $arr = @($trades)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{
            trades = 0; net = 0.0; pf = 0.0; winRate = 0.0; avgNet = 0.0; medianNet = 0.0
            ciLow = 0.0; ciHigh = 0.0; pNoEdge = 1.0; mddRub = 0.0; mddPct = 0.0
            avgCapturedPct = 0.0; avgDirectionalEdgePct = 0.0; avgHoldBars = 0.0
        }
    }
    $nets = [double[]]@($arr | ForEach-Object { $_.net })
    $wins = @($arr | Where-Object { $_.net -gt 0 })
    $losses = @($arr | Where-Object { $_.net -le 0 })
    $sumWin = ($wins | Measure-Object -Property net -Sum).Sum
    $sumLoss = [math]::Abs(($losses | Measure-Object -Property net -Sum).Sum)
    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    $net = ($nets | Measure-Object -Sum).Sum
    $avg = ($nets | Measure-Object -Average).Average
    $median = Get-Quantile $nets 0.5

    $cum = 0.0; $peak = 0.0; $mdd = 0.0
    foreach ($t in ($arr | Sort-Object { $_.entryTime })) {
        $cum += $t.net
        if ($cum -gt $peak) { $peak = $cum }
        $dd = $peak - $cum
        if ($dd -gt $mdd) { $mdd = $dd }
    }

    # date-block bootstrap: одновременные сделки одного дня не считаются независимыми
    $byDate = @{}
    foreach ($t in $arr) {
        if (-not $byDate.ContainsKey($t.date)) {
            $byDate[$t.date] = [System.Collections.Generic.List[double]]::new()
        }
        $byDate[$t.date].Add([double]$t.net)
    }
    $dateKeys = @($byDate.Keys)
    $nd = $dateKeys.Count
    $means = [double[]]::new($BootstrapIterations)
    $rnd = [System.Random]::new($Seed)
    for ($k = 0; $k -lt $BootstrapIterations; $k++) {
        $sum = 0.0; $cnt = 0
        for ($j = 0; $j -lt $nd; $j++) {
            $key = $dateKeys[$rnd.Next(0, $nd)]
            foreach ($x in $byDate[$key]) { $sum += $x; $cnt++ }
        }
        $means[$k] = if ($cnt -gt 0) { $sum / $cnt } else { 0.0 }
    }
    $pNoEdge = @($means | Where-Object { $_ -le 0 }).Count / [double]$BootstrapIterations

    $captured = @($arr | Where-Object { $null -ne $_.capturedPct } | ForEach-Object { $_.capturedPct })
    $dirEdge = @($arr | Where-Object { $null -ne $_.directionalEdgePct } | ForEach-Object { $_.directionalEdgePct })
    [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round($net, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins.Count / $arr.Count, 1)
        avgNet = [math]::Round($avg, 2)
        medianNet = [math]::Round($median, 2)
        ciLow = [math]::Round((Get-Quantile $means 0.025), 2)
        ciHigh = [math]::Round((Get-Quantile $means 0.975), 2)
        pNoEdge = [math]::Round($pNoEdge, 4)
        mddRub = [math]::Round($mdd, 2)
        mddPct = [math]::Round($mdd / $Notional * 100.0, 2)
        avgCapturedPct = [math]::Round((($captured | Measure-Object -Average).Average), 2)
        avgDirectionalEdgePct = [math]::Round((($dirEdge | Measure-Object -Average).Average), 2)
        avgHoldBars = [math]::Round((($arr | ForEach-Object { $_.holdBars }) | Measure-Object -Average).Average, 1)
    }
}

function Get-SimpleStats($trades) {
    $arr = @($trades)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{ trades = 0; net = 0.0; pf = 0.0; winRate = 0.0; avgNet = 0.0 }
    }
    $nets = [double[]]@($arr | ForEach-Object { $_.net })
    $wins = @($arr | Where-Object { $_.net -gt 0 })
    $losses = @($arr | Where-Object { $_.net -le 0 })
    $sumWin = ($wins | Measure-Object -Property net -Sum).Sum
    $sumLoss = [math]::Abs(($losses | Measure-Object -Property net -Sum).Sum)
    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round(($nets | Measure-Object -Sum).Sum, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins.Count / $arr.Count, 1)
        avgNet = [math]::Round(($nets | Measure-Object -Average).Average, 2)
    }
}

function Get-FoldConsistency($trades) {
    $arr = @($trades)
    if ($arr.Count -eq 0) { return [pscustomobject]@{ consistency = 0.0; folds = @() } }
    $dates = @($arr | ForEach-Object { $_.date } | Sort-Object -Unique)
    $n = $dates.Count
    $folds = @()
    $start = 0
    for ($f = 0; $f -lt 3; $f++) {
        $end = if ($f -eq 2) { $n - 1 } else { [math]::Min($n - 1, [int][math]::Floor(($f + 1) * $n / 3.0) - 1) }
        if ($end -lt $start) { $end = $start }
        $foldDates = @($dates[$start..$end])
        $set = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($d in $foldDates) { [void]$set.Add($d) }
        $foldTrades = @($arr | Where-Object { $set.Contains($_.date) })
        $foldNet = if ($foldTrades.Count -gt 0) { ($foldTrades | Measure-Object -Property net -Sum).Sum } else { 0.0 }
        $folds += [pscustomobject]@{
            fold = $f + 1
            dates = "$($foldDates[0])..$($foldDates[-1])"
            trades = $foldTrades.Count
            net = [math]::Round($foldNet, 2)
        }
        $start = $end + 1
    }
    $positive = @($folds | Where-Object { $_.net -gt 0 }).Count
    return [pscustomobject]@{ consistency = [math]::Round($positive / 3.0, 3); folds = $folds }
}

$allTrades = [System.Collections.Generic.List[object]]::new()
$allDates = [System.Collections.Generic.HashSet[string]]::new()

foreach ($ticker in $tickerList) {
    $sql = @"
select to_char(time, 'YYYY-MM-DD HH24:MI:SS') || '|' ||
       open_price::text || '|' || high_price::text || '|' || low_price::text || '|' || close_price::text
from candles
where ticker = '$ticker' and timeframe = 'MINUTE_10'
  and time >= '$StartDate' and time < current_date
order by time;
"@
    $raw = $sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    $byDay = [ordered]@{}
    foreach ($line in ($raw -split "`n")) {
        $s = $line.Trim()
        if ($s -eq '') { continue }
        $p = $s -split '\|'
        if ($p.Count -ne 5) { continue }
        $dt = [datetime]::ParseExact($p[0], 'yyyy-MM-dd HH:mm:ss', $inv)
        $key = $dt.ToString('yyyy-MM-dd')
        if (-not $byDay.Contains($key)) { $byDay[$key] = [System.Collections.Generic.List[object]]::new() }
        $byDay[$key].Add([pscustomobject]@{
                dt = $dt
                open = [double]::Parse($p[1], $inv)
                high = [double]::Parse($p[2], $inv)
                low = [double]::Parse($p[3], $inv)
                close = [double]::Parse($p[4], $inv)
            })
    }

    foreach ($date in @($byDay.Keys)) { [void]$allDates.Add($date) }
    $dayCount = 0
    foreach ($date in @($byDay.Keys)) {
        $dayDt = [datetime]::ParseExact($date, 'yyyy-MM-dd', $inv)
        if ($dayDt.DayOfWeek -eq [DayOfWeek]::Saturday -or $dayDt.DayOfWeek -eq [DayOfWeek]::Sunday) { continue }
        $bars = $byDay[$date]
        if ($bars.Count -lt $MinBarsPerDay) { continue }

        $orBars = @($bars | Where-Object { $_.dt.TimeOfDay -ge $orStart -and $_.dt.TimeOfDay -lt $orEnd })
        if ($orBars.Count -lt 2) { continue }

        $orHigh = $orBars[0].high
        $orLow = $orBars[0].low
        foreach ($b in $orBars) {
            if ($b.high -gt $orHigh) { $orHigh = $b.high }
            if ($b.low -lt $orLow) { $orLow = $b.low }
        }
        $orRange = $orHigh - $orLow
        if ($orRange -le 0) { continue }

        $afterOrStart = $null
        for ($i = 0; $i -lt $bars.Count; $i++) {
            if ($bars[$i].dt.TimeOfDay -ge $orEnd) { $afterOrStart = $i; break }
        }
        if ($null -eq $afterOrStart) { continue }

        $signalIdx = $null
        $dir = 0
        for ($i = $afterOrStart; $i -lt $bars.Count; $i++) {
            $up = $bars[$i].high -gt $orHigh
            $dn = $bars[$i].low -lt $orLow
            if ($up -and $dn) { $signalIdx = $null; $dir = 0; break }
            if ($up) { $signalIdx = $i; $dir = 1; break }
            if ($dn) { $signalIdx = $i; $dir = -1; break }
        }
        if ($null -eq $signalIdx -or $dir -eq 0) { continue }
        if ($signalIdx + 1 -ge $bars.Count) { continue }

        $entryIdx = $signalIdx + 1
        $exitIdx = $bars.Count - 1
        $entryPrice = $bars[$entryIdx].open
        $exitPrice = $bars[$exitIdx].close
        if ($entryPrice -le 0 -or $exitPrice -le 0) { continue }

        $dayHigh = $bars[0].high
        $dayLow = $bars[0].low
        foreach ($b in $bars) {
            if ($b.high -gt $dayHigh) { $dayHigh = $b.high }
            if ($b.low -lt $dayLow) { $dayLow = $b.low }
        }
        $dayRange = $dayHigh - $dayLow
        if ($dayRange -le 0) { continue }

        $shares = [math]::Round($Notional / $entryPrice)
        if ($shares -lt 1) { continue }

        $gross = ($exitPrice - $entryPrice) * $shares * $dir
        $entryNotional = $entryPrice * $shares
        $exitNotional = $exitPrice * $shares
        $turnover = $entryNotional + $exitNotional
        $commission = $turnover * $baseCommRate
        $slippage = $turnover * $baseSlipRate
        $net = $gross - $commission - $slippage
        $stressNet = $gross - $turnover * $stressCommRate - $turnover * $stressSlipRate
        $capturedRub = [math]::Abs($exitPrice - $entryPrice) * $shares
        $capturedPct = $capturedRub / ($dayRange * $shares) * 100.0
        $directionalEdgePct = if ($capturedRub -gt 0) { $net / $capturedRub * 100.0 } else { $null }

        $allTrades.Add([pscustomobject]@{
                period = $null
                ticker = $ticker
                date = $date
                direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
                entryTime = $bars[$entryIdx].dt.ToString('yyyy-MM-dd HH:mm:ss')
                exitTime = $bars[$exitIdx].dt.ToString('yyyy-MM-dd HH:mm:ss')
                entryPrice = [math]::Round($entryPrice, 4)
                exitPrice = [math]::Round($exitPrice, 4)
                shares = $shares
                gross = [math]::Round($gross, 2)
                commission = [math]::Round($commission, 2)
                slippage = [math]::Round($slippage, 2)
                net = [math]::Round($net, 2)
                stressNet = [math]::Round($stressNet, 2)
                holdBars = $exitIdx - $entryIdx
                capturedPct = [math]::Round($capturedPct, 2)
                dayRangePct = [math]::Round($dayRange / $entryPrice * 100.0, 2)
                directionalEdgePct = if ($null -ne $directionalEdgePct) { [math]::Round($directionalEdgePct, 2) } else { $null }
            })
        $dayCount++
    }
    Write-Host ("{0}: days={1}, trades={2}" -f $ticker, $dayCount, $allTrades.Count)
}

Write-Host ("Total trades simulated: {0}" -f $allTrades.Count)
if ($allTrades.Count -eq 0) { throw 'No trades simulated; check data/tickers.' }

$dates = @($allDates) | Sort-Object
$devCount = [int][math]::Floor($dates.Count * $DevFraction)
if ($devCount -lt 1 -or $devCount -ge $dates.Count) { throw "Invalid dev split: dates=$($dates.Count) dev=$devCount" }
$devLast = $dates[$devCount - 1]
$oosFirst = $dates[$devCount]
foreach ($t in $allTrades) {
    $t.period = if ($t.date -le $devLast) { 'DEV' } else { 'OOS' }
}

$devTrades = @($allTrades | Where-Object { $_.period -eq 'DEV' })
$devStats = Get-Stats $devTrades
$devConsistency = Get-FoldConsistency $devTrades
$devByTicker = @($tickerList | ForEach-Object {
        $tk = $_
        $s = Get-SimpleStats @($devTrades | Where-Object { $_.ticker -eq $tk })
        [pscustomobject]@{ ticker = $tk; trades = $s.trades; pf = $s.pf; net = $s.net; avgNet = $s.avgNet; winRate = $s.winRate }
    })

$devOk = ($devStats.trades -ge 200) -and ($devStats.pf -ge 1.30) -and ($devStats.pNoEdge -lt 0.05) -and
    ($devStats.avgNet -gt 0) -and ($devConsistency.consistency -ge 0.60)

$oosStats = $null
$oosConsistency = $null
$oosByTicker = @()
$stressStats = $null
$verdict = 'REJECTED_DEV'

if ($devOk) {
    $oosTrades = @($allTrades | Where-Object { $_.period -eq 'OOS' })
    $oosStats = Get-Stats $oosTrades
    $oosConsistency = Get-FoldConsistency $oosTrades
    $oosByTicker = @($tickerList | ForEach-Object {
            $tk = $_
            $s = Get-SimpleStats @($oosTrades | Where-Object { $_.ticker -eq $tk })
            [pscustomobject]@{ ticker = $tk; trades = $s.trades; pf = $s.pf; net = $s.net; avgNet = $s.avgNet; winRate = $s.winRate }
        })
    $stressTrades = @($oosTrades | ForEach-Object {
            [pscustomobject]@{ date = $_.date; entryTime = $_.entryTime; net = $_.stressNet }
        })
    $stressStats = Get-Stats $stressTrades

    $oosOk = ($oosStats.trades -ge 100) -and ($oosStats.pf -ge 1.30) -and ($oosStats.pNoEdge -lt 0.05) -and
        ($oosStats.ciLow -gt 0) -and ($oosStats.mddPct -le 40.0) -and ($oosConsistency.consistency -ge 0.60)
    $stressOk = ($stressStats.pf -ge 1.15)
    $verdict = if (($oosStats.pf -lt 1.30) -or ($oosStats.mddPct -gt 40.0) -or (-not $stressOk)) {
        'REJECTED'
    } elseif ($oosOk) {
        'PASS_PRECHECK'
    } else {
        'INCONCLUSIVE'
    }
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    timeframe = 'MINUTE_10'
    startDate = $StartDate
    endDate = 'last complete bar < current_date'
    notionalPerTrade = $Notional
    baseCosts = [pscustomobject]@{ commissionPctPerSide = $CommissionPctPerSide; slippageBpsPerSide = $SlippageBps }
    stressCosts = [pscustomobject]@{ commissionPctPerSide = $StressCommissionPctPerSide; slippageBpsPerSide = $StressSlippageBps }
    tradingDays = $dates.Count
    devDays = $devCount
    devLast = $devLast
    oosFirst = $oosFirst
    dev = $devStats
    devConsistency = $devConsistency
    devByTicker = $devByTicker
    devPassed = $devOk
    oosEvaluated = $devOk
    oos = $oosStats
    oosConsistency = $oosConsistency
    oosByTicker = $oosByTicker
    stressOos = $stressStats
    verdict = $verdict
    notes = 'Standalone pre-screen (docs/51), not a formal engine verdict. PASS only means implement the same variant in BacktestEngine and run docs/30 protocol.'
}

$summaryPath = Join-Path $OutDir 'equity_intraday_orb_2026-11-02_summary.json'
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $summaryPath -Encoding utf8
$devTrades | Export-Csv -LiteralPath (Join-Path $OutDir 'equity_intraday_orb_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($devOk) {
    $allTrades | Where-Object { $_.period -eq 'OOS' } |
        Export-Csv -LiteralPath (Join-Path $OutDir 'equity_intraday_orb_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
}

Write-Host ''
Write-Host '=== DEV ==='
$devStats | Format-List
$devConsistency.folds | Format-Table -AutoSize
Write-Host "devPassed=$devOk verdict=$verdict"
if ($devOk) {
    Write-Host ''
    Write-Host '=== OOS ==='
    $oosStats | Format-List
    $oosConsistency.folds | Format-Table -AutoSize
    Write-Host '=== OOS STRESS 0.10%/side + 3bp ==='
    $stressStats | Format-List
    Write-Host '=== OOS BY TICKER (diagnostics) ==='
    $oosByTicker | Format-Table -AutoSize
}
Write-Host "Summary: $summaryPath"
