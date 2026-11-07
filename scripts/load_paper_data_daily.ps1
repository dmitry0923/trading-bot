# Ежедневная донакачка данных для forward paper B (docs/46):
#   1) DAY_1-свечи фьючерсов из MOEX ISS (окно -DaysBack дней);
#   2) funding SWAPRATE из MOEX ISS history (окно -FundingDaysBack дней).
#
# Вызывается из paper_momentum_cron.ps1 ДО paper_momentum_daily.ps1, чтобы
# раннер не простаивал из-за отсутствия свежих баров, а выходы не упирались
# в fail-closed по funding_history.
#
# Свечи: вставляются только бары с датой <= последнего ЗАВЕРШЁННОГО дня
# (сегодня, если сейчас >= 23:50 МСК, иначе вчера) — частичный бар текущего
# дня в candles НЕ попадает (правило AGENTS.md «исключать time < current_date»).
# ON CONFLICT DO UPDATE: поздние корректировки ISS догружаются.
#
# Funding: value_rub_per_contract = SWAPRATE * lotSize инструмента
# (формула MoexFundingHistoryLoader; CNYRUBF/USDRUBF/EURRUBF=1000, GLDRUBF=1,
# IMOEXF=10); upsert по (ticker, clearing_date) — как
# FundingHistoryRepository.saveAll. Пагинация — по числу СЫРЫХ строк ISS
# (SWAPRATE может быть пустым на части дат).
param(
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [int]$DaysBack = 14,
    [int]$FundingDaysBack = 21,
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$SleepMs = 150
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture
$tickerList = @($Tickers.Split(',') | Where-Object { $_ -ne '' })

# lotSize инструментов (MOEX ISS LOTVOLUME) — как в InstrumentsConfig.
$script:Lots = @{
    'CNYRUBF' = 1000
    'USDRUBF' = 1000
    'EURRUBF' = 1000
    'GLDRUBF' = 1
    'IMOEXF'  = 10
}

function Invoke-IssJson([string]$Uri) {
    for ($i = 0; $i -lt 6; $i++) {
        try { return Invoke-RestMethod -Uri $Uri -TimeoutSec 90 }
        catch {
            if ($i -eq 5) { throw }
            Start-Sleep -Seconds (2 + $i * 2)
        }
    }
}

function Invoke-Psql([string]$Sql) {
    $Sql | docker exec -i $Container psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "psql failed (exit $LASTEXITCODE)" }
}

# --- последний завершённый торговый день (как Resolve-AsOf в paper_momentum_daily.ps1) ---
$nowMsk = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(3))
$afterClose = $nowMsk.TimeOfDay -ge ([TimeSpan]::FromHours(23) + [TimeSpan]::FromMinutes(50))
$lastComplete = if ($afterClose) { $nowMsk.ToString('yyyy-MM-dd') } else { $nowMsk.AddDays(-1).ToString('yyyy-MM-dd') }
Write-Host "last complete DAY_1 date: $lastComplete (now MSK $($nowMsk.ToString('yyyy-MM-dd HH:mm')))"

# === 1) DAY_1 свечи ===
$from = $nowMsk.AddDays(-$DaysBack).ToString('yyyy-MM-dd')
$fromStr = "$from 00:00:00"
$tillStr = "$($nowMsk.ToString('yyyy-MM-dd')) 23:59:59"

