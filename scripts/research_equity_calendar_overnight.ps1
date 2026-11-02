# Pre-screen календарных и overnight-эффектов на акциях TQBR (docs/52, 2026-11-02).
# 4 замороженных варианта: TOM, overnight каждый день, overnight после down-дня,
# pre-holiday. Pooled по 8 акциям; dev 70% / OOS 30% по календарным датам.
# Издержки: 0.05%/сторона + 1 bp (stress: 0.10%/сторона + 3 bp).
#
# Запуск: & .\scripts\research_equity_calendar_overnight.ps1
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

    # date-block bootstrap по датам выхода; суммы по датам считаются заранее
    $dateSum = @{}
    $dateCount = @{}
    foreach ($t in $arr) {
        $d = $t.exitDate
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
    $dates = @($arr | ForEach-Object { $_.exitDate } | Sort-Object -Unique)
    $n = $dates.Count
    $folds = @()
    $start = 0
    for ($f = 0; $f -lt 3; $f++) {
        $end = if ($f -eq 2) { $n - 1 } else { [math]::Min($n - 1, [int][math]::Floor(($f + 1) * $n / 3.0) - 1) }
        if ($end -lt $start) { $end = $start }
        $foldDates = @($dates[$start..$end])
        $set = [System.Collections.Generic.HashSet[string]]::new()
        foreach ($d in $foldDates) { [void]$set.Add($d) }
        $foldTrades = @($arr | Where-Object { $set.Contains($_.exitDate) })
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

function New-Trade($variant, $ticker, $entryBar, $exitBar, $entryPrice, $exitPrice) {
    $shares = [math]::Round($Notional / $entryPrice)
    if ($shares -lt 1) { return $null }
    $gross = ($exitPrice - $entryPrice) * $shares
    $turnover = ($entryPrice + $exitPrice) * $shares
    $commission = $turnover * $baseCommRate
    $slippage = $turnover * $baseSlipRate
    $net = $gross - $commission - $slippage
    $stressNet = $gross - $turnover * $stressCommRate - $turnover * $stressSlipRate
    $entryDt = [datetime]::ParseExact($entryBar.date, 'yyyy-MM-dd', $inv)
    $exitDt = [datetime]::ParseExact($exitBar.date, 'yyyy-MM-dd', $inv)
    [pscustomobject]@{
        period = $null
        variant = $variant
        ticker = $ticker
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
        holdDays = ($exitDt - $entryDt).Days
    }
}

$allTrades = [System.Collections.Generic.List[object]]::new()
$allDates = [System.Collections.Generic.HashSet[string]]::new()
$variantNames = @('K1_tom_long', 'K2_overnight_long', 'K3_overnight_after_down', 'K4_pre_holiday_long')

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
    foreach ($b in $bars) { [void]$allDates.Add($b.date) }
    if ($bars.Count -lt 30) { Write-Host "${ticker}: too few bars ($($bars.Count))"; continue }

    # K2/K3: overnight каждый день / после down-дня.
    for ($i = 0; $i -lt $bars.Count - 1; $i++) {
        $entry = $bars[$i]
        $exit = $bars[$i + 1]
        $t2 = New-Trade 'K2_overnight_long' $ticker $entry $exit $entry.close $exit.open
        if ($null -ne $t2) { $allTrades.Add($t2) }
        if ($entry.close -lt $entry.open) {
            $t3 = New-Trade 'K3_overnight_after_down' $ticker $entry $exit $entry.close $exit.open
            if ($null -ne $t3) { $allTrades.Add($t3) }
        }
    }

    # K1: turn-of-month (последние 2 дня M + первые 3 дня M+1).
    $months = [ordered]@{}
    foreach ($b in $bars) {
        $m = $b.date.Substring(0, 7)
        if (-not $months.Contains($m)) { $months[$m] = [System.Collections.Generic.List[object]]::new() }
        $months[$m].Add($b)
    }
    $monthKeys = @($months.Keys) | Sort-Object
    for ($mi = 0; $mi -lt $monthKeys.Count - 1; $mi++) {
        $m = $monthKeys[$mi]
        $nextM = ([datetime]::ParseExact("$m-01", 'yyyy-MM-dd', $inv)).AddMonths(1).ToString('yyyy-MM')
        if ($monthKeys[$mi + 1] -ne $nextM) { continue }
        $cur = $months[$m]
        $nxt = $months[$monthKeys[$mi + 1]]
        if ($cur.Count -lt 3 -or $nxt.Count -lt 3) { continue }
        $entryBar = $cur[$cur.Count - 3]
        $exitBar = $nxt[2]
        $t1 = New-Trade 'K1_tom_long' $ticker $entryBar $exitBar $entryBar.close $exitBar.close
        if ($null -ne $t1) { $allTrades.Add($t1) }
    }

    # K4: pre-holiday (между i и i+1 есть хотя бы один будний нерабочий день).
    for ($i = 1; $i -lt $bars.Count - 1; $i++) {
        $d1 = [datetime]::ParseExact($bars[$i].date, 'yyyy-MM-dd', $inv)
        $d2 = [datetime]::ParseExact($bars[$i + 1].date, 'yyyy-MM-dd', $inv)
        $gapWeekdays = 0
        $d = $d1.AddDays(1)
        while ($d -lt $d2) {
            if ($d.DayOfWeek -ne [DayOfWeek]::Saturday -and $d.DayOfWeek -ne [DayOfWeek]::Sunday) { $gapWeekdays++ }
            $d = $d.AddDays(1)
        }
        if ($gapWeekdays -ge 1) {
            $entryBar = $bars[$i - 1]
            $exitBar = $bars[$i]
            $t4 = New-Trade 'K4_pre_holiday_long' $ticker $entryBar $exitBar $entryBar.close $exitBar.close
            if ($null -ne $t4) { $allTrades.Add($t4) }
        }
    }
    Write-Host ("{0}: bars={1}, trades total={2}" -f $ticker, $bars.Count, $allTrades.Count)
}

Write-Host ("Total trades simulated: {0}" -f $allTrades.Count)
if ($allTrades.Count -eq 0) { throw 'No trades simulated; check data/tickers.' }

$dates = @($allDates) | Sort-Object
$devCount = [int][math]::Floor($dates.Count * $DevFraction)
if ($devCount -lt 1 -or $devCount -ge $dates.Count) { throw "Invalid dev split: dates=$($dates.Count) dev=$devCount" }
$devLast = $dates[$devCount - 1]
$oosFirst = $dates[$devCount]
foreach ($t in $allTrades) {
    $t.period = if ($t.exitDate -le $devLast) { 'DEV' } else { 'OOS' }
}

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
            [pscustomobject]@{ exitDate = $_.exitDate; entryTime = $_.entryTime; net = $_.stressNet; holdDays = $_.holdDays }
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
    timeframe = 'DAY_1'
    startDate = $StartDate
    endDate = 'last complete bar < current_date'
    notionalPerTrade = $Notional
    baseCosts = [pscustomobject]@{ commissionPctPerSide = $CommissionPctPerSide; slippageBpsPerSide = $SlippageBps }
    stressCosts = [pscustomobject]@{ commissionPctPerSide = $StressCommissionPctPerSide; slippageBpsPerSide = $StressSlippageBps }
    tradingDays = $dates.Count
    devDays = $devCount
    devLast = $devLast
    oosFirst = $oosFirst
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
    verdict = $verdict
    notes = 'Standalone pre-screen (docs/52), not a formal engine verdict. PASS only means implement the same variant in BacktestEngine and run docs/30 protocol.'
}

$summaryPath = Join-Path $OutDir 'equity_calendar_overnight_2026-11-02_summary.json'
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $summaryPath -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'DEV' } |
    Export-Csv -LiteralPath (Join-Path $OutDir 'equity_calendar_overnight_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($null -ne $selected) {
    $allTrades | Where-Object { $_.period -eq 'OOS' -and $_.variant -eq $selected } |
        Export-Csv -LiteralPath (Join-Path $OutDir 'equity_calendar_overnight_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
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
    Write-Host '=== OOS BY TICKER (diagnostics) ==='
    $oosByTicker | Format-Table -AutoSize
}
Write-Host "Summary: $summaryPath"
