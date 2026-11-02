# Forward paper momentum+low-vol на 5 фьючерсах (docs/46, шаг B).
# Никаких live-ордеров: только состояние и журнал гипотетических сделок.
#
# Ежедневный запуск после закрытия (>= 23:50 МСК): & .\scripts\paper_momentum_daily.ps1
# Первичная инициализация:                         & .\scripts\paper_momentum_daily.ps1 -Init
#
# Правила заморожены docs/46 §1: mom20, ATR20%, volPct<=0.50 за 252 бара,
# hold 5 баров включая вход, 1 контракт на инструмент, комиссия 1.5 ₽/сторона,
# slippage 1 bp, funding по фактическому funding_history.
#
# Состояние (state.json) хранит lastProcessedDate/position/pending на инструмент;
# trades.csv — только закрытые сделки. Скрипт идемпотентен: повторный запуск
# без новых завершённых баров ничего не меняет.
param(
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [int]$MomentumBars = 20,
    [int]$AtrBars = 20,
    [int]$VolLookbackBars = 252,
    [double]$MaxVolPercentile = 0.5,
    [int]$HoldBars = 5,
    [double]$CommissionPerSide = 1.5,
    [double]$SlippageBps = 1.0,
    [string]$StartAfter = '2026-11-03',
    [string]$AsOf = '',
    [string]$StateDir = 'artifacts/paper_momentum',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$BootstrapIterations = 2000,
    [int]$Seed = 42,
    [switch]$Init,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$statePath = Join-Path $StateDir 'state.json'
$tradesPath = Join-Path $StateDir 'trades.csv'
$summaryPath = Join-Path $StateDir 'summary.json'

$script:TickerList = @($Tickers.Split(',') | Where-Object { $_ -ne '' })
$script:StartAfter = ([datetime]::ParseExact($StartAfter, 'yyyy-MM-dd', $inv)).ToString('yyyy-MM-dd')
$script:AsOf = $AsOf

$script:Specs = @{
    'CNYRUBF' = @{ lot = 1000 }
    'USDRUBF' = @{ lot = 1000 }
    'EURRUBF' = @{ lot = 1000 }
    'GLDRUBF' = @{ lot = 1 }
    'IMOEXF'  = @{ lot = 10 }
}

function Get-FrozenParams {
    return [ordered]@{
        tickers = @($script:TickerList)
        momentumBars = $script:MomentumBars
        atrBars = $script:AtrBars
        volLookbackBars = $script:VolLookbackBars
        maxVolPercentile = $script:MaxVolPercentile
        holdBars = $script:HoldBars
        commissionPerSide = $script:CommissionPerSide
        slippageBps = $script:SlippageBps
    }
}

function Get-DefaultAsOf {
    # MSK = UTC+3 (без перехода на летнее время с 2014). До 23:50 МСК
    # сегодняшний DAY_1-бар неполный, поэтому по умолчанию берём вчера.
    $nowMsk = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(3))
    $today = $nowMsk.ToString('yyyy-MM-dd')
    $afterClose = $nowMsk.TimeOfDay -ge ([TimeSpan]::FromHours(23) + [TimeSpan]::FromMinutes(50))
    if ([string]::IsNullOrWhiteSpace($script:AsOf)) {
        return [pscustomobject]@{
            date = $(if ($afterClose) { $today } else { $nowMsk.AddDays(-1).ToString('yyyy-MM-dd') })
            today = $today
            afterClose = $afterClose
        }
    }
    return [pscustomobject]@{ date = $script:AsOf; today = $today; afterClose = $afterClose }
}

function Resolve-AsOf {
    $resolved = Get-DefaultAsOf
    $date = ([datetime]::ParseExact($resolved.date, 'yyyy-MM-dd', $inv)).ToString('yyyy-MM-dd')
    if ($date -gt $resolved.today) { throw "AsOf=$date is in the future (today=$($resolved.today))." }
    if ($date -eq $resolved.today -and -not $resolved.afterClose) {
        throw "Today's DAY_1 bar is not complete before 23:50 MSK; run after close or pass -AsOf yesterday."
    }
    return $date
}

function Invoke-DbSql([string]$Sql) {
    $raw = $Sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "psql failed (exit $LASTEXITCODE)." }
    return $raw
}

function Load-Bars([string]$Ticker, [string]$AsOfDate) {
    $sql = @"
select to_char(time, 'YYYY-MM-DD') || '|' || open_price::text || '|' || high_price::text || '|' ||
       low_price::text || '|' || close_price::text
from candles
where ticker = '$Ticker' and timeframe = 'DAY_1'
  and time < (date '$AsOfDate' + interval '1 day')
order by time;
"@
    $raw = Invoke-DbSql $sql
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
            })
    }
    return , $bars
}

