# Confirmation-тест momentum+low-vol на акциях TQBR 2019-2026 (docs/43, 2026-11-02).
# Сигнал: mom20, volPct <= 0.5, hold 5 дней, равный нотионал 100k на позицию.
# Издержки: 0.05%/сторона + 1 bp slippage; funding/дивиденды не моделируются.
#
# Запуск: & .\scripts\research_equity_momentum.ps1
param(
    [string]$Tickers = 'GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN',
    [double]$TargetNotionalRub = 100000,
    [double]$CommissionPctPerSide = 0.05,
    [double]$SlippageBps = 1.0,
    [string]$OutDir = 'artifacts',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [string]$StartDate = '2019-01-01',
    [string]$EndDate = '2026-10-30'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Get-InstrumentTrades([string]$ticker) {
    $sql = @"
select to_char(time, 'YYYY-MM-DD') || '|' || open_price::text || '|' || high_price::text || '|' ||
       low_price::text || '|' || close_price::text
from candles
where ticker = '$ticker' and timeframe = 'DAY_1' and time < current_date
order by time;
"@
    $raw = $sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    $bars = [System.Collections.Generic.List[object]]::new()
    foreach ($line in ($raw -split "`n")) {
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
    if ($bars.Count -lt 300) { return @() }
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
            $cnt = 0; $le = 0
            for ($k = $i - 251; $k -le $i; $k++) { $cnt++; if ($bars[$k].atrPct -le $cur) { $le++ } }
            $bars[$i].volPct = $le / [double]$cnt
        }
    }
    $trades = [System.Collections.Generic.List[object]]::new()
    $nextAvailable = [datetime]::MinValue
    for ($i = 270; $i -lt $bars.Count - 1; $i++) {
        $vol = $bars[$i].volPct
        if ($vol -gt 0.50) { continue }
        $prev = $bars[$i - 20].close
        if ($prev -le 0) { continue }
        $mom = $bars[$i].close / $prev - 1.0
        if ($mom -eq 0) { continue }
        $dir = if ($mom -gt 0) { 1 } else { -1 }
        $entryIdx = $i + 1
        $exitIdx = $entryIdx + 4
        if ($exitIdx -ge $bars.Count) { continue }
        $entryDate = $bars[$entryIdx].date
        if ($entryDate -lt $StartDate -or $entryDate -gt $EndDate) { continue }
        if ([datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv) -lt $nextAvailable) { continue }
        $entryPrice = $bars[$entryIdx].open
        if ($entryPrice -le 0) { continue }
        $exitPrice = $bars[$exitIdx].close
        $qty = $TargetNotionalRub / $entryPrice
        $gross = ($exitPrice - $entryPrice) * $qty * $dir
        $commission = $TargetNotionalRub * ($CommissionPctPerSide / 100.0) * 2.0
        $slippage = $TargetNotionalRub * ($SlippageBps / 10000.0) * 2.0
        $net = $gross - $commission - $slippage
        $trades.Add([pscustomobject]@{
                ticker = $ticker
                signalDate = $bars[$i].date
                direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
                entryDate = $entryDate
                exitDate = $bars[$exitIdx].date
                entryPrice = [math]::Round($entryPrice, 4)
                exitPrice = [math]::Round($exitPrice, 4)
                qty = [math]::Round($qty, 2)
                gross = [math]::Round($gross, 2)
                commission = [math]::Round($commission, 2)
                slippage = [math]::Round($slippage, 2)
                net = [math]::Round($net, 2)
            })
        $nextAvailable = [datetime]::ParseExact($bars[$exitIdx].date, 'yyyy-MM-dd', $inv).AddDays(1)
    }
    return $trades
}

$tickerList = $Tickers.Split(',') | Where-Object { $_ -ne '' }
$allTrades = [System.Collections.Generic.List[object]]::new()
foreach ($t in $tickerList) {
    $tr = Get-InstrumentTrades -ticker $t
    Write-Host ("{0}: {1} trades" -f $t, @($tr).Count)
    foreach ($x in $tr) { $allTrades.Add($x) }
}
Write-Host ("Total trades: {0}" -f $allTrades.Count)

function Quantile([double[]]$values, [double]$q) {
    if ($null -eq $values -or $values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return $s[$idx]
}

function Test-BlockNoEdge($items, [int]$iterations, [int]$seed) {
    $arr = @($items)
    if ($arr.Count -lt 2) { return [pscustomobject]@{ p = 1.0; ciLow = 0; ciHigh = 0; blocks = 0 } }
    $blocks = @($arr | Group-Object exitDate | ForEach-Object { , [double[]]@($_.Group | ForEach-Object { $_.net }) })
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
$perInstrument = @{}
foreach ($t in $tickerList) { $perInstrument[$t] = Stats @($allTrades | Where-Object { $_.ticker -eq $t }) }

$passed = $stats.trades -ge 100 -and $stats.pf -ge 1.30 -and $block.p -lt 0.05 -and $block.ciLow -gt 0 -and $stats.mddPct -le 40
$verdict = if ($passed) { 'PASS_CONFIRMATION' } elseif ($stats.pf -lt 1.30 -or $stats.mddPct -gt 40) { 'REJECTED' } else { 'INCONCLUSIVE' }

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    startDate = $StartDate
    endDate = $EndDate
    targetNotionalRub = $TargetNotionalRub
    commissionPctPerSide = $CommissionPctPerSide
    slippageBps = $SlippageBps
    stats = $stats
    blockP = [math]::Round($block.p, 4)
    blockCi = @([math]::Round($block.ciLow, 2), [math]::Round($block.ciHigh, 2))
    blocks = $block.blocks
    perInstrument = $perInstrument
    verdict = $verdict
    notes = 'Confirmation test on equities; fixed signal from docs/41/42. Fractional qty; dividends/funding not modeled. PASS only means implement in BacktestEngine and run formal WFA.'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'equity_momentum_2026-11-02_summary.json') -Encoding utf8
$allTrades | Export-Csv -LiteralPath (Join-Path $OutDir 'equity_momentum_2026-11-02_trades.csv') -NoTypeInformation -Encoding utf8

Write-Host "=== EQUITY PORTFOLIO ==="
$stats | Format-List
Write-Host ("block p={0} ci=[{1};{2}] blocks={3}" -f $block.p, $block.ciLow, $block.ciHigh, $block.blocks)
Write-Host "=== PER INSTRUMENT ==="
$tickerList | ForEach-Object { [pscustomobject]@{ ticker = $_; trades = $perInstrument[$_].trades; pf = $perInstrument[$_].pf; net = $perInstrument[$_].net; avgNet = $perInstrument[$_].avgNet; mdd = $perInstrument[$_].mddPct } } | Format-Table -AutoSize
Write-Host "verdict=$verdict"
