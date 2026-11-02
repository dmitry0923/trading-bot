# Pre-screen event study решений ЦБ РФ на акциях TQBR (docs/53, 2026-11-02).
# 4 варианта: cut→LONG / hike→SHORT, горизонт 5 или 10 торговых дней.
# Pooled по 8 акциям; dev/OOS 70/30 по датам событий.
# Издержки: 0.05%/сторона + 1 bp (stress: 0.10%/сторона + 3 bp).
#
# Запуск: & .\scripts\research_cbr_rate_events.ps1
param(
    [string]$Tickers = 'GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN',
    [string]$StartDate = '2018-01-03',
    [double]$Notional = 100000,
    [double]$CommissionPctPerSide = 0.05,
    [double]$SlippageBps = 1.0,
    [double]$StressCommissionPctPerSide = 0.10,
    [double]$StressSlippageBps = 3.0,
    [double]$DevFraction = 0.70,
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [string]$ExcludeEvent = '2022-02-28',
    [string]$OutDir = 'artifacts',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$tickerList = @($Tickers.Split(',') | Where-Object { $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
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
            avgHoldDays = 0.0
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

    # date-block bootstrap по датам событий; 8 сделок одного события коррелированы
    $dateSum = @{}
    $dateCount = @{}
    foreach ($t in $arr) {
        $d = $t.eventDate
        if (-not $dateSum.ContainsKey($d)) { $dateSum[$d] = 0.0; $dateCount[$d] = 0 }
        $dateSum[$d] += [double]$t.net
        $dateCount[$d]++
    }
    $dateKeys = @($dateSum.Keys)
    $nd = $dateKeys.Count
    $means = [double[]]::new($BootstrapIterations)
    $rnd = [System.Random]::new($Seed)
    for ($k = 0; $k -lt $BootstrapIterations; $k++) {
        $sum = 0.0; $cnt = 0
        for ($j = 0; $j -lt $nd; $j++) {
            $key = $dateKeys[$rnd.Next(0, $nd)]
            $sum += $dateSum[$key]
            $cnt += $dateCount[$key]
        }
        $means[$k] = if ($cnt -gt 0) { $sum / $cnt } else { 0.0 }
    }
    $pNoEdge = @($means | Where-Object { $_ -le 0 }).Count / [double]$BootstrapIterations

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
        avgHoldDays = [math]::Round((($arr | ForEach-Object { $_.holdDays }) | Measure-Object -Average).Average, 1)
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
    $dates = @($arr | ForEach-Object { $_.eventDate } | Sort-Object -Unique)
    $n = $dates.Count
    $folds = @()
    $start = 0
    for ($f = 0; $f -lt 3; $f++) {
        $end = if ($f -eq 2) { $n - 1 } else { [math]::Min($n - 1, [int][math]::Floor(($f + 1) * $n / 3.0) - 1) }
        if ($end -lt $start) { $end = $start }
        $foldDates = @($dates[$start..$end])
        $set = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($d in $foldDates) { [void]$set.Add($d) }
        $foldTrades = @($arr | Where-Object { $set.Contains($_.eventDate) })
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

function New-Trade($variant, $ticker, $event, $bars, $entryIdx, $holdDays) {
    if ($entryIdx + $holdDays - 1 -ge $bars.Count) { return $null }
    $entryBar = $bars[$entryIdx]
    $exitBar = $bars[$entryIdx + $holdDays - 1]
    $entryPrice = $entryBar.open
    $exitPrice = $exitBar.close
    if ($entryPrice -le 0 -or $exitPrice -le 0) { return $null }
    $shares = [math]::Round($Notional / $entryPrice)
    if ($shares -lt 1) { return $null }
    $dir = if ($event.delta -lt 0) { 1 } else { -1 }
    $gross = ($exitPrice - $entryPrice) * $shares * $dir
    $turnover = ($entryPrice + $exitPrice) * $shares
    $commission = $turnover * $baseCommRate
    $slippage = $turnover * $baseSlipRate
    $net = $gross - $commission - $slippage
    $stressNet = $gross - $turnover * $stressCommRate - $turnover * $stressSlipRate
    [pscustomobject]@{
        period = $null
        variant = $variant
        ticker = $ticker
        eventDate = $event.date
        delta = $event.delta
        kind = if ($event.delta -lt 0) { 'CUT' } else { 'HIKE' }
        direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
        holdDays = $holdDays
        entryDate = $entryBar.date
        exitDate = $exitBar.date
        entryTime = "$($entryBar.date) 00:00:00"
        exitTime = "$($exitBar.date) 00:00:00"
        entryPrice = [math]::Round($entryPrice, 4)
        exitPrice = [math]::Round($exitPrice, 4)
        shares = $shares
        gross = [math]::Round($gross, 2)
        commission = [math]::Round($commission, 2)
        slippage = [math]::Round($slippage, 2)
        net = [math]::Round($net, 2)
        stressNet = [math]::Round($stressNet, 2)
    }
}

# ---------- События ставки ----------
$sqlRates = @"
select to_char(rate_date, 'YYYY-MM-DD') || '|' || key_rate::text
from cbr_rates_history
where key_rate is not null
order by rate_date;
"@
$rawRates = $sqlRates | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
$rateRows = @()
foreach ($line in ($rawRates -split "`n")) {
    $s = $line.Trim()
    if ($s -eq '') { continue }
    $p = $s -split '\|'
    if ($p.Count -ne 2) { continue }
    $rateRows += [pscustomobject]@{ date = $p[0]; rate = [double]::Parse($p[1], $inv) }
}
$events = @()
for ($i = 1; $i -lt $rateRows.Count; $i++) {
    $delta = $rateRows[$i].rate - $rateRows[$i - 1].rate
    if ([math]::Abs($delta) -lt 0.0001) { continue }
    if ($rateRows[$i].date -lt $StartDate) { continue }
    $events += [pscustomobject]@{
        date = $rateRows[$i].date
        rate = $rateRows[$i].rate
        prevRate = $rateRows[$i - 1].rate
        delta = [math]::Round($delta, 4)
    }
}
Write-Host ("Rate events: {0} (cuts={1}, hikes={2})" -f $events.Count,
    @($events | Where-Object { $_.delta -lt 0 }).Count,
    @($events | Where-Object { $_.delta -gt 0 }).Count)

$allTrades = [System.Collections.Generic.List[object]]::new()
$allEventDates = [System.Collections.Generic.HashSet[string]]::new()

foreach ($ticker in $tickerList) {
    $sql = @"
select to_char(time, 'YYYY-MM-DD') || '|' ||
       open_price::text || '|' || high_price::text || '|' || low_price::text || '|' || close_price::text
from candles
where ticker = '$ticker' and timeframe = 'DAY_1'
  and time >= '$StartDate' and time < current_date
order by time;
"@
    $raw = $sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    $byDate = @{}
    foreach ($line in ($raw -split "`n")) {
        $s = $line.Trim()
        if ($s -eq '') { continue }
        $p = $s -split '\|'
        if ($p.Count -ne 5) { continue }
        $dt = [datetime]::ParseExact($p[0], 'yyyy-MM-dd', $inv)
        if ($dt.DayOfWeek -eq [DayOfWeek]::Saturday -or $dt.DayOfWeek -eq [DayOfWeek]::Sunday) { continue }
        $byDate[$p[0]] = [pscustomobject]@{
            date = $p[0]
            open = [double]::Parse($p[1], $inv)
            high = [double]::Parse($p[2], $inv)
            low = [double]::Parse($p[3], $inv)
            close = [double]::Parse($p[4], $inv)
        }
    }
    $bars = @($byDate.Values | Sort-Object { $_.date })
    if ($bars.Count -lt 30) { Write-Host "${ticker}: too few bars ($($bars.Count))"; continue }

    foreach ($event in $events) {
        $entryIdx = -1
        for ($i = 0; $i -lt $bars.Count; $i++) {
            if ($bars[$i].date -gt $event.date) { $entryIdx = $i; break }
        }
        if ($entryIdx -lt 0) { continue }
        [void]$allEventDates.Add($event.date)

        if ($event.delta -lt 0) {
            $t1 = New-Trade 'R1_cut_long_5d' $ticker $event $bars $entryIdx 5
            if ($null -ne $t1) { $allTrades.Add($t1) }
            $t2 = New-Trade 'R2_cut_long_10d' $ticker $event $bars $entryIdx 10
            if ($null -ne $t2) { $allTrades.Add($t2) }
        } else {
            $t3 = New-Trade 'R3_hike_short_5d' $ticker $event $bars $entryIdx 5
            if ($null -ne $t3) { $allTrades.Add($t3) }
            $t4 = New-Trade 'R4_hike_short_10d' $ticker $event $bars $entryIdx 10
            if ($null -ne $t4) { $allTrades.Add($t4) }
        }
    }
    Write-Host ("{0}: bars={1}, trades total={2}" -f $ticker, $bars.Count, $allTrades.Count)
}

Write-Host ("Total trades simulated: {0}" -f $allTrades.Count)
if ($allTrades.Count -eq 0) { throw 'No trades simulated; check data/events.' }

$eventDates = @($allEventDates) | Sort-Object
$devEventCount = [int][math]::Floor($eventDates.Count * $DevFraction)
if ($devEventCount -lt 2 -or $devEventCount -ge $eventDates.Count) {
    throw "Invalid dev split: events=$($eventDates.Count) dev=$devEventCount"
}
$devLastEvent = $eventDates[$devEventCount - 1]
$oosFirstEvent = $eventDates[$devEventCount]
foreach ($t in $allTrades) {
    $t.period = if ($t.eventDate -le $devLastEvent) { 'DEV' } else { 'OOS' }
}

$variantNames = @('R1_cut_long_5d', 'R2_cut_long_10d', 'R3_hike_short_5d', 'R4_hike_short_10d')
$devStats = @{}
$devConsistency = @{}
foreach ($v in $variantNames) {
    $vt = @($allTrades | Where-Object { $_.period -eq 'DEV' -and $_.variant -eq $v })
    $devStats[$v] = Get-Stats $vt
    $devConsistency[$v] = Get-FoldConsistency $vt
}

$candidates = @($variantNames | Where-Object {
        $s = $devStats[$_]
        $c = $devConsistency[$_]
        $s.trades -ge 100 -and $s.pf -ge 1.30 -and $s.pNoEdge -lt 0.05 -and $s.avgNet -gt 0 -and $c.consistency -ge 0.60
    })
$selected = if ($candidates.Count -gt 0) { $candidates | Sort-Object { $devStats[$_].pf } -Descending | Select-Object -First 1 } else { $null }

$oosStats = $null
$oosConsistency = $null
$stressStats = $null
$oosEx2022 = $null
$oosByTicker = @()
$verdict = 'REJECTED_DEV'

if ($null -ne $selected) {
    $oosTrades = @($allTrades | Where-Object { $_.period -eq 'OOS' -and $_.variant -eq $selected })
    $oosStats = Get-Stats $oosTrades
    $oosConsistency = Get-FoldConsistency $oosTrades
    $oosByTicker = @($tickerList | ForEach-Object {
            $tk = $_
            $s = Get-SimpleStats @($oosTrades | Where-Object { $_.ticker -eq $tk })
            [pscustomobject]@{ ticker = $tk; trades = $s.trades; pf = $s.pf; net = $s.net; avgNet = $s.avgNet; winRate = $s.winRate }
        })
    $stressTrades = @($oosTrades | ForEach-Object {
            [pscustomobject]@{ eventDate = $_.eventDate; entryTime = $_.entryTime; net = $_.stressNet; holdDays = $_.holdDays }
        })
    $stressStats = Get-Stats $stressTrades
    $exTrades = @($oosTrades | Where-Object { $_.eventDate -ne $ExcludeEvent })
    $oosEx2022 = Get-Stats $exTrades

    $oosOk = ($oosStats.trades -ge 80) -and ($oosStats.pf -ge 1.30) -and ($oosStats.pNoEdge -lt 0.05) -and
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
    timeframe = 'DAY_1'
    startDate = $StartDate
    notionalPerTrade = $Notional
    baseCosts = [pscustomobject]@{ commissionPctPerSide = $CommissionPctPerSide; slippageBpsPerSide = $SlippageBps }
    stressCosts = [pscustomobject]@{ commissionPctPerSide = $StressCommissionPctPerSide; slippageBpsPerSide = $StressSlippageBps }
    rateEvents = $events.Count
    rateCuts = @($events | Where-Object { $_.delta -lt 0 }).Count
    rateHikes = @($events | Where-Object { $_.delta -gt 0 }).Count
    eventDatesWithTrades = $eventDates.Count
    devEvents = $devEventCount
    devLastEvent = $devLastEvent
    oosFirstEvent = $oosFirstEvent
    variants = $variantNames
    dev = $devStats
    devConsistency = $devConsistency
    candidates = $candidates
    selected = $selected
    oosEvaluated = ($null -ne $selected)
    oos = $oosStats
    oosConsistency = $oosConsistency
    oosByTicker = $oosByTicker
    stressOos = $stressStats
    oosExcluding2022_02_28 = $oosEx2022
    verdict = $verdict
    notes = 'Standalone pre-screen (docs/53), not a formal engine verdict. PASS only means implement the same variant in BacktestEngine and run docs/30 protocol.'
}

$summaryPath = Join-Path $OutDir 'cbr_rate_events_2026-11-02_summary.json'
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $summaryPath -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'DEV' } |
    Export-Csv -LiteralPath (Join-Path $OutDir 'cbr_rate_events_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($null -ne $selected) {
    $allTrades | Where-Object { $_.period -eq 'OOS' -and $_.variant -eq $selected } |
        Export-Csv -LiteralPath (Join-Path $OutDir 'cbr_rate_events_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
}

Write-Host ''
Write-Host '=== DEV ==='
$variantNames | ForEach-Object {
    $s = $devStats[$_]
    $c = $devConsistency[$_]
    [pscustomobject]@{
        variant = $_
        trades = $s.trades
        pf = $s.pf
        net = $s.net
        avgNet = $s.avgNet
        pNoEdge = $s.pNoEdge
        consistency = $c.consistency
    }
} | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $selected) {
    Write-Host ''
    Write-Host '=== OOS ==='
    $oosStats | Format-List
    $oosConsistency.folds | Format-Table -AutoSize
    Write-Host '=== OOS STRESS 0.10%/side + 3bp ==='
    $stressStats | Format-List
    Write-Host "=== OOS EXCLUDING $ExcludeEvent ==="
    $oosEx2022 | Format-List
    Write-Host '=== OOS BY TICKER (diagnostics) ==='
    $oosByTicker | Format-Table -AutoSize
}
Write-Host "Summary: $summaryPath"