function Get-FundingMap([string]$Ticker) {
    $sql = "select clearing_date::text || '|' || value_rub_per_contract::text from funding_history where ticker = '$Ticker';"
    $raw = Invoke-DbSql $sql
    $map = @{}
    foreach ($line in ($raw -split "`n")) {
        $s = $line.Trim()
        if ($s -eq '') { continue }
        $p = $s -split '\|'
        if ($p.Count -eq 2) { $map[$p[0]] = [double]::Parse($p[1], $inv) }
    }
    return $map
}

function Get-TradingDates($Bars) {
    $dates = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($b in $Bars) { [void]$dates.Add($b.date) }
    return , $dates
}

function Get-FundingCost(
    [string]$EntryDate,
    [string]$ExitDate,
    [int]$Direction,
    [hashtable]$FundingMap,
    [System.Collections.Generic.HashSet[string]]$TradingDates
) {
    $cost = 0.0
    $missing = [System.Collections.Generic.List[string]]::new()
    $d = [datetime]::ParseExact($EntryDate, 'yyyy-MM-dd', $inv)
    $end = [datetime]::ParseExact($ExitDate, 'yyyy-MM-dd', $inv)
    $entryDt = $d.AddHours(10)
    $exitDt = $end.AddHours(23).AddMinutes(40)
    while ($d -le $end) {
        $key = $d.ToString('yyyy-MM-dd')
        $clearing = $d.AddHours(18).AddMinutes(45)
        # Клиринг есть только в торговый день (в календаре есть DAY_1-бар).
        if ($entryDt -lt $clearing -and $clearing -lt $exitDt -and $TradingDates.Contains($key)) {
            if ($FundingMap.ContainsKey($key)) {
                $r = [double]$FundingMap[$key]
                $cost += if ($Direction -gt 0) { $r } else { -$r }
            } else {
                $missing.Add($key)
            }
        }
        $d = $d.AddDays(1)
    }
    return [pscustomobject]@{ cost = $cost; missing = $missing }
}

function Get-SignalAt($Bars, [int]$Index) {
    $minIndex = $script:VolLookbackBars + $script:AtrBars - 2
    if ($Index -lt $minIndex -or $Index -ge $Bars.Count) { return $null }
    $prev = $Bars[$Index - $script:MomentumBars].close
    if ($prev -le 0) { return $null }
    $mom = $Bars[$Index].close / $prev - 1.0
    if ($mom -eq 0) { return $null }
    $start = $Index - $minIndex
    $tr = [double[]]::new($minIndex + 1)
    for ($j = $start; $j -le $Index; $j++) {
        $pc = if ($j -gt 0) { $Bars[$j - 1].close } else { $Bars[$j].close }
        $b = $Bars[$j]
        $tr[$j - $start] = [math]::Max($b.high - $b.low, [math]::Max([math]::Abs($b.high - $pc), [math]::Abs($b.low - $pc)))
    }
    $atrp = [double[]]::new($script:VolLookbackBars)
    for ($k = 0; $k -lt $script:VolLookbackBars; $k++) {
        $j = $Index - $script:VolLookbackBars + 1 + $k
        $sum = 0.0
        for ($t = $j - $script:AtrBars + 1; $t -le $j; $t++) { $sum += $tr[$t - $start] }
        $atrp[$k] = if ($Bars[$j].close -gt 0) { ($sum / $script:AtrBars) / $Bars[$j].close } else { 0.0 }
    }
    $cur = $atrp[$script:VolLookbackBars - 1]
    $le = 0
    foreach ($x in $atrp) { if ($x -le $cur) { $le++ } }
    $volPct = $le / [double]$script:VolLookbackBars
    if ($volPct -gt $script:MaxVolPercentile) { return $null }
    return [pscustomobject]@{
        date = $Bars[$Index].date
        direction = $(if ($mom -gt 0) { 1 } else { -1 })
        volPct = $volPct
    }
}

