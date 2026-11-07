# Контрольная точка forward paper B (docs/46): фактическая частота сделок
# против ожидаемой, свежесть данных, состояние гейтов. Ничего не меняет.
#
# Ожидаемая частота — из исторического dry-run (docs/46): 232 сделки за
# ~494 торговых дня (2024-10-24..2026-10-19) => ~0.47 сделки/торг. день
# (~10/мес). Окно малых чисел широкое: сравнение порядковое, не статистический тест.
#
# Запуск: & .\scripts\paper_momentum_status.ps1
param(
    [string]$StateDir = 'artifacts/paper_momentum',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [string]$WindowStart = '2026-11-05',
    [double]$ExpectedTradesPerDay = 0.47
)

$ErrorActionPreference = 'Continue'
$inv = [System.Globalization.CultureInfo]::InvariantCulture

# Гос. праздники РФ (как MoexHolidayCalendar): 1-8 янв, 23 фев, 8 мар, 1/9 мая, 12 июн, 4 ноя.
$holidays = @(
    '2026-01-01','2026-01-02','2026-01-05','2026-01-06','2026-01-07','2026-01-08','2026-02-23','2026-03-09',
    '2026-05-01','2026-05-11','2026-06-12','2026-11-04',
    '2027-01-01','2027-01-04','2027-01-05','2027-01-06','2027-01-07','2027-01-08','2027-02-23','2027-03-08',
    '2027-05-03','2027-05-10','2027-06-14','2027-11-04'
)
$holidaySet = [System.Collections.Generic.HashSet[string]]::new([string[]]$holidays)

function Get-TradingDays([string]$from, [string]$to) {
    $d = [datetime]::ParseExact($from, 'yyyy-MM-dd', $inv)
    $end = [datetime]::ParseExact($to, 'yyyy-MM-dd', $inv)
    $n = 0
    while ($d -le $end) {
        $wd = $d.DayOfWeek
        if ($wd -ne [DayOfWeek]::Saturday -and $wd -ne [DayOfWeek]::Sunday -and -not $holidaySet.Contains($d.ToString('yyyy-MM-dd'))) { $n++ }
        $d = $d.AddDays(1)
    }
    return $n
}

$summaryPath = Join-Path $StateDir 'summary.json'
if (-not (Test-Path -LiteralPath $summaryPath)) { throw "No $summaryPath" }
$s = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
$nowMsk = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(3))
$today = $nowMsk.ToString('yyyy-MM-dd')

# Прошедшие торговые дни окна: с WindowStart по вчера (сегодняшний бар неполный).
$yesterday = $nowMsk.AddDays(-1).ToString('yyyy-MM-dd')
$elapsedEnd = if ([string]$s.asOf -lt $yesterday) { [string]$s.asOf } else { $yesterday }
$days = Get-TradingDays $WindowStart $elapsedEnd
$expected = [math]::Round($ExpectedTradesPerDay * $days, 1)

Write-Host "=== FORWARD PAPER STATUS ($today) ===" -ForegroundColor Cyan
Write-Host ("asOf={0}  firstSignal={1}  lastExit={2}" -f $s.asOf, $s.firstSignalDate, $s.lastExitDate)
Write-Host ("closed={0}  open={1}  pending={2}  verdict={3}" -f $s.closedTrades, $s.openPositions, $s.pendingEntries, $s.verdict)
if ($s.closedTrades -gt 0) {
    Write-Host ("net={0}  PF={1}  WR={2}%  avgNet={3}  MDD={4}%  blockP={5}  CI95=[{6}; {7}]" -f
        $s.stats.net, $s.stats.pf, $s.stats.winRate, $s.stats.avgNet, $s.stats.mddPct,
        $s.blockBootstrap.pValue, $s.blockBootstrap.ci95Low, $s.blockBootstrap.ci95High)
}

Write-Host ""
Write-Host "--- Trade frequency ---" -ForegroundColor Cyan
Write-Host ("window trading days: {0}  |  expected ~{1} trades  |  actual {2}" -f $days, $expected, $s.closedTrades)
if ($days -ge 5) {
    $low = [math]::Max(1, [math]::Floor($expected / 3))
    $high = [math]::Max(3, [math]::Ceiling($expected * 3))
    $freqVerdict = if ($s.closedTrades -lt $low) { 'LAGGING (check collection pipeline)' }
        elseif ($s.closedTrades -gt $high) { 'ANOMALOUSLY MANY (check logic)' }
        else { 'ON TRACK' }
    Write-Host ("tolerance band [{0}..{1}]  ->  {2}" -f $low, $high, $freqVerdict) -ForegroundColor $(if ($freqVerdict -eq 'ON TRACK') { 'Green' } else { 'Yellow' })
} else {
    Write-Host 'window too short for frequency check (< 5 trading days)'
}
if ($s.closedTrades -lt 30) {
    Write-Host ("to verdict: {0}/30 trades (gates PF>=1.30, p<0.05, CI>0, MDD<=40%)" -f $s.closedTrades)
}

Write-Host ""
Write-Host "--- Data freshness (DB) ---" -ForegroundColor Cyan
try {
    $c = docker exec -i $Container psql -U $DbUser -d $DbName -t -A -c "select max(time)::date from candles where timeframe='DAY_1' and ticker='CNYRUBF';" 2>$null
    $f = docker exec -i $Container psql -U $DbUser -d $DbName -t -A -c "select max(clearing_date) from funding_history where ticker='CNYRUBF';" 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($c)) { throw 'db unavailable' }
    $c = $c.Trim(); $f = $f.Trim()
    $staleCandles = ([datetime]::ParseExact($yesterday, 'yyyy-MM-dd', $inv) - [datetime]::ParseExact($c, 'yyyy-MM-dd', $inv)).Days
    $staleFunding = ([datetime]::ParseExact($yesterday, 'yyyy-MM-dd', $inv) - [datetime]::ParseExact($f, 'yyyy-MM-dd', $inv)).Days
    $cOk = $staleCandles -le 4   # выходные/праздники дают легальный лаг до 4 дней
    $fOk = $staleFunding -le 10  # SWAPRATE публикуется по торг. дням; лаг ~1.5 недели уже подозрителен
    Write-Host ("candles DAY_1 max = {0}  (lag {1} d)  ->  {2}" -f $c, $staleCandles, $(if ($cOk) { 'OK' } else { 'STALLED!' })) -ForegroundColor $(if ($cOk) { 'Green' } else { 'Red' })
    Write-Host ("funding max       = {0}  (lag {1} d)  ->  {2}" -f $f, $staleFunding, $(if ($fOk) { 'OK' } else { 'STALE' })) -ForegroundColor $(if ($fOk) { 'Green' } else { 'Yellow' })
} catch {
    Write-Host 'DB unavailable - freshness not checked (docker/db running?)' -ForegroundColor Yellow
}
