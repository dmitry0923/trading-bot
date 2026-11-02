# Pre-screen высокочастотных дневных стратегий CNYRUBF (docs/36, 2026-11-02).
# 5 фиксированных вариантов, 1 сделка/день, dev 70% / OOS 30%, costs+funding.
#
# Запуск: & .\scripts\research_daily_strategies.ps1
param(
    [string]$Ticker = 'CNYRUBF',
    [int]$LotSize = 1000,
    [double]$CommissionPerSide = 1.5,
    [double]$SlippageBps = 1.0,
    [string]$OutDir = 'artifacts',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---------- Загрузка баров (окно привязано к последнему доступному бару) ----------
$sqlBars = @"
select to_char(time, 'YYYY-MM-DD HH24:MI:SS') || '|' ||
       open_price::text || '|' || high_price::text || '|' || low_price::text || '|' || close_price::text
from candles
where ticker = '$Ticker' and timeframe = 'MINUTE_10'
  and time > (select max(time) from candles where ticker = '$Ticker' and timeframe = 'MINUTE_10') - interval '730 days'
order by time;
"@
$rawBars = $sqlBars | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
$barsByDay = [ordered]@{}
foreach ($line in ($rawBars -split "`n")) {
    $s = $line.Trim()
    if ($s -eq '') { continue }
    $p = $s -split '\|'
    if ($p.Count -ne 5) { continue }
    $dt = [datetime]::ParseExact($p[0], 'yyyy-MM-dd HH:mm:ss', $inv)
    $bar = [pscustomobject]@{
        dt = $dt
        open = [double]::Parse($p[1], $inv)
        high = [double]::Parse($p[2], $inv)
        low = [double]::Parse($p[3], $inv)
        close = [double]::Parse($p[4], $inv)
    }
    $key = $dt.ToString('yyyy-MM-dd')
    if (-not $barsByDay.Contains($key)) { $barsByDay[$key] = [System.Collections.Generic.List[object]]::new() }
    $barsByDay[$key].Add($bar)
}
$days = @($barsByDay.Keys)
Write-Host ("Loaded {0} bars across {1} days ({2}..{3})" -f (($barsByDay.Values | ForEach-Object { $_.Count }) | Measure-Object -Sum).Sum, $days.Count, $days[0], $days[-1])

# ---------- Funding ----------
$sqlFunding = "select clearing_date::text || '|' || value_rub_per_contract::text from funding_history where ticker = '$Ticker';"
$rawFunding = $sqlFunding | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
$funding = @{}
foreach ($line in ($rawFunding -split "`n")) {
    $s = $line.Trim()
    if ($s -eq '') { continue }
    $p = $s -split '\|'
    if ($p.Count -eq 2) { $funding[$p[0]] = [double]::Parse($p[1], $inv) }
}
Write-Host ("Funding rows: {0}" -f $funding.Count)

function Get-FundingCost([datetime]$entryDt, [datetime]$exitDt, [int]$dir) {
    # Положительная ставка = long платит; short получает кредит.
    $cost = 0.0
    $d = $entryDt.Date
    while ($d -le $exitDt.Date) {
        $clearing = $d.AddHours(18).AddMinutes(45)
        if ($entryDt -lt $clearing -and $clearing -lt $exitDt) {
            $key = $d.ToString('yyyy-MM-dd')
            if ($funding.ContainsKey($key)) {
                $r = $funding[$key]
                $cost += if ($dir -gt 0) { $r } else { -$r }
            }
        }
        $d = $d.AddDays(1)
    }
    return $cost
}

function New-Trade {
    param($date, $variant, $dir, $entryIdx, $entryDt, $exitIdx, $exitDt, $entryPrice, $exitPrice, $reason, $dayRange, $bars)
    $gross = ($exitPrice - $entryPrice) * $LotSize * $dir
    $commission = $CommissionPerSide * 2.0
    $slippage = ($entryPrice * $SlippageBps / 10000.0 * $LotSize) + ($exitPrice * $SlippageBps / 10000.0 * $LotSize)
    $fundingCost = Get-FundingCost -entryDt $entryDt -exitDt $exitDt -dir $dir
    $net = $gross - $commission - $slippage - $fundingCost
    $captured = [math]::Abs($exitPrice - $entryPrice) * $LotSize
    $capPct = if ($dayRange -gt 0) { $captured / ($dayRange * $LotSize) * 100.0 } else { $null }
    [pscustomobject]@{
        date = $date
        variant = $variant
        direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
        entryTime = $entryDt.ToString('yyyy-MM-dd HH:mm:ss')
        exitTime = $exitDt.ToString('yyyy-MM-dd HH:mm:ss')
        entryPrice = [math]::Round($entryPrice, 4)
        exitPrice = [math]::Round($exitPrice, 4)
        closeReason = $reason
        gross = [math]::Round($gross, 2)
        commission = [math]::Round($commission, 2)
        slippage = [math]::Round($slippage, 2)
        funding = [math]::Round($fundingCost, 2)
        net = [math]::Round($net, 2)
        holdBars = $exitIdx - $entryIdx
        capturedRub = [math]::Round($captured, 2)
        dayRangeRub = [math]::Round($dayRange * $LotSize, 2)
        capturedPct = if ($null -ne $capPct) { [math]::Round($capPct, 2) } else { $null }
    }
}

function Simulate-Exit {
    param($bars, [int]$entryIdx, [int]$dir, $slOffset, $tpOffset, $date, $variant, $dayRange)
    $entryPrice = $bars[$entryIdx].open
    $entryDt = $bars[$entryIdx].dt
    $sl = if ($null -ne $slOffset) { $entryPrice - $dir * $slOffset } else { $null }
    $tp = if ($null -ne $tpOffset) { $entryPrice + $dir * $tpOffset } else { $null }
    $reason = 'CLOSE'
    $exitIdx = $bars.Count - 1
    $exitPrice = $bars[$exitIdx].close
    $exitDt = $bars[$exitIdx].dt
    for ($j = $entryIdx; $j -lt $bars.Count; $j++) {
        $b = $bars[$j]
        if ($dir -gt 0) {
            if ($null -ne $sl -and $b.low -le $sl) { $exitIdx = $j; $exitPrice = $sl; $exitDt = $b.dt; $reason = 'SL'; break }
            if ($null -ne $tp -and $b.high -ge $tp) { $exitIdx = $j; $exitPrice = $tp; $exitDt = $b.dt; $reason = 'TP'; break }
        } else {
            if ($null -ne $sl -and $b.high -ge $sl) { $exitIdx = $j; $exitPrice = $sl; $exitDt = $b.dt; $reason = 'SL'; break }
            if ($null -ne $tp -and $b.low -le $tp) { $exitIdx = $j; $exitPrice = $tp; $exitDt = $b.dt; $reason = 'TP'; break }
        }
    }
    return New-Trade -date $date -variant $variant -dir $dir -entryIdx $entryIdx -entryDt $entryDt `
        -exitIdx $exitIdx -exitDt $exitDt -entryPrice $entryPrice -exitPrice $exitPrice -reason $reason `
        -dayRange $dayRange -bars $bars
}

$allTrades = [System.Collections.Generic.List[object]]::new()

for ($d = 1; $d -lt $days.Count; $d++) {
    $date = $days[$d]
    $bars = $barsByDay[$date]
    if ($bars.Count -lt 5) { continue }
    $dayHigh = ($bars | Measure-Object -Property high -Maximum).Maximum
    $dayLow = ($bars | Measure-Object -Property low -Minimum).Minimum
    $dayRange = $dayHigh - $dayLow

    $prevBars = $barsByDay[$days[$d - 1]]
    $prevHigh = ($prevBars | Measure-Object -Property high -Maximum).Maximum
    $prevLow = ($prevBars | Measure-Object -Property low -Minimum).Minimum
    $prevOpen = $prevBars[0].open
    $prevClose = $prevBars[$prevBars.Count - 1].close
    $prevRange = $prevHigh - $prevLow

    # Opening range 10:00..10:30 и первый бар после 10:30.
    $orBars = @($bars | Where-Object { $_.dt.TimeOfDay -ge [timespan]::FromHours(10) -and $_.dt.TimeOfDay -lt [timespan]::FromMinutes(630) })
    $afterOr = @()
    for ($i = 0; $i -lt $bars.Count; $i++) {
        if ($bars[$i].dt.TimeOfDay -ge [timespan]::FromMinutes(630)) { $afterOr += $i }
    }

    if ($orBars.Count -ge 2 -and $afterOr.Count -gt 0) {
        $orHigh = ($orBars | Measure-Object -Property high -Maximum).Maximum
        $orLow = ($orBars | Measure-Object -Property low -Minimum).Minimum
        $orRange = $orHigh - $orLow
        if ($orRange -gt 0) {
            $signalIdx = $null
            $signalDir = 0
            for ($i = $afterOr[0]; $i -lt $bars.Count; $i++) {
                $up = $bars[$i].high -gt $orHigh
                $dn = $bars[$i].low -lt $orLow
                if ($up -and $dn) { break } # ambiguous -> skip day
                if ($up) { $signalIdx = $i; $signalDir = 1; break }
                if ($dn) { $signalIdx = $i; $signalDir = -1; break }
            }
            if ($null -ne $signalIdx -and $signalIdx + 1 -lt $bars.Count) {
                $entryIdx = $signalIdx + 1
                $allTrades.Add((Simulate-Exit -bars $bars -entryIdx $entryIdx -dir $signalDir -slOffset $null -tpOffset $null -date $date -variant 'H1_orb30_close' -dayRange $dayRange))
                $allTrades.Add((Simulate-Exit -bars $bars -entryIdx $entryIdx -dir $signalDir -slOffset (0.5 * $orRange) -tpOffset (1.0 * $orRange) -date $date -variant 'H2_orb30_sl05_tp10' -dayRange $dayRange))
                $allTrades.Add((Simulate-Exit -bars $bars -entryIdx $entryIdx -dir $signalDir -slOffset (1.0 * $orRange) -tpOffset (2.0 * $orRange) -date $date -variant 'H3_orb30_sl10_tp20' -dayRange $dayRange))
            }
        }
    }

    # H4/H5: вход на открытии первого бара >= 10:00.
    $firstMain = $null
    for ($i = 0; $i -lt $bars.Count; $i++) {
        if ($bars[$i].dt.TimeOfDay -ge [timespan]::FromHours(10)) { $firstMain = $i; break }
    }
    if ($null -ne $firstMain) {
        # H4 previous-day momentum
        if ($prevClose -ne $prevOpen) {
            $dir4 = if ($prevClose -gt $prevOpen) { 1 } else { -1 }
            $allTrades.Add((Simulate-Exit -bars $bars -entryIdx $firstMain -dir $dir4 -slOffset $null -tpOffset $null -date $date -variant 'H4_prevday_momentum' -dayRange $dayRange))
        }
        # H5 gap fade
        if ($prevRange -gt 0) {
            $openToday = $bars[$firstMain].open
            $gap = $openToday - $prevClose
            $threshold = 0.1 * $prevRange
            if ([math]::Abs($gap) -gt $threshold) {
                $dir5 = if ($gap -gt 0) { -1 } else { 1 }
                $allTrades.Add((Simulate-Exit -bars $bars -entryIdx $firstMain -dir $dir5 -slOffset $null -tpOffset $null -date $date -variant 'H5_gap_fade' -dayRange $dayRange))
            }
        }
    }
}

Write-Host ("Trades simulated: {0}" -f $allTrades.Count)

# ---------- Разбиение dev/OOS по дням ----------
$devDays = [math]::Floor($days.Count * 0.70)
$devLast = $days[$devDays - 1]
$oosFirst = $days[$devDays]
foreach ($t in $allTrades) { $t | Add-Member -NotePropertyName period -NotePropertyValue $(if ($t.date -le $devLast) { 'DEV' } else { 'OOS' }) -Force }

$devTrades = @($allTrades | Where-Object { $_.period -eq 'DEV' })
$oosTrades = @($allTrades | Where-Object { $_.period -eq 'OOS' })

function Quantile([double[]]$values, [double]$q) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return $s[$idx]
}