function Test-StateParams($State) {
    if ([int]$State.version -ne 1) { throw "Unsupported state version: $($State.version)" }
    if ([string]$State.startAfter -ne $script:StartAfter) {
        throw "State startAfter=$($State.startAfter) != -StartAfter=$($script:StartAfter). Preregistered parameters are frozen."
    }
    $frozen = Get-FrozenParams
    if ((@($State.params.tickers) -join ',') -ne (@($frozen.tickers) -join ',')) {
        throw 'State universe differs from -Tickers. Preregistered parameters are frozen.'
    }
    foreach ($key in @('momentumBars', 'atrBars', 'volLookbackBars', 'holdBars')) {
        if ([int]$State.params.$key -ne [int]$frozen[$key]) {
            throw "State param $key=$($State.params.$key) != -$key=$($frozen[$key]). Preregistered parameters are frozen."
        }
    }
    foreach ($key in @('maxVolPercentile', 'commissionPerSide', 'slippageBps')) {
        if ([math]::Abs([double]$State.params.$key - [double]$frozen[$key]) -gt 1e-9) {
            throw "State param $key=$($State.params.$key) != -$key=$($frozen[$key]). Preregistered parameters are frozen."
        }
    }
}

function Get-Stats($Trades) {
    $arr = @($Trades)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{ trades = 0; net = 0.0; pf = 0.0; winRate = 0.0; avgNet = 0.0; medianNet = 0.0; mddPct = 0.0 }
    }
    $pnls = [double[]]::new($arr.Count)
    $sumWin = 0.0
    $sumLoss = 0.0
    $wins = 0
    for ($i = 0; $i -lt $arr.Count; $i++) {
        $v = [double]$arr[$i].net
        $pnls[$i] = $v
        if ($v -gt 0) { $sumWin += $v; $wins++ } else { $sumLoss += [math]::Abs($v) }
    }
    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    $net = 0.0
    foreach ($x in $pnls) { $net += $x }
    $avg = $net / $arr.Count
    $sortedPnl = @($pnls | Sort-Object)
    $mid = [int][math]::Floor($sortedPnl.Count / 2)
    $median = if ($sortedPnl.Count % 2 -eq 1) {
        $sortedPnl[$mid]
    } else {
        ($sortedPnl[$mid - 1] + $sortedPnl[$mid]) / 2.0
    }
    $cum = 0.0
    $peak = 0.0
    $mdd = 0.0
    foreach ($x in $pnls) {
        $cum += $x
        if ($cum -gt $peak) { $peak = $cum }
        $dd = $peak - $cum
        if ($dd -gt $mdd) { $mdd = $dd }
    }
    return [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round($net, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins / $arr.Count, 1)
        avgNet = [math]::Round($avg, 2)
        medianNet = [math]::Round($median, 2)
        mddPct = [math]::Round($mdd / 1000.0, 2)
    }
}

function Get-BlockNoEdge($Trades, [int]$Iterations, [int]$SeedValue) {
    $arr = @($Trades)
    if ($arr.Count -lt 2) { return [pscustomobject]@{ p = 1.0; ciLow = 0.0; ciHigh = 0.0; blocks = 0 } }
    $byDate = @{}
    foreach ($t in $arr) {
        $d = [string]$t.exitDate
        if (-not $byDate.ContainsKey($d)) { $byDate[$d] = [System.Collections.Generic.List[double]]::new() }
        $byDate[$d].Add([double]$t.net)
    }
    $blocks = @($byDate.Values | ForEach-Object { , ([double[]]@($_)) })
    if ($blocks.Count -lt 2) { return [pscustomobject]@{ p = 1.0; ciLow = 0.0; ciHigh = 0.0; blocks = 0 } }
    $rnd = [System.Random]::new($SeedValue)
    $means = [double[]]::new($Iterations)
    for ($k = 0; $k -lt $Iterations; $k++) {
        $sum = 0.0
        $n = 0
        for ($b = 0; $b -lt $blocks.Count; $b++) {
            $block = $blocks[$rnd.Next(0, $blocks.Count)]
            foreach ($x in $block) { $sum += $x; $n++ }
        }
        $means[$k] = if ($n -gt 0) { $sum / $n } else { 0.0 }
    }
    $le = 0
    foreach ($m in $means) { if ($m -le 0) { $le++ } }
    $sortedMeans = $means | Sort-Object
    $q = { param($p) $sortedMeans[[math]::Min($sortedMeans.Count - 1, [math]::Max(0, [int][math]::Floor(($sortedMeans.Count - 1) * $p)))] }
    return [pscustomobject]@{
        p = $le / [double]$Iterations
        ciLow = & $q 0.025
        ciHigh = & $q 0.975
        blocks = $blocks.Count
    }
}

