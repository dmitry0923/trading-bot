# Capacity audit (research restart 2026-11-02): сколько физически можно заработать
# на одном контракте CNYRUBF при идеальном внутридневном тайминге.
#
# Верхняя граница: сумма дневных диапазонов (high-low) за окно × lotSize. Это
# максимальная прибыль, если каждый день покупать на low и продавать на high
# (одна сделка в день, 1 контракт). Любая реальная стратегия берёт меньше.
#
# Запуск: & .\scripts\research_capacity_audit.ps1
param(
    [string]$Ticker = 'CNYRUBF',
    [string]$Timeframe = 'DAY_1',
    [int]$LotSize = 1000,
    [int]$Days = 730,
    [double[]]$Deposits = @(50000, 100000),
    [int[]]$MaxContracts = @(1, 2, 3, 4, 6, 8),
    [int[]]$TradeCounts = @(82, 200, 500, 1000),
    [double]$TargetAnnualPct = 100.0,
    [string]$Out = 'artifacts/capacity_audit_2026-11-02.json',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot'
)

$ErrorActionPreference = 'Stop'

$sql = @"
with d as (
    select time::date dt, max(high_price) hi, min(low_price) lo
    from candles
    where ticker = '$Ticker'
      and timeframe = '$Timeframe'
      and time >= now() - interval '$Days days'
    group by 1
), agg as (
    select count(*)::int days,
           sum(hi - lo)::numeric sum_range,
           avg(hi - lo)::numeric avg_range,
           min(lo)::numeric min_lo,
           max(hi)::numeric max_hi
    from d
)
select days, sum_range, avg_range, min_lo, max_hi from agg;
"@

$raw = ($sql | docker exec -i $Container psql -U $DbUser -d $DbName -t -A -F '|' -v ON_ERROR_STOP=1)
$parts = ($raw.Trim() -split '\|')
if ($parts.Count -lt 5) { throw "Unexpected psql output: $raw" }

$tradingDays = [int]$parts[0]
$sumRange = [double]::Parse($parts[1], [System.Globalization.CultureInfo]::InvariantCulture)
$avgRange = [double]::Parse($parts[2], [System.Globalization.CultureInfo]::InvariantCulture)
$minLo = [double]::Parse($parts[3], [System.Globalization.CultureInfo]::InvariantCulture)
$maxHi = [double]::Parse($parts[4], [System.Globalization.CultureInfo]::InvariantCulture)

$maxProfitPerContract = $sumRange * $LotSize
# Внимание: PowerShell не различает $Days и $days — окно и число торговых дней
# обязаны быть разными именами, иначе годовое масштабирование завышается.
$windowDays = $Days
$years = $windowDays / 365.25
$annualPerContract = $maxProfitPerContract / $years

$depositRows = foreach ($deposit in $Deposits) {
    $periodPct = $maxProfitPerContract / $deposit * 100.0
    $annualPct = $annualPerContract / $deposit * 100.0
    $required = foreach ($n in $MaxContracts) {
        [pscustomobject]@{
            maxContracts = $n
            maxAnnualPctAtPerfectTiming = [math]::Round($annualPct * $n, 2)
            requiredCapturePctFor100 = [math]::Round(100.0 / ($annualPct * $n) * 100.0, 2)
        }
    }
    # Сколько рублей чистыми должна приносить ОДНА сделка, чтобы за окно выйти
    # на TargetAnnualPct при данном maxC. Верхняя оценка: каждая сделка
    # использует полный maxC; при меньшем размере требование выше.
    $targetTotalRub = $deposit * ($TargetAnnualPct / 100.0) * $years
    $perTradeByMaxC = foreach ($n in $MaxContracts) {
        [pscustomobject]@{
            maxContracts = $n
            byTradeCount = @($TradeCounts | ForEach-Object {
                [pscustomobject]@{
                    tradesInWindow = $_
                    requiredNetRubPerTrade = [math]::Round($targetTotalRub / ($_ * $n), 2)
                }
            })
        }
    }
    [pscustomobject]@{
        depositRub = $deposit
        ceilingPeriodPct = [math]::Round($periodPct, 2)
        ceilingAnnualPctAt1Contract = [math]::Round($annualPct, 2)
        scenarios = $required
        requiredPerTradeByMaxC = $perTradeByMaxC
    }
}

$result = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    ticker = $Ticker
    timeframe = $Timeframe
    windowDays = $windowDays
    tradingDays = $tradingDays
    lotSize = $LotSize
    avgDailyRangeRubPerUnit = [math]::Round($avgRange, 4)
    sumDailyRangeRubPerUnit = [math]::Round($sumRange, 4)
    maxProfitPerContractRub = [math]::Round($maxProfitPerContract, 2)
    annualMaxProfitPerContractRub = [math]::Round($annualPerContract, 2)
    targetAnnualPct = $TargetAnnualPct
    tradeCounts = $TradeCounts
    priceRange = [pscustomobject]@{ minLow = $minLo; maxHigh = $maxHi }
    deposits = $depositRows
    note = 'Ceiling = perfect daily buy-low/sell-high timing, one round trip per day, one contract. No strategy can exceed it; real capture is a fraction.'
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Out) | Out-Null
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Out -Encoding utf8
$result | ConvertTo-Json -Depth 8
