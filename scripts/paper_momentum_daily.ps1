# Forward paper momentum+low-vol на 5 фьючерсах (docs/46, шаг B).
# Никаких live-ордеров: только состояние и журнал гипотетических сделок.
#
# Ежедневный запуск после закрытия: & .\scripts\paper_momentum_daily.ps1
# Первичная инициализация:        & .\scripts\paper_momentum_daily.ps1 -Init
param(
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [int]$MomentumBars = 20,
    [int]$AtrBars = 20,
    [int]$VolLookbackBars = 252,
    [double]$MaxVolPercentile = 0.5,
    [int]$HoldBars = 5,
    [int]$LotSizeDefault = 1000,
    [double]$CommissionPerSide = 1.5,
    [double]$SlippageBps = 1.0,
    [string]$StateDir = 'artifacts/paper_momentum',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [switch]$Init
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
New-Item -ItemType Directory -Force -Path $StateDir | Out-Null
$statePath = Join-Path $StateDir 'state.json'
$tradesPath = Join-Path $StateDir 'trades.csv'
$summaryPath = Join-Path $StateDir 'summary.json'

$specs = @{
    'CNYRUBF' = @{ lot = 1000 }
    'USDRUBF' = @{ lot = 1000 }
    'EURRUBF' = @{ lot = 1000 }
    'GLDRUBF' = @{ lot = 1 }
    'IMOEXF'  = @{ lot = 10 }
}

function Load-Bars([string]$ticker) {
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
            })
    }
    return $bars
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

