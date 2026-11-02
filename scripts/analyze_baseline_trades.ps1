# Декомпозиция baseline-трейдов (research restart 2026-11-02).
# Вход: artifacts/baseline_trades_2026-11-02.json (includeTrades=true).
# Выход: artifacts/baseline_attribution_2026-11-02.{json,csv}.
#
# Важно: `pnl` в BacktestTradeRecord — NET (после commission и funding);
# gross = pnl + commission + funding. `capturedPctOfDayRange` сравнивает
# |exit-entry|*lotSize с дневным диапазоном (high-low) дня входа.
param(
    [string]$InJson = 'artifacts/baseline_trades_2026-11-02.json',
    [string]$OutJson = 'artifacts/baseline_attribution_2026-11-02.json',
    [string]$OutCsv = 'artifacts/baseline_attribution_trades_2026-11-02.csv',
    [string]$Ticker = 'CNYRUBF',
    [int]$LotSize = 1000,
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $InJson)) { throw "Not found: $InJson" }

$resp = Get-Content -LiteralPath $InJson -Raw | ConvertFrom-Json
$trades = @($resp.trades)
if ($trades.Count -eq 0) { throw "No trades in $InJson" }

# Дневные диапазоны для captured range.
$sql = "select time::date::text || '|' || (max(high_price)-min(low_price))::text " +
    "from candles where ticker='$Ticker' and timeframe='DAY_1' group by 1;"
$raw = ($sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -v ON_ERROR_STOP=1)
$dayRange = @{}
foreach ($line in ($raw -split "`n")) {
    $t = $line.Trim()
    if ($t -eq '') { continue }
    $p = $t -split '\|'
    if ($p.Count -eq 2) {
        $dayRange[$p[0]] = [double]::Parse($p[1], [System.Globalization.CultureInfo]::InvariantCulture)
    }
}

$rows = foreach ($t in $trades) {
    $entry = if ($t.entryTime) { [datetime]$t.entryTime } else { $null }
    $exit = if ($t.exitTime) { [datetime]$t.exitTime } else { $null }
    $pnl = [double]$t.pnl
    $commission = [double]$t.commission
    $funding = [double]$t.funding
    $gross = $pnl + $commission + $funding
    $captured = [math]::Abs(([double]$t.exitPrice - [double]$t.entryPrice)) * $LotSize
    $dayKey = if ($entry) { $entry.ToString('yyyy-MM-dd') } else { $null }
    $range = if ($dayKey -and $dayRange.ContainsKey($dayKey)) { $dayRange[$dayKey] } else { $null }
    $capturePct = if ($range -and $range -gt 0) { $captured / $range * 100.0 } else { $null }
    $hours = if ($entry -and $exit) { ($exit - $entry).TotalHours } else { $null }
    [pscustomobject]@{
        direction = $t.direction
        quantity = [int]$t.quantity
        entryPrice = [double]$t.entryPrice
        exitPrice = [double]$t.exitPrice
        entryTime = $t.entryTime
        exitTime = $t.exitTime
        closeReason = $t.closeReason
        pnl = $pnl
        commission = $commission
        funding = $funding
        gross = $gross
        holdBars = [int]$t.holdBars
        holdHours = if ($null -ne $hours) { [math]::Round($hours, 2) } else { $null }
        hourOfDay = $t.hourOfDay
        dayOfWeek = $t.dayOfWeek
        atrPercent = $t.atrPercent
        volatilityPercent = $t.volatilityPercent
        capturedRub = [math]::Round($captured, 2)
        dayRangeRub = if ($null -ne $range) { [math]::Round($range * $LotSize, 2) } else { $null }
        capturedPctOfDayRange = if ($null -ne $capturePct) { [math]::Round($capturePct, 2) } else { $null }
        win = $pnl -gt 0
    }
}

function Q([double[]]$values, [double]$q) {
    if ($values.Count -eq 0) { return $null }
    $s = $values | Sort-Object
    $idx = [math]::Min($s.Count - 1, [math]::Max(0, [int][math]::Floor(($s.Count - 1) * $q)))
    return $s[$idx]
}