foreach ($t in $tickerList) {
    $url = "https://iss.moex.com/iss/engines/futures/markets/forts/boards/RFUD/securities/$t/candles.json" +
        "?interval=24&from=$([uri]::EscapeDataString($fromStr))&until=$([uri]::EscapeDataString($tillStr))&iss.meta=off"
    $j = Invoke-IssJson $url
    $rows = @($j.candles.data)
    if ($rows.Count -eq 0) { Write-Warning "$t DAY_1: no rows from ISS"; continue }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('ticker,timeframe,open_price,high_price,low_price,close_price,volume,time')
    $kept = 0
    foreach ($r in $rows) {
        $ts = [string]$r[6]
        if ($ts.Substring(0, 10) -gt $lastComplete) { continue }  # неполный бар сегодняшнего дня
        $cv = $r[5]; if ($null -eq $cv) { $cv = 0 }
        $open = ([double]$r[0]).ToString('0.######', $inv)
        $high = ([double]$r[2]).ToString('0.######', $inv)
        $low = ([double]$r[3]).ToString('0.######', $inv)
        $close = ([double]$r[1]).ToString('0.######', $inv)
        $lines.Add("$t,DAY_1,$open,$high,$low,$close,$([long]$cv),$ts")
        $kept++
    }
    if ($kept -eq 0) { Write-Host "$t DAY_1: 0 complete bars"; continue }
    $head = "BEGIN; CREATE TEMP TABLE _stg (like candles INCLUDING DEFAULTS); \copy _stg FROM STDIN WITH (FORMAT csv, HEADER true, FORCE_NULL(volume));"
    $tail = @"
INSERT INTO candles (ticker,timeframe,open_price,high_price,low_price,close_price,volume,time)
SELECT ticker,timeframe,open_price,high_price,low_price,close_price,volume,time FROM _stg
ON CONFLICT (ticker,timeframe,time) DO UPDATE SET
  open_price = EXCLUDED.open_price, high_price = EXCLUDED.high_price,
  low_price = EXCLUDED.low_price, close_price = EXCLUDED.close_price,
  volume = EXCLUDED.volume;
DROP TABLE _stg; COMMIT;
"@
    $feed = @($head) + $lines + @('\.') + $tail
    ($feed -join "`n") | docker exec -i $Container psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "psql candles failed for $t (exit $LASTEXITCODE)" }
    Write-Host "$t DAY_1: upserted $kept bar(s) [$from .. $lastComplete]" -ForegroundColor Green
    if ($SleepMs -gt 0) { Start-Sleep -Milliseconds $SleepMs }
}

# === 2) Funding (SWAPRATE) ===
$fromF = $nowMsk.AddDays(-$FundingDaysBack).ToString('yyyy-MM-dd')
$tillF = $nowMsk.ToString('yyyy-MM-dd')

foreach ($t in $tickerList) {
    $lot = [double]$script:Lots[$t]
    if ($lot -le 0) { throw "lotSize unresolved for $t" }
    $values = New-Object System.Collections.Generic.List[string]
    $start = 0
    while ($true) {
        $url = "https://iss.moex.com/iss/history/engines/futures/markets/forts/securities/$t.json" +
            "?iss.meta=off&from=$fromF&till=$tillF&start=$start"
        $j = Invoke-IssJson $url
        $block = $null
        foreach ($prop in $j.PSObject.Properties) {
            $b = $prop.Value
            if ($null -ne $b.columns -and @($b.columns) -contains 'SWAPRATE' -and $null -ne $b.data) { $block = $b; break }
        }
        if ($null -eq $block) { break }
        $cols = @($block.columns)
        $dateIdx = [array]::IndexOf($cols, 'TRADEDATE')
        $rateIdx = [array]::IndexOf($cols, 'SWAPRATE')
        if ($dateIdx -lt 0 -or $rateIdx -lt 0) { break }
        $data = @($block.data)
        if ($data.Count -eq 0) { break }
        foreach ($row in $data) {
            $d = [string]$row[$dateIdx]
            $rateRaw = $row[$rateIdx]
            if ([string]::IsNullOrWhiteSpace($d) -or $null -eq $rateRaw -or [string]::IsNullOrWhiteSpace([string]$rateRaw)) { continue }
            $raw = [double]::Parse([string]$rateRaw, $inv)
            $val = $raw * $lot
            $values.Add("('$t','$d',$($raw.ToString('0.########', $inv)),$($val.ToString('0.########', $inv)),'MOEX')")
        }
        if ($data.Count -lt 100) { break }
        $start += $data.Count
        if ($start -gt 200000) { Write-Warning "safety stop $t funding"; break }
        if ($SleepMs -gt 0) { Start-Sleep -Milliseconds $SleepMs }
    }
    if ($values.Count -eq 0) { Write-Host "$t funding: no rows"; continue }
    $sql = @"
INSERT INTO funding_history (ticker, clearing_date, raw_value, value_rub_per_contract, source)
VALUES $($values -join ',')
ON CONFLICT (ticker, clearing_date) DO UPDATE SET
  raw_value = EXCLUDED.raw_value,
  value_rub_per_contract = EXCLUDED.value_rub_per_contract,
  source = EXCLUDED.source;
"@
    Invoke-Psql $sql
    Write-Host "$t funding: upserted $($values.Count) row(s) [$fromF .. $tillF]" -ForegroundColor Green
}
