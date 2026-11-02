# Pre-screen volatility-regime фильтра для дневного momentum CNYRUBF (docs/41, 2026-11-02).
# 3 фиксированных варианта, dev 2024-10..2026-10, OOS 2022-12..2024-10.
#
# Запуск: & .\scripts\research_volregime.ps1
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

$sqlBars = @"
select to_char(time, 'YYYY-MM-DD') || '|' || open_price::text || '|' || high_price::text || '|' ||
       low_price::text || '|' || close_price::text
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
    if ($p.Count -ne 5) { continue }
    $bars.Add([pscustomobject]@{
            date = $p[0]
            open = [double]::Parse($p[1], $inv)
            high = [double]::Parse($p[2], $inv)
            low = [double]::Parse($p[3], $inv)
            close = [double]::Parse($p[4], $inv)
            atrPct = 0.0
            volPct = 0.5
        })
}
if ($bars.Count -lt 300) { throw "Too few bars: $($bars.Count)" }
Write-Host ("Loaded {0} DAY_1 bars: {1}..{2}" -f $bars.Count, $bars[0].date, $bars[$bars.Count - 1].date)

# ATR20% и его перцентиль за 252 дня.
$tr = [double[]]::new($bars.Count)
for ($i = 0; $i -lt $bars.Count; $i++) {
    $prevClose = if ($i -gt 0) { $bars[$i - 1].close } else { $bars[$i].close }
    $b = $bars[$i]
    $tr[$i] = [math]::Max($b.high - $b.low, [math]::Max([math]::Abs($b.high - $prevClose), [math]::Abs($b.low - $prevClose)))
}
for ($i = 19; $i -lt $bars.Count; $i++) {
    $sum = 0.0
    for ($k = $i - 19; $k -le $i; $k++) { $sum += $tr[$k] }
    $atr = $sum / 20.0
    $bars[$i].atrPct = if ($bars[$i].close -gt 0) { $atr / $bars[$i].close } else { 0.0 }
    if ($i -ge 270) {
        $cur = $bars[$i].atrPct
        $cnt = 0
        $le = 0
        for ($k = $i - 251; $k -le $i; $k++) {
            $cnt++
            if ($bars[$k].atrPct -le $cur) { $le++ }
        }
        $bars[$i].volPct = $le / [double]$cnt
    }
}

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

function Get-FundingCost([string]$entryDate, [string]$exitDate, [int]$dir) {
    $cost = 0.0
    $d = [datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv)
    $end = [datetime]::ParseExact($exitDate, 'yyyy-MM-dd', $inv)
    $entryDt = $d.AddHours(10)
    $exitDt = $end.AddHours(23).AddMinutes(40)
    while ($d -le $end) {
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

function New-VolTrade {
    param($signalDate, $variant, $dir, $entryDate, $exitDate, $entryPrice, $exitPrice)
    $gross = ($exitPrice - $entryPrice) * $LotSize * $dir
    $commission = $CommissionPerSide * 2.0
    $slippage = ($entryPrice * $SlippageBps / 10000.0 * $LotSize) + ($exitPrice * $SlippageBps / 10000.0 * $LotSize)
    $fc = Get-FundingCost -entryDate $entryDate -exitDate $exitDate -dir $dir
    $net = $gross - $commission - $slippage - $fc
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
        funding = [math]::Round($fc, 2)
        net = [math]::Round($net, 2)
    }
}

$variants = @(
    @{ id = 'V1_lowvol'; mode = 'low' },
    @{ id = 'V2_highvol'; mode = 'high' },
    @{ id = 'V3_all'; mode = 'all' }
)
$trades = [System.Collections.Generic.List[object]]::new()

foreach ($v in $variants) {
    $nextAvailable = [datetime]::MinValue
    for ($i = 20; $i -lt $bars.Count - 1; $i++) {
        if ($i -lt 270) { continue }
        $prev = $bars[$i - 20].close
        if ($prev -le 0) { continue }
        $mom = $bars[$i].close / $prev - 1.0
        if ($mom -eq 0) { continue }
        $vol = $bars[$i].volPct
        $take = switch ($v.mode) {
            'low' { $vol -le 0.50 }
            'high' { $vol -gt 0.50 }
            default { $true }
        }
        if (-not $take) { continue }
        $dir = if ($mom -gt 0) { 1 } else { -1 }
        $entryIdx = $i + 1
        $exitIdx = $entryIdx + 4
        if ($exitIdx -ge $bars.Count) { continue }
        $entryDate = $bars[$entryIdx].date
        if ([datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv) -lt $nextAvailable) { continue }
        $trade = New-VolTrade -signalDate $bars[$i].date -variant $v.id -dir $dir -entryDate $entryDate `
            -exitDate $bars[$exitIdx].date -entryPrice $bars[$entryIdx].open -exitPrice $bars[$exitIdx].close
        $trades.Add($trade)
        $nextAvailable = [datetime]::ParseExact($bars[$exitIdx].date, 'yyyy-MM-dd', $inv).AddDays(1)
    }
}

Write-Host ("Trades simulated: {0}" -f $trades.Count)
foreach ($t in $trades) {
    $t | Add-Member -NotePropertyName period -NotePropertyValue $(if ($t.signalDate -ge $DevStart) { 'DEV' } elseif ($t.signalDate -ge $OosStart -and $t.signalDate -le $OosEnd) { 'OOS' } else { 'OTHER' }) -Force
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
$ids = @('V1_lowvol', 'V2_highvol', 'V3_all')

$devStats = @{}
foreach ($id in $ids) { $devStats[$id] = Stats @($devTrades | Where-Object { $_.variant -eq $id }) }
$candidates = @($ids | Where-Object { $s = $devStats[$_]; $s.trades -ge 30 -and $s.pf -ge 1.30 -and $s.pNoEdge -lt 0.05 -and $s.avgNet -gt 0 })
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
    notes = 'Standalone volatility-regime pre-screen; PASS only means implement in BacktestEngine and run formal WFA. OOS is the new 2022-2024 horizon.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'volregime_2026-11-02_summary.json') -Encoding utf8
$trades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'volregime_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($null -ne $selected) {
    $trades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'volregime_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
}

Write-Host "=== DEV ==="
$ids | ForEach-Object { [pscustomobject]@{ variant = $_; trades = $devStats[$_].trades; pf = $devStats[$_].pf; net = $devStats[$_].net; avgNet = $devStats[$_].avgNet; pNoEdge = $devStats[$_].pNoEdge; funding = $devStats[$_].avgFunding } } | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $oosStats) { $oosStats | Format-List }