function Test-NoEdge([double[]]$pnls, [int]$iterations, [int]$seed) {
    if ($null -eq $pnls -or $pnls.Count -lt 2) { return 1.0 }
    $rnd = [System.Random]::new($seed)
    $means = [double[]]::new($iterations)
    $n = $pnls.Count
    for ($k = 0; $k -lt $iterations; $k++) {
        $sum = 0.0
        for ($i = 0; $i -lt $n; $i++) { $sum += $pnls[$rnd.Next(0, $n)] }
        $means[$k] = $sum / $n
    }
    $le = 0
    foreach ($m in $means) { if ($m -le 0) { $le++ } }
    return $le / [double]$iterations
}

function Stats($items) {
    $arr = @($items)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{ trades = 0; net = 0; pf = 0; winRate = 0; avgNet = 0; medianNet = 0; ciLow = 0; ciHigh = 0; pNoEdge = 1.0; mddPct = 0; avgCapturedPct = 0; avgHoldBars = 0 }
    }
    $pnls = [double[]]@($arr | ForEach-Object { $_.net })
    $wins = @($arr | Where-Object { $_.net -gt 0 })
    $losses = @($arr | Where-Object { $_.net -le 0 })
    $sumWin = ($wins | Measure-Object -Property net -Sum).Sum
    $sumLoss = [math]::Abs(($losses | Measure-Object -Property net -Sum).Sum)
    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    $net = ($pnls | Measure-Object -Sum).Sum
    $avg = ($pnls | Measure-Object -Average).Average
    $median = Quantile $pnls 0.5
    # MDD по кумулятивному net (в рублях; % от 100k — ориентир)
    $cum = 0.0; $peak = 0.0; $mdd = 0.0
    foreach ($x in $pnls) { $cum += $x; if ($cum -gt $peak) { $peak = $cum }; $dd = $peak - $cum; if ($dd -gt $mdd) { $mdd = $dd } }
    # Bootstrap CI средней
    $rnd = [System.Random]::new($Seed)
    $means = [double[]]::new($BootstrapIterations)
    $n = $pnls.Count
    for ($k = 0; $k -lt $BootstrapIterations; $k++) {
        $sum = 0.0
        for ($i = 0; $i -lt $n; $i++) { $sum += $pnls[$rnd.Next(0, $n)] }
        $means[$k] = $sum / $n
    }
    [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round($net, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins.Count / $arr.Count, 1)
        avgNet = [math]::Round($avg, 2)
        medianNet = [math]::Round($median, 2)
        ciLow = [math]::Round((Quantile $means 0.025), 2)
        ciHigh = [math]::Round((Quantile $means 0.975), 2)
        pNoEdge = [math]::Round((Test-NoEdge $pnls $BootstrapIterations $Seed), 4)
        mddPct = [math]::Round($mdd / 1000.0, 2)
        avgCapturedPct = [math]::Round((($arr | Where-Object { $null -ne $_.capturedPct } | ForEach-Object { $_.capturedPct }) | Measure-Object -Average).Average, 2)
        avgHoldBars = [math]::Round((($arr | ForEach-Object { $_.holdBars }) | Measure-Object -Average).Average, 1)
    }
}

