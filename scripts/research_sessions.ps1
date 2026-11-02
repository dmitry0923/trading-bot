# Pre-screen сессионных (time-of-day) стратегий CNYRUBF (docs/38, 2026-11-02).
# 5 фиксированных вариантов, 1 сделка/день, dev 2024-10..2026-10, OOS 2022-12..2024-10.
#
# Запуск: & .\scripts\research_sessions.ps1
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

# ---------- HOUR_1 ----------
$sqlBars = @"
select to_char(time, 'YYYY-MM-DD HH24:MI:SS') || '|' ||
       open_price::text || '|' || high_price::text || '|' || low_price::text || '|' || close_price::text
from candles
where ticker = '$Ticker' and timeframe = 'HOUR_1' and time < current_date
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
        hour = $dt.Hour
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

function Get-FundingCost([string]$date, [int]$dir) {
    if (-not $funding.ContainsKey($date)) { return 0.0 }
    $r = $funding[$date]
    if ($dir -gt 0) { return $r } else { return -$r }
}

function New-SessionTrade {
    param($date, $variant, $dir, $entryTime, $exitTime, $entryPrice, $exitPrice, [bool]$chargeFunding)
    $gross = ($exitPrice - $entryPrice) * $LotSize * $dir
    $commission = $CommissionPerSide * 2.0
    $slippage = ($entryPrice * $SlippageBps / 10000.0 * $LotSize) + ($exitPrice * $SlippageBps / 10000.0 * $LotSize)
    $fc = if ($chargeFunding) { Get-FundingCost -date $date -dir $dir } else { 0.0 }
    $net = $gross - $commission - $slippage - $fc
    [pscustomobject]@{
        date = $date
        variant = $variant
        direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
        entryTime = $entryTime
        exitTime = $exitTime
        entryPrice = [math]::Round($entryPrice, 4)
        exitPrice = [math]::Round($exitPrice, 4)
        gross = [math]::Round($gross, 2)
        commission = [math]::Round($commission, 2)
        slippage = [math]::Round($slippage, 2)
        funding = [math]::Round($fc, 2)
        net = [math]::Round($net, 2)
    }
}

$variants = @('S1_evening_long', 'S2_evening_short', 'S3_evening_momentum', 'S4_evening_reversal', 'S5_morning_short')
$trades = [System.Collections.Generic.List[object]]::new()

foreach ($date in $days) {
    $bars = $barsByDay[$date]
    if ($bars.Count -lt 3) { continue }
    $bar10 = $bars | Where-Object { $_.hour -ge 10 } | Select-Object -First 1
    $bar17 = $bars | Where-Object { $_.hour -eq 17 } | Select-Object -First 1
    $bar18 = $bars | Where-Object { $_.hour -eq 18 } | Select-Object -First 1
    $last = $bars[$bars.Count - 1]

    # S1-S4: evening session exists (bar 18 and a later bar)
    if ($null -ne $bar18 -and $last.hour -ge 19) {
        $entryTime = $bar18.dt.ToString('yyyy-MM-dd HH:mm:ss')
        $exitTime = $last.dt.ToString('yyyy-MM-dd HH:mm:ss')
        $trades.Add((New-SessionTrade -date $date -variant 'S1_evening_long' -dir 1 -entryTime $entryTime -exitTime $exitTime -entryPrice $bar18.open -exitPrice $last.close -chargeFunding $true))
        $trades.Add((New-SessionTrade -date $date -variant 'S2_evening_short' -dir -1 -entryTime $entryTime -exitTime $exitTime -entryPrice $bar18.open -exitPrice $last.close -chargeFunding $true))
        if ($null -ne $bar10 -and $null -ne $bar17) {
            $move = $bar17.close - $bar10.open
            if ($move -ne 0) {
                $dir = if ($move -gt 0) { 1 } else { -1 }
                $trades.Add((New-SessionTrade -date $date -variant 'S3_evening_momentum' -dir $dir -entryTime $entryTime -exitTime $exitTime -entryPrice $bar18.open -exitPrice $last.close -chargeFunding $true))
                $trades.Add((New-SessionTrade -date $date -variant 'S4_evening_reversal' -dir (-1 * $dir) -entryTime $entryTime -exitTime $exitTime -entryPrice $bar18.open -exitPrice $last.close -chargeFunding $true))
            }
        }
    }

    # S5: morning short 10:00 -> 17:00
    if ($null -ne $bar10 -and $null -ne $bar17) {
        $trades.Add((New-SessionTrade -date $date -variant 'S5_morning_short' -dir -1 -entryTime $bar10.dt.ToString('yyyy-MM-dd HH:mm:ss') -exitTime $bar17.dt.ToString('yyyy-MM-dd HH:mm:ss') -entryPrice $bar10.open -exitPrice $bar17.close -chargeFunding $false))
    }
}

Write-Host ("Trades simulated: {0}" -f $trades.Count)
foreach ($t in $trades) {
    $t | Add-Member -NotePropertyName period -NotePropertyValue $(if ($t.date -ge $DevStart) { 'DEV' } elseif ($t.date -ge $OosStart -and $t.date -le $OosEnd) { 'OOS' } else { 'OTHER' }) -Force
}

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
    days = $days.Count
    fundingRows = $funding.Count
    devStart = $DevStart
    oosStart = $OosStart
    oosEnd = $OosEnd
    devStats = $devStats
    candidates = $candidates
    selected = $selected
    oosStats = $oosStats
    verdict = $verdict
    notes = 'Standalone session pre-screen; PASS only means implement in BacktestEngine and run formal WFA. OOS is the new 2022-2024 horizon.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'sessions_2026-11-02_summary.json') -Encoding utf8
$trades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'sessions_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($null -ne $selected) {
    $trades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'sessions_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
}

Write-Host "=== DEV ==="
$variants | ForEach-Object { [pscustomobject]@{ variant = $_; trades = $devStats[$_].trades; pf = $devStats[$_].pf; net = $devStats[$_].net; avgNet = $devStats[$_].avgNet; pNoEdge = $devStats[$_].pNoEdge; funding = $devStats[$_].avgFunding } } | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $oosStats) { $oosStats | Format-List }