function Write-StateFiles($State) {
    $State.updatedAt = (Get-Date).ToString('o')
    $tmp = "$statePath.tmp"
    $State | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $tmp -Encoding utf8
    Move-Item -LiteralPath $tmp -Destination $statePath -Force

    $sorted = @($State.trades | Sort-Object @{ Expression = { [string]$_.exitDate } },
        @{ Expression = { [string]$_.entryDate } }, @{ Expression = { [string]$_.ticker } })
    if ($sorted.Count -gt 0) {
        $sorted | Export-Csv -LiteralPath $tradesPath -NoTypeInformation -Encoding utf8
    } else {
        'ticker,signalDate,direction,entryDate,exitDate,entryPrice,exitPrice,gross,commission,slippage,funding,net,holdBars,recordedAt' |
            Set-Content -LiteralPath $tradesPath -Encoding utf8
    }

    $stats = Get-Stats $sorted
    $block = Get-BlockNoEdge $sorted $BootstrapIterations $Seed
    $gates = [ordered]@{
        F1_closedTrades_ge_30 = $stats.trades -ge 30
        F2_profitFactor_ge_1_30 = $stats.pf -ge 1.30
        F3_blockP_lt_0_05 = $block.p -lt 0.05
        F4_ci95Low_gt_0 = $block.ciLow -gt 0
        F5_mdd_le_40 = $stats.trades -gt 0 -and $stats.mddPct -le 40
    }
    $verdict = if ($stats.trades -lt 30) {
        'COLLECTING'
    } elseif ($stats.pf -lt 1.30 -or $stats.mddPct -gt 40) {
        'REJECTED'
    } elseif ($block.p -lt 0.05 -and $block.ciLow -gt 0) {
        'PASS'
    } else {
        'INCONCLUSIVE'
    }
    $openPositions = @($State.tickers.Values | Where-Object { $null -ne $_.position }).Count
    $pendingEntries = @($State.tickers.Values | Where-Object { $null -ne $_.pending }).Count
    $firstSignal = @($sorted | Select-Object -First 1).signalDate
    $lastExit = @($sorted | Select-Object -Last 1).exitDate
    $summary = [ordered]@{
        generatedAt = (Get-Date).ToString('o')
        asOf = $script:ResolvedAsOf
        startAfter = $script:StartAfter
        params = Get-FrozenParams
        closedTrades = $stats.trades
        openPositions = $openPositions
        pendingEntries = $pendingEntries
        firstSignalDate = $firstSignal
        lastExitDate = $lastExit
        stats = $stats
        blockBootstrap = [ordered]@{
            iterations = $BootstrapIterations
            seed = $Seed
            pValue = [math]::Round($block.p, 4)
            ci95Low = [math]::Round($block.ciLow, 2)
            ci95High = [math]::Round($block.ciHigh, 2)
            blocks = $block.blocks
        }
        gates = $gates
        evaluated = $stats.trades -ge 30
        verdict = $verdict
        note = 'Forward paper only; no live orders. Verdict after >= 30 closed trades (docs/46).'
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    return $summary
}

$script:ResolvedAsOf = Resolve-AsOf

if ($Init) {
    if ((Test-Path -LiteralPath $statePath) -and -not $Force) {
        throw "State already exists: $statePath. Use -Force to overwrite."
    }
    $now = (Get-Date).ToString('o')
    $state = [ordered]@{
        version = 1
        createdAt = $now
        updatedAt = $now
        startAfter = $script:StartAfter
        params = Get-FrozenParams
        tickers = @{}
        trades = @()
    }
    foreach ($ticker in $script:TickerList) {
        $bars = Load-Bars -Ticker $ticker -AsOfDate $script:ResolvedAsOf
        $lastBar = if ($bars.Count -gt 0) { $bars[$bars.Count - 1].date } else { $script:StartAfter }
        $lastProcessed = if ($lastBar -gt $script:StartAfter) { $lastBar } else { $script:StartAfter }
        $state.tickers[$ticker] = @{
            lastProcessedDate = $lastProcessed
            position = $null
            pending = $null
        }
    }
    $summary = Write-StateFiles $state
    Write-Host "Initialized $statePath (startAfter=$($script:StartAfter), asOf=$($script:ResolvedAsOf)); no historical trades." -ForegroundColor Green
    $summary | Format-List
    return
}

if (-not (Test-Path -LiteralPath $statePath)) {
    throw "No state at $statePath. Run with -Init first (docs/46)."
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
Test-StateParams $state
if ($null -eq $state.trades) { $state.trades = @() }

$newBarCount = 0
foreach ($ticker in $script:TickerList) {
    if (-not $state.tickers.ContainsKey($ticker)) { throw "State has no ticker $ticker." }
    $tickerState = $state.tickers[$ticker]
    $bars = Load-Bars -Ticker $ticker -AsOfDate $script:ResolvedAsOf
    if ($bars.Count -eq 0) { continue }
    $lot = [double]$script:Specs[$ticker].lot
    $fundingMap = Get-FundingMap $ticker
    $tradingDates = Get-TradingDates $bars
    $indexByDate = @{}
    for ($i = 0; $i -lt $bars.Count; $i++) { $indexByDate[$bars[$i].date] = $i }

    $floor = if ([string]$tickerState.lastProcessedDate -gt $script:StartAfter) {
        [string]$tickerState.lastProcessedDate
    } else {
        $script:StartAfter
    }
    $newBars = @($bars | Where-Object { $_.date -gt $floor -and $_.date -le $script:ResolvedAsOf })
    $newBarCount += $newBars.Count
    foreach ($bar in $newBars) {
        # 1) Выход по времени удержания: 5-й бар включая бар входа, close.
        if ($null -ne $tickerState.position -and $bar.date -gt $tickerState.position.entryDate) {
            $pos = $tickerState.position
            $pos.barsHeld = [int]$pos.barsHeld + 1
            if ([int]$pos.barsHeld -ge $HoldBars) {
                $direction = [int]$pos.direction
                $entryPrice = [double]$pos.entryPrice
                $exitPrice = [double]$bar.close
                $fc = Get-FundingCost -EntryDate $pos.entryDate -ExitDate $bar.date -Direction $direction `
                    -FundingMap $fundingMap -TradingDates $tradingDates
                if ($fc.missing.Count -gt 0) {
                    throw "Missing funding_history for $ticker on $($fc.missing -join ', '). Backfill funding and rerun; position is kept open."
                }
                $gross = ($exitPrice - $entryPrice) * $lot * $direction
                $commission = $CommissionPerSide * 2.0
                $slippage = ($entryPrice * $SlippageBps / 10000.0 * $lot) + ($exitPrice * $SlippageBps / 10000.0 * $lot)
                $net = $gross - $commission - $slippage - $fc.cost
                $trade = [ordered]@{
                    ticker = $ticker
                    signalDate = $pos.signalDate
                    direction = $(if ($direction -gt 0) { 'LONG' } else { 'SHORT' })
                    entryDate = $pos.entryDate
                    exitDate = $bar.date
                    entryPrice = [math]::Round($entryPrice, 4)
                    exitPrice = [math]::Round($exitPrice, 4)
                    gross = [math]::Round($gross, 2)
                    commission = [math]::Round($commission, 2)
                    slippage = [math]::Round($slippage, 2)
                    funding = [math]::Round($fc.cost, 2)
                    net = [math]::Round($net, 2)
                    holdBars = [int]$pos.barsHeld
                    recordedAt = (Get-Date).ToString('o')
                }
                $state.trades = @($state.trades) + $trade
                $tickerState.position = $null
            }
        }

        # 2) Вход по открытию первого бара после сигнала.
        if ($null -eq $tickerState.position -and $null -ne $tickerState.pending) {
            $pending = $tickerState.pending
            if ($bar.date -gt $pending.signalDate) {
                $tickerState.position = @{
                    signalDate = $pending.signalDate
                    entryDate = $bar.date
                    entryPrice = [double]$bar.open
                    direction = [int]$pending.direction
                    barsHeld = 1
                }
                $tickerState.pending = $null
            }
        }

        # 3) Новый сигнал только когда позиции нет; во время удержания сигналы игнорируются.
        if ($null -eq $tickerState.position -and $null -eq $tickerState.pending) {
            $idx = [int]$indexByDate[$bar.date]
            $sig = Get-SignalAt $bars $idx
            if ($null -ne $sig) {
                $tickerState.pending = @{ signalDate = $sig.date; direction = [int]$sig.direction }
            }
        }
        $tickerState.lastProcessedDate = $bar.date
    }
}

if ($newBarCount -eq 0) {
    Write-Host "No new completed DAY_1 bars (asOf=$($script:ResolvedAsOf))." -ForegroundColor Yellow
} elseif ($newBarCount -gt 1) {
    Write-Host "Catch-up: processed $newBarCount missed bar(s); rules unchanged." -ForegroundColor Yellow
}

$summary = Write-StateFiles $state
Write-Host "=== FORWARD PAPER (asOf=$($script:ResolvedAsOf)) ==="
$summary | Format-List