function Get-FundingCost([string]$entryDate, [string]$exitDate, [int]$dir, [hashtable]$fundingMap) {
    $cost = 0.0
    $d = [datetime]::ParseExact($entryDate, 'yyyy-MM-dd', $inv)
    $end = [datetime]::ParseExact($exitDate, 'yyyy-MM-dd', $inv)
    $entryDt = $d.AddHours(10)
    $exitDt = $end.AddHours(23).AddMinutes(40)
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

function Get-Signal($bars) {
    $minIndex = $VolLookbackBars + $AtrBars - 2
    if ($bars.Count -le $minIndex) { return $null }
    $i = $bars.Count - 1
    $prev = $bars[$i - $MomentumBars].close
    if ($prev -le 0) { return $null }
    $mom = $bars[$i].close / $prev - 1.0
    if ($mom -eq 0) { return $null }
    $start = $i - $minIndex
    $tr = [double[]]::new($minIndex + 1)
    for ($j = $start; $j -le $i; $j++) {
        $pc = if ($j -gt 0) { $bars[$j - 1].close } else { $bars[$j].close }
        $b = $bars[$j]
        $tr[$j - $start] = [math]::Max($b.high - $b.low, [math]::Max([math]::Abs($b.high - $pc), [math]::Abs($b.low - $pc)))
    }
    $atrp = [double[]]::new($VolLookbackBars)
    for ($k = 0; $k -lt $VolLookbackBars; $k++) {
        $j = $i - $VolLookbackBars + 1 + $k
        $sum = 0.0
        for ($t = $j - $AtrBars + 1; $t -le $j; $t++) { $sum += $tr[$t - $start] }
        $atrp[$k] = if ($bars[$j].close -gt 0) { ($sum / $AtrBars) / $bars[$j].close } else { 0.0 }
    }
    $cur = $atrp[$VolLookbackBars - 1]
    $le = 0
    foreach ($x in $atrp) { if ($x -le $cur) { $le++ } }
    $volPct = $le / [double]$VolLookbackBars
    if ($volPct -gt $MaxVolPercentile) { return $null }
    return [pscustomobject]@{ date = $bars[$i].date; direction = $(if ($mom -gt 0) { 1 } else { -1 }); volPct = $volPct }
}

$state = @{ lastProcessed = @{}; positions = @{}; pending = @{}; trades = @() }
if (Test-Path -LiteralPath $statePath) {
    $raw = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable
    if ($null -ne $raw) { $state = $raw }
} elseif (-not $Init) {
    Write-Host "No state; initializing." -ForegroundColor Yellow
}

$tickerList = $Tickers.Split(',') | Where-Object { $_ -ne '' }
foreach ($ticker in $tickerList) {
    $bars = Load-Bars -ticker $ticker
    if ($bars.Count -le ($VolLookbackBars + $AtrBars - 1)) { Write-Host "$ticker`: insufficient bars"; continue }
    $fundingMap = Get-FundingMap -ticker $ticker
    $lot = $specs[$ticker].lot
    $latest = $bars[$bars.Count - 1]

    # 1) Fill pending entries on the first bar after the signal date.
    if ($state.pending.ContainsKey($ticker)) {
        $p = $state.pending[$ticker]
        $entryIdx = -1
        for ($idx = 0; $idx -lt $bars.Count; $idx++) {
            if ($bars[$idx].date -gt $p.signalDate) { $entryIdx = $idx; break }
        }
        if ($entryIdx -ge 0) {
            $exitIdx = $entryIdx + $HoldBars - 1
            if ($exitIdx -lt $bars.Count) {
                $state.positions[$ticker] = @{
                    signalDate = $p.signalDate
                    entryDate = $bars[$entryIdx].date
                    exitDate = $bars[$exitIdx].date
                    direction = [int]$p.direction
                    entryPrice = $bars[$entryIdx].open
                }
                $state.pending.Remove($ticker)
            }
        }
    }

    # 2) Close positions whose exit bar is available.
    if ($state.positions.ContainsKey($ticker)) {
        $pos = $state.positions[$ticker]
        $exitBar = $bars | Where-Object { $_.date -eq $pos.exitDate } | Select-Object -First 1
        if ($null -ne $exitBar) {
            $dir = [int]$pos.direction
            $entryPrice = [double]$pos.entryPrice
            $exitPrice = [double]$exitBar.close
            $gross = ($exitPrice - $entryPrice) * $lot * $dir
            $commission = $CommissionPerSide * 2.0
            $slippage = ($entryPrice * $SlippageBps / 10000.0 * $lot) + ($exitPrice * $SlippageBps / 10000.0 * $lot)
            $fc = Get-FundingCost -entryDate $pos.entryDate -exitDate $pos.exitDate -dir $dir -fundingMap $fundingMap
            $net = $gross - $commission - $slippage - $fc
            $state.trades += [pscustomobject]@{
                ticker = $ticker
                signalDate = $pos.signalDate
                direction = $(if ($dir -gt 0) { 'LONG' } else { 'SHORT' })
                entryDate = $pos.entryDate
                exitDate = $pos.exitDate
                entryPrice = [math]::Round($entryPrice, 4)
                exitPrice = [math]::Round($exitPrice, 4)
                gross = [math]::Round($gross, 2)
                commission = [math]::Round($commission, 2)
                slippage = [math]::Round($slippage, 2)
                funding = [math]::Round($fc, 2)
                net = [math]::Round($net, 2)
            }
            $state.positions.Remove($ticker)
        }
    }

    # 3) New signal only on a new completed bar and when flat.
    $lastProcessed = if ($state.lastProcessed.ContainsKey($ticker)) { $state.lastProcessed[$ticker] } else { '' }
    if (-not $state.positions.ContainsKey($ticker) -and -not $state.pending.ContainsKey($ticker) -and $latest.date -gt $lastProcessed) {
        $sig = Get-Signal -bars $bars
        if ($null -ne $sig) {
            $state.pending[$ticker] = @{ signalDate = $sig.date; direction = $sig.direction }
        }
        $state.lastProcessed[$ticker] = $latest.date
    }
}

$state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $statePath -Encoding utf8
if ($state.trades.Count -gt 0) {
    $state.trades | Export-Csv -LiteralPath $tradesPath -NoTypeInformation -Encoding utf8
} else {
    'ticker,signalDate,direction,entryDate,exitDate,entryPrice,exitPrice,gross,commission,slippage,funding,net' |
        Set-Content -LiteralPath $tradesPath -Encoding utf8
}

$trades = @($state.trades)
$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    closedTrades = $trades.Count
    openPositions = $state.positions.Count
    pendingEntries = $state.pending.Count
    net = if ($trades.Count -gt 0) { [math]::Round((($trades | ForEach-Object { [double]$_.net }) | Measure-Object -Sum).Sum, 2) } else { 0 }
    note = 'Forward paper only; no live orders. Verdict after >= 30 closed trades (docs/46).'
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding utf8
$summary | Format-List