$variants = @('H1_orb30_close', 'H2_orb30_sl05_tp10', 'H3_orb30_sl10_tp20', 'H4_prevday_momentum', 'H5_gap_fade')
$devStats = @{}
foreach ($v in $variants) {
    $devStats[$v] = Stats @($devTrades | Where-Object { $_.variant -eq $v })
}
$candidates = @($variants | Where-Object { $s = $devStats[$_]; $s.trades -ge 100 -and $s.pf -ge 1.30 -and $s.pNoEdge -lt 0.05 -and $s.avgNet -gt 0 })
$selected = if ($candidates.Count -gt 0) { $candidates | Sort-Object { $devStats[$_].pf } -Descending | Select-Object -First 1 } else { $null }

$oosStats = $null
$verdict = 'NO_CANDIDATE'
if ($null -ne $selected) {
    $oosStats = Stats @($oosTrades | Where-Object { $_.variant -eq $selected })
    $ok = $oosStats.trades -ge 30 -and $oosStats.pf -ge 1.30 -and $oosStats.pNoEdge -lt 0.05 -and $oosStats.ciLow -gt 0 -and $oosStats.mddPct -le 40
    $verdict = if ($ok) { 'PASS_PRECHECK' } elseif ($oosStats.pf -lt 1.30 -or $oosStats.mddPct -gt 40) { 'REJECTED' } else { 'INCONCLUSIVE' }
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    ticker = $Ticker
    windowDays = 730
    tradingDays = $days.Count
    devDays = $devDays
    devLast = $devLast
    oosFirst = $oosFirst
    devStats = $devStats
    candidates = $candidates
    selected = $selected
    oosStats = $oosStats
    verdict = $verdict
    notes = 'Standalone pre-screen, not a formal engine verdict. PASS only means implement in BacktestEngine and run docs/30 protocol.'
}

$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'daily_strategies_2026-11-02_summary.json') -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'daily_strategies_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'daily_strategies_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8

Write-Host "=== DEV ==="
$variants | ForEach-Object { [pscustomobject]@{ variant = $_; trades = $devStats[$_].trades; pf = $devStats[$_].pf; net = $devStats[$_].net; avgNet = $devStats[$_].avgNet; pNoEdge = $devStats[$_].pNoEdge; capturedPct = $devStats[$_].avgCapturedPct } } | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $oosStats) { $oosStats | Format-List }
