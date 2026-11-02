# Pre-screen carry/funding-стратегий CNYRUBF на новом горизонте (docs/37, 2026-11-02).
# 3 фиксированных варианта, 1 позиция, dev 2024-10..2026-10, OOS 2022-12..2024-10.
#
# Запуск: & .\scripts\research_carry.ps1
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
    [int]$Seed = 42,
    [string]$DevStart = '2024-10-24',
    [string]$OosStart = '2022-12-05',
    [string]$OosEnd = '2024-10-23'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# ---------- Бары DAY_1 (без неполного текущего дня) ----------
$sqlBars = @"
select to_char(time, 'YYYY-MM-DD') || '|' || open_price::text || '|' || close_price::text
from candles
where ticker = '$Ticker' and timeframe = 'DAY_1' and time < current_date
order by time;
"@
$rawBars = $sqlBars | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
$bars = [System.Collections.Generic.List[object]]::new()
foreach ($line in ($rawBars -split "`n")) {
    $s = $line.Trim()
    if ($s -eq '') { continue }
    $p = $s -split '\|'
    if ($p.Count -ne 3) { continue }
    $bars.Add([pscustomobject]@{
            date = $p[0]
            open = [double]::Parse($p[1], $inv)
            close = [double]::Parse($p[2], $inv)
        })
}
if ($bars.Count -lt 100) { throw "Too few bars: $($bars.Count)" }
Write-Host ("Loaded {0} DAY_1 bars: {1}..{2}" -f $bars.Count, $bars[0].date, $bars[$bars.Count - 1].date)

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

function Quantile([double[]]$values, [double]$q) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return $s[$idx]
}

function Get-FundingCost([string]$entryDate, [string]$exitDate, [int]$dir, [hashtable]$fundingMap) {
    $cost = 0.0
    $missing = 0
    $d = [datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv)
    $end = [datetime]::ParseExact($exitDate, 'yyyy-MM-dd', $inv)
    while ($d -le $end) {
        $clearing = $d.AddHours(18).AddMinutes(45)
        $entryDt = [datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv).AddHours(10)
        $exitDt = [datetime]::ParseExact($exitDate, 'yyyy-MM-dd', $inv).AddHours(23).AddMinutes(40)
        if ($entryDt -lt $clearing -and $clearing -lt $exitDt) {
            $key = $d.ToString('yyyy-MM-dd')
            if ($fundingMap.ContainsKey($key)) {
                $r = $fundingMap[$key]
                $cost += if ($dir -gt 0) { $r } else { -$r }
            } else {
                $missing++
            }
        }
        $d = $d.AddDays(1)
    }
    return [pscustomobject]@{ cost = $cost; missing = $missing }
}

function New-CarryTrade {
    param($signalDate, $variant, $dir, $entryDate, $exitDate, $entryPrice, $exitPrice, [hashtable]$fundingMap)
    $gross = ($exitPrice - $entryPrice) * $LotSize * $dir
    $commission = $CommissionPerSide * 2.0
    $slippage = ($entryPrice * $SlippageBps / 10000.0 * $LotSize) + ($exitPrice * $SlippageBps / 10000.0 * $LotSize)
    $fc = Get-FundingCost -entryDate $entryDate -exitDate $exitDate -dir $dir -fundingMap $fundingMap
    $net = $gross - $commission - $slippage - $fc.cost
    [pscustomobject]@{
        signalDate = $signalDate
        variant = $variant
        direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
        entryDate = $entryDate
        exitDate = $exitDate
        entryPrice = [math]::Round($entryPrice, 4)
        exitPrice = [math]::Round($exitPrice, 4)
        gross = [math]::Round($gross, 2)
        commission = [math]::Round($commission, 2)
        slippage = [math]::Round($slippage, 2)
        funding = [math]::Round($fc.cost, 2)
        fundingMissing = $fc.missing
        net = [math]::Round($net, 2)
    }
}

$variants = @('H1_carry_follow_1d', 'H2_carry_fade_1d', 'H3_carry_extreme_5d')
$trades = [System.Collections.Generic.List[object]]::new()

# Индекс funding по датам для trailing-окна.
$fundingSeries = @()
foreach ($b in $bars) {
    if ($funding.ContainsKey($b.date)) { $fundingSeries += [pscustomobject]@{ date = $b.date; value = $funding[$b.date] } }
}

