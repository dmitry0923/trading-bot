# Pre-screen HOUR_1 momentum+low-vol на 5 фьючерсах (docs/44, 2026-11-02).
# Сигнал: mom20 часов, volPct <= 0.5, hold 5 часов, 1 контракт на инструмент.
# Dev 2024-10..2026-10, OOS 2022-12..2024-10; блочный bootstrap по датам выхода.
#
# Запуск: & .\scripts\research_hourly_momentum.ps1
param(
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [double]$CommissionPerSide = 1.5,
    [double]$SlippageBps = 1.0,
    [string]$OutDir = 'artifacts',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [string]$DevStart = '2024-10-24 00:00:00',
    [string]$OosStart = '2022-12-05 00:00:00',
    [string]$OosEnd = '2024-10-23 23:59:59'
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$specs = @{
    'CNYRUBF' = @{ lot = 1000 }
    'USDRUBF' = @{ lot = 1000 }
    'EURRUBF' = @{ lot = 1000 }
    'GLDRUBF' = @{ lot = 1 }
    'IMOEXF'  = @{ lot = 10 }
}

function Get-FundingMap([string]$ticker) {
    $sql = "select clearing_date::text || '|' || value_rub_per_contract::text from funding_history where ticker = '$ticker';"
    $raw = $sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    $map = @{}
    foreach ($line in ($raw -split "`n")) {
        $s = $line.Trim()
        if ($s -eq '') { continue }
        $p = $s -split '\|'
        if ($p.Count -eq 2) { $map[$p[0]] = [double]::Parse($p[1], $inv) }
    }
    return $map
}

function Get-FundingCost([datetime]$entryDt, [datetime]$exitDt, [int]$dir, [hashtable]$fundingMap) {
    $cost = 0.0
    $d = $entryDt.Date
    $end = $exitDt.Date
    while ($d -le $end) {
        $clearing = $d.AddHours(18).AddMinutes(45)
        if ($entryDt -lt $clearing -and $clearing -lt $exitDt) {
            $key = $d.ToString('yyyy-MM-dd')
            if ($fundingMap.ContainsKey($key)) {
                $r = $fundingMap[$key]
                $cost += if ($dir -gt 0) { $r } else { -$r }
            }
        }
        $d = $d.AddDays(1)
    }
    return $cost
}

function Get-InstrumentTrades([string]$ticker) {
    $lot = $specs[$ticker].lot
    $sql = @"
select to_char(time, 'YYYY-MM-DD HH24:MI:SS') || '|' || open_price::text || '|' || high_price::text || '|' ||
       low_price::text || '|' || close_price::text
from candles
where ticker = '$ticker' and timeframe = 'HOUR_1' and time < current_date
order by time;
"@
    $raw = $sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    $bars = [System.Collections.Generic.List[object]]::new()
    foreach ($line in ($raw -split "`n")) {
        $s = $line.Trim()
        if ($s -eq '') { continue }
        $p = $s -split '\|'
        if ($p.Count -ne 5) { continue }
        $dt = [datetime]::ParseExact($p[0], 'yyyy-MM-dd HH:mm:ss', $inv)
        $bars.Add([pscustomobject]@{
                dt = $dt
                date = $dt.ToString('yyyy-MM-dd')
                open = [double]::Parse($p[1], $inv)
                high = [double]::Parse($p[2], $inv)
                low = [double]::Parse($p[3], $inv)
                close = [double]::Parse($p[4], $inv)
                atrPct = 0.0
                volPct = 0.5
            })
    }
    if ($bars.Count -lt 400) { return @() }
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
    $fundingMap = Get-FundingMap -ticker $ticker
    $trades = [System.Collections.Generic.List[object]]::new()
    $nextAvailableIdx = 0
    for ($i = 270; $i -lt $bars.Count - 1; $i++) {
        if ($i -lt $nextAvailableIdx) { continue }
        if ($bars[$i].volPct -gt 0.50) { continue }
        $prev = $bars[$i - 20].close
        if ($prev -le 0) { continue }
        $mom = $bars[$i].close / $prev - 1.0
        if ($mom -eq 0) { continue }
        $dir = if ($mom -gt 0) { 1 } else { -1 }
        $entryIdx = $i + 1
        $exitIdx = $entryIdx + 4
        if ($exitIdx -ge $bars.Count) { continue }
        $entryPrice = $bars[$entryIdx].open
        $exitPrice = $bars[$exitIdx].close
        $gross = ($exitPrice - $entryPrice) * $lot * $dir
        $commission = $CommissionPerSide * 2.0
        $slippage = ($entryPrice * $SlippageBps / 10000.0 * $lot) + ($exitPrice * $SlippageBps / 10000.0 * $lot)
        $fc = Get-FundingCost -entryDt $bars[$entryIdx].dt -exitDt $bars[$exitIdx].dt.AddHours(1) -dir $dir -fundingMap $fundingMap
        $net = $gross - $commission - $slippage - $fc
        $trades.Add([pscustomobject]@{
                ticker = $ticker
                signalTime = $bars[$i].dt.ToString('yyyy-MM-dd HH:mm:ss')
                direction = if ($dir -gt 0) { 'LONG' } else { 'SHORT' }
                entryTime = $bars[$entryIdx].dt.ToString('yyyy-MM-dd HH:mm:ss')
                exitTime = $bars[$exitIdx].dt.ToString('yyyy-MM-dd HH:mm:ss')
                exitDate = $bars[$exitIdx].date
                entryPrice = [math]::Round($entryPrice, 4)
                exitPrice = [math]::Round($exitPrice, 4)
                gross = [math]::Round($gross, 2)
                commission = [math]::Round($commission, 2)
                slippage = [math]::Round($slippage, 2)
                funding = [math]::Round($fc, 2)
                net = [math]::Round($net, 2)
            })
        $nextAvailableIdx = $exitIdx + 1
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

$devStartDt = [datetime]::ParseExact($DevStart, 'yyyy-MM-dd HH:mm:ss', $inv)
$oosStartDt = [datetime]::ParseExact($OosStart, 'yyyy-MM-dd HH:mm:ss', $inv)
$oosEndDt = [datetime]::ParseExact($OosEnd, 'yyyy-MM-dd HH:mm:ss', $inv)
foreach ($t in $allTrades) {
    $sig = [datetime]::ParseExact($t.signalTime, 'yyyy-MM-dd HH:mm:ss', $inv)
    $period = if ($sig -ge $devStartDt) { 'DEV' } elseif ($sig -ge $oosStartDt -and $sig -le $oosEndDt) { 'OOS' } else { 'OTHER' }
    $t | Add-Member -NotePropertyName period -NotePropertyValue $period -Force
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
        return [pscustomobject]@{ trades = 0; net = 0; pf = 0; winRate = 0; avgNet = 0; medianNet = 0; pNoEdge = 1.0; mddPct = 0 }
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
        pNoEdge = [math]::Round((Test-NoEdge $pnls $BootstrapIterations $Seed), 4)
        mddPct = [math]::Round($mdd / 1000.0, 2)
    }
}

$devTrades = @($allTrades | Where-Object { $_.period -eq 'DEV' })
$oosTrades = @($allTrades | Where-Object { $_.period -eq 'OOS' })
$perInstrument = @{}
foreach ($t in $tickerList) { $perInstrument[$t] = Stats @($devTrades | Where-Object { $_.ticker -eq $t }) }
$devStats = Stats $devTrades
$devBlock = Test-BlockNoEdge $devTrades $BootstrapIterations $Seed

$passedDev = $devStats.trades -ge 100 -and $devStats.pf -ge 1.30 -and $devBlock.p -lt 0.05 -and $devStats.avgNet -gt 0
$selected = if ($passedDev) { 'PORTFOLIO' } else { $null }
$oosStats = $null
$oosBlock = $null
$verdict = if ($passedDev) { 'PENDING_OOS' } else { 'NO_CANDIDATE' }
if ($passedDev) {
    $oosStats = Stats $oosTrades
    $oosBlock = Test-BlockNoEdge $oosTrades $BootstrapIterations $Seed
    $ok = $oosStats.trades -ge 30 -and $oosStats.pf -ge 1.30 -and $oosBlock.p -lt 0.05 -and $oosBlock.ciLow -gt 0 -and $oosStats.mddPct -le 40
    $verdict = if ($ok) { 'PASS_PRECHECK' } elseif ($oosStats.pf -lt 1.30 -or $oosStats.mddPct -gt 40) { 'REJECTED' } else { 'INCONCLUSIVE' }
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    devStart = $DevStart
    oosStart = $OosStart
    oosEnd = $OosEnd
    devStats = $devStats
    devBlockP = [math]::Round($devBlock.p, 4)
    devBlockCi = @([math]::Round($devBlock.ciLow, 2), [math]::Round($devBlock.ciHigh, 2))
    devBlocks = $devBlock.blocks
    perInstrument = $perInstrument
    selected = $selected
    oosStats = $oosStats
    oosBlockP = if ($null -ne $oosBlock) { [math]::Round($oosBlock.p, 4) } else { $null }
    oosBlockCi = if ($null -ne $oosBlock) { @([math]::Round($oosBlock.ciLow, 2), [math]::Round($oosBlock.ciHigh, 2)) } else { $null }
    verdict = $verdict
    notes = 'HOUR_1 portfolio pre-screen: 1 contract per instrument; block bootstrap by exit date. PASS only means formal engine validation (step A).'
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'hourly_momentum_2026-11-02_summary.json') -Encoding utf8
$allTrades | Where-Object { $_.period -eq 'DEV' } | Export-Csv -LiteralPath (Join-Path $OutDir 'hourly_momentum_2026-11-02_dev.csv') -NoTypeInformation -Encoding utf8
if ($null -ne $selected) {
    $allTrades | Where-Object { $_.period -eq 'OOS' } | Export-Csv -LiteralPath (Join-Path $OutDir 'hourly_momentum_2026-11-02_oos.csv') -NoTypeInformation -Encoding utf8
}

Write-Host "=== DEV PORTFOLIO ==="
$devStats | Format-List
Write-Host ("block p={0} ci=[{1};{2}] blocks={3}" -f $devBlock.p, $devBlock.ciLow, $devBlock.ciHigh, $devBlock.blocks)
Write-Host "=== PER INSTRUMENT (DEV) ==="
$tickerList | ForEach-Object { [pscustomobject]@{ ticker = $_; trades = $perInstrument[$_].trades; pf = $perInstrument[$_].pf; net = $perInstrument[$_].net; avgNet = $perInstrument[$_].avgNet; pNoEdge = $perInstrument[$_].pNoEdge } } | Format-Table -AutoSize
Write-Host "selected=$selected verdict=$verdict"
if ($null -ne $oosStats) { $oosStats | Format-List; Write-Host ("OOS block p={0} ci=[{1};{2}]" -f $oosBlock.p, $oosBlock.ciLow, $oosBlock.ciHigh) }