function Stats($items) {
    $arr = @($items)
    $pnls = @($arr | ForEach-Object { $_.pnl })
    $gross = @($arr | ForEach-Object { $_.gross })
    $caps = @($arr | Where-Object { $null -ne $_.capturedPctOfDayRange } | ForEach-Object { $_.capturedPctOfDayRange })
    $wins = @($arr | Where-Object { $_.pnl -gt 0 })
    $losses = @($arr | Where-Object { $_.pnl -le 0 })
    $sumWin = ($wins | Measure-Object -Property pnl -Sum).Sum
    $sumLoss = [math]::Abs(($losses | Measure-Object -Property pnl -Sum).Sum)
    [pscustomobject]@{
        trades = $arr.Count
        netRub = [math]::Round(($pnls | Measure-Object -Sum).Sum, 2)
        grossRub = [math]::Round(($gross | Measure-Object -Sum).Sum, 2)
        commissionRub = [math]::Round(($arr | Measure-Object -Property commission -Sum).Sum, 2)
        fundingRub = [math]::Round(($arr | Measure-Object -Property funding -Sum).Sum, 2)
        avgNet = if ($arr.Count) { [math]::Round(($pnls | Measure-Object -Average).Average, 2) } else { $null }
        medianNet = Q $pnls 0.5
        avgGross = if ($arr.Count) { [math]::Round(($gross | Measure-Object -Average).Average, 2) } else { $null }
        winRatePct = if ($arr.Count) { [math]::Round(100.0 * $wins.Count / $arr.Count, 1) } else { $null }
        profitFactor = if ($sumLoss -gt 0) { [math]::Round($sumWin / $sumLoss, 3) } else { $null }
        avgWin = if ($wins.Count) { [math]::Round(($wins | Measure-Object -Property pnl -Average).Average, 2) } else { $null }
        avgLoss = if ($losses.Count) { [math]::Round(($losses | Measure-Object -Property pnl -Average).Average, 2) } else { $null }
        avgCapturedPctOfDayRange = if ($caps.Count) { [math]::Round(($caps | Measure-Object -Average).Average, 2) } else { $null }
        medianCapturedPctOfDayRange = Q $caps 0.5
        avgHoldHours = if ($arr.Count) { [math]::Round((($arr | Where-Object { $null -ne $_.holdHours } | ForEach-Object { $_.holdHours }) | Measure-Object -Average).Average, 2) } else { $null }
    }
}

function Group($name, $key) {
    $groups = $rows | Group-Object -Property $key | Sort-Object Name
    $out = foreach ($g in $groups) {
        $s = Stats $g.Group
        [pscustomobject]@{
            dimension = $name
            value = [string]$g.Name
            trades = $s.trades
            netRub = $s.netRub
            grossRub = $s.grossRub
            avgNet = $s.avgNet
            winRatePct = $s.winRatePct
            profitFactor = $s.profitFactor
            avgCapturedPctOfDayRange = $s.avgCapturedPctOfDayRange
            avgHoldHours = $s.avgHoldHours
        }
    }
    return $out
}

$overall = Stats $rows
$byDirection = Group 'direction' 'direction'
$byCloseReason = Group 'closeReason' 'closeReason'
$byHour = Group 'hourOfDay' 'hourOfDay'
$byDayOfWeek = Group 'dayOfWeek' 'dayOfWeek'
$byHoldBucket = $rows | Group-Object -Property {
    if ($_.holdBars -le 6) { '1-6 bars' }
    elseif ($_.holdBars -le 30) { '7-30 bars' }
    elseif ($_.holdBars -le 100) { '31-100 bars' }
    else { '>100 bars' }
} | Sort-Object Name | ForEach-Object {
    $s = Stats $_.Group
    [pscustomobject]@{
        dimension = 'holdBucket'; value = $_.Name; trades = $s.trades
        netRub = $s.netRub; grossRub = $s.grossRub; avgNet = $s.avgNet
        winRatePct = $s.winRatePct; profitFactor = $s.profitFactor
        avgCapturedPctOfDayRange = $s.avgCapturedPctOfDayRange; avgHoldHours = $s.avgHoldHours
    }
}

$artifact = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    source = $InJson
    engineVersion = $resp.engineVersion
    ticker = $resp.ticker
    totalReturnPct = $resp.totalReturn
    responseProfitFactor = $resp.profitFactor
    responseTrades = $resp.totalTrades
    overall = $overall
    byDirection = $byDirection
    byCloseReason = $byCloseReason
    byHour = $byHour
    byDayOfWeek = $byDayOfWeek
    byHoldBucket = $byHoldBucket
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutJson) | Out-Null
$artifact | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutJson -Encoding utf8
$rows | Export-Csv -LiteralPath $OutCsv -NoTypeInformation -Encoding utf8

Write-Host "=== OVERALL ==="
$overall | Format-List
Write-Host "=== BY DIRECTION ==="
$byDirection | Format-Table -AutoSize
Write-Host "=== BY CLOSE REASON ==="
$byCloseReason | Format-Table -AutoSize
Write-Host "=== BY HOUR ==="
$byHour | Format-Table -AutoSize
Write-Host "=== BY HOLD ==="
$byHoldBucket | Format-Table -AutoSize