foreach ($v in $variants) {
    $nextAvailable = [datetime]::MinValue
    for ($i = 0; $i -lt $bars.Count - 1; $i++) {
        $D = $bars[$i]
        if (-not $funding.ContainsKey($D.date)) { continue }
        $fundingD = $funding[$D.date]

        $dir = 0
        $hold = 1
        switch ($v) {
            'H1_carry_follow_1d' {
                if ($fundingD -gt 0) { $dir = -1 } elseif ($fundingD -lt 0) { $dir = 1 }
            }
            'H2_carry_fade_1d' {
                if ($fundingD -gt 0) { $dir = 1 } elseif ($fundingD -lt 0) { $dir = -1 }
            }
            'H3_carry_extreme_5d' {
                $hold = 5
                # trailing 60 наблюдений funding, включая D (известно на закрытии D)
                $window = @($fundingSeries | Where-Object { $_.date -le $D.date } | Select-Object -Last 60 | ForEach-Object { $_.value })
                if ($window.Count -ge 20) {
                    $p10 = Quantile ([double[]]$window) 0.10
                    $p90 = Quantile ([double[]]$window) 0.90
                    if ($fundingD -gt $p90) { $dir = -1 } elseif ($fundingD -lt $p10) { $dir = 1 }
                }
            }
        }
        if ($dir -eq 0) { continue }

        $entryIdx = $i + 1
        $exitIdx = $entryIdx + $hold - 1
        if ($exitIdx -ge $bars.Count) { continue }
        $entryDate = $bars[$entryIdx].date
        if ([datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv) -lt $nextAvailable) { continue }

        $trade = New-CarryTrade -signalDate $D.date -variant $v -dir $dir -entryDate $entryDate -exitDate $bars[$exitIdx].date `
            -entryPrice $bars[$entryIdx].open -exitPrice $bars[$exitIdx].close -fundingMap $funding
        $trades.Add($trade)
        $nextAvailable = [datetime]::ParseExact($bars[$exitIdx].date, 'yyyy-MM-dd', $inv).AddDays(1)
    }
}

Write-Host ("Trades simulated: {0}" -f $trades.Count)
foreach ($t in $trades) {
    $t | Add-Member -NotePropertyName period -NotePropertyValue $(if ($t.signalDate -ge $DevStart) { 'DEV' } elseif ($t.signalDate -ge $OosStart -and $t.signalDate -le $OosEnd) { 'OOS' } else { 'OTHER' }) -Force
}

function Test-NoEdge([double[]]$pnls, [int]$iterations, [int]$seed) {
    if ($null -eq $pnls -or $pnls.Count -lt 2) { return 1.0 }
    $rnd = [System.Random]::new($seed)
    $means = [double[]]::new($iterations)
    $n = $pnls.Count
    for ($k = 0; $k -lt $iterations; $k++) {
        $sum = 0.0
        for ($x = 0; $x -lt $n; $x++) { $sum += $pnls[$rnd.Next(0, $n)] }
        $means[$k] = $sum / $n
    }
    $le = 0
    foreach ($m in $means) { if ($m -le 0) { $le++ } }
    return $le / [double]$iterations
}

function Stats($items) {
    $arr = @($items)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{ trades = 0; net = 0; pf = 0; winRate = 0; avgNet = 0; medianNet = 0; ciLow = 0; ciHigh = 0; pNoEdge = 1.0; mddPct = 0; avgFunding = 0 }
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
    $cum = 0.0; $peak = 0.0; $mdd = 0.0
    foreach ($x in $pnls) { $cum += $x; if ($cum -gt $peak) { $peak = $cum }; $dd = $peak - $cum; if ($dd -gt $mdd) { $mdd = $dd } }
    $rnd = [System.Random]::new($Seed)
    $means = [double[]]::new($BootstrapIterations)
    $n = $pnls.Count
    for ($k = 0; $k -lt $BootstrapIterations; $k++) {
        $sum = 0.0
        for ($x = 0; $x -lt $n; $x++) { $sum += $pnls[$rnd.Next(0, $n)] }
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
        avgFunding = [math]::Round((($arr | ForEach-Object { $_.funding }) | Measure-Object -Average).Average, 2)
    }
}

$devTrades = @($trades | Where-Object { $_.period -eq 'DEV' })
$oosTrades = @($trades | Where-Object { $_.period -eq 'OOS' })

$devStats = @{}
foreach ($v in $variants) { $devStats[$v] = Stats @($devTrades | Where-Object { $_.variant -eq $v }) }
$candidates = @($variants | Where-Object { $s = $devStats[$_]; $s.trades -ge 30 -and $s.pf -ge 1.30 -and $s.pNoEdge -lt 0.05 -and $s.avgNet -gt 0 })
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
    bars = $bars.Count
    fundingRows = $funding.Count
    devStart = $DevStart
    oosStart = $OosStart
    oosEnd = $OosEnd
    devStats = $devStats
    candidates = $candidates
    selected = $selected
    oosStats = $oosStats
    verdict = $verdict
    notes = 'Standalone pre-screen; PASS only means implement in BacktestEngine and run formal WFA. OOS is the new 2022-2024 horizon.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'carry_2026-11-02_summary.json') -Encoding utf8
$trades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'carry_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
$trades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'carry_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8

Write-Host "=== DEV ==="
$variants | ForEach-Object { [pscustomobject]@{ variant = $_; trades = $devStats[$_].trades; pf = $devStats[$_].pf; net = $devStats[$_].net; avgNet = $devStats[$_].avgNet; pNoEdge = $devStats[$_].pNoEdge; funding = $devStats[$_].avgFunding } } | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $oosStats) { $oosStats | Format-List }
