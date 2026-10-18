param(
    # Тикеры для загрузки. Доска определяется автоматически:
    # если тикер в -StockTickers -> TQBR (акции), иначе RFUD (фьючерсы/перпетуалы).
    [string[]]$Tickers = @('GAZP', 'LKOH', 'MGNT', 'NVTK', 'PLZL', 'ROSN', 'SBER', 'TATN', 'SBERF', 'GAZPF'),
    [string[]]$StockTickers = @('GAZP', 'LKOH', 'MGNT', 'NVTK', 'PLZL', 'ROSN', 'SBER', 'TATN'),
    [string]$From = '2024-09-01',
    [string]$Till = '2026-10-19',
    # ISS intervals: 60 = HOUR_1, 24 = DAY_1.
    [string]$IntervalCsv = '60,24',
    [string]$OutDir = 'data/candles',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$SleepMs = 200,
    [int]$PageSize = 500
)

# Загрузка старших таймфреймов (HOUR_1/DAY_1) в candles hypertable.
# Шаг 3 плана 18_10_2026 (P1): расширение юниверса U13/U15 на старшие ТФ.
# Время begin от MOEX ISS берётся как есть — так же, как для U5 (load_candles_moex.ps1)
# и для MINUTE_10 акций (load_stock_history.ps1): метки времени всех таймфреймов
# должны быть сопоставимы в одном inner-join окне портфельного движка.
# Импорт — единый psql stdin-поток (temp-таблица -> \copy -> INSERT ... ON CONFLICT DO NOTHING),
# идемпотентно: повторный запуск не дублирует и не перетирает свечи.

$ErrorActionPreference = 'Stop'
$tfName = @{ "60" = "HOUR_1"; "24" = "DAY_1" }
$Intervals = $IntervalCsv.Split(',') | Where-Object { $_ -ne "" }

if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$fromStr = "$From 00:00:00"
$tillStr = ([datetime]::ParseExact($Till, 'yyyy-MM-dd', $null)).ToString('yyyy-MM-dd') + " 23:59:59"

function Invoke-IssJson {
    param([string]$Uri)
    for ($i = 0; $i -lt 6; $i++) {
        try {
            return Invoke-RestMethod -Uri $Uri -TimeoutSec 60
        } catch {
            if ($i -eq 5) { throw }
            Start-Sleep -Seconds (2 + $i * 2)
        }
    }
}

function Get-BoardPath {
    param([string]$Ticker)
    if ($StockTickers -contains $Ticker) {
        return "engines/stock/markets/shares/boards/TQBR"
    }
    return "engines/futures/markets/forts/boards/RFUD"
}

$summary = @()
foreach ($ticker in $Tickers) {
    $board = Get-BoardPath -Ticker $ticker
    foreach ($iv in $Intervals) {
        $tf = if ($tfName.ContainsKey($iv)) { $tfName[$iv] } else { "int_$iv" }
        $base = "https://iss.moex.com/iss/$board/securities/$ticker/candles.json"
        $all = New-Object System.Collections.Generic.List[object]
        $start = 0
        while ($true) {
            $url = "$base`?interval=$iv&from=$fromStr&until=$tillStr&start=$start&iss.meta=off"
            $j = Invoke-IssJson -Uri $url
            $rows = @($j.candles.data)
            if ($rows.Count -eq 0) { break }
            foreach ($r in $rows) { $all.Add($r) }
            $start += $rows.Count
            if ($rows.Count -lt $PageSize) { break }
            if ($start -gt 300000) { Write-Warning "safety stop $ticker $tf"; break }
            if ($SleepMs -gt 0) { Start-Sleep -Milliseconds $SleepMs }
        }

        $csv = Join-Path $OutDir ("{0}_{1}_{2}_{3}.csv" -f $ticker, $tf, ($From -replace '-', ''), ($Till -replace '-', ''))
        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('ticker,timeframe,open_price,high_price,low_price,close_price,volume,time')
        $first = '?'; $last = '?'
        foreach ($r in $all) {
            $cv = $r[5]; if ($null -eq $cv) { $cv = 0 }
            $open = ([double]$r[0]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
            $high = ([double]$r[2]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
            $low = ([double]$r[3]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
            $close = ([double]$r[1]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
            $vol = [long]$cv
            $ts = $r[6]
            if ($first -eq '?') { $first = $ts }
            $last = $ts
            $lines.Add("$ticker,$tf,$open,$high,$low,$close,$vol,$ts")
        }
        [System.IO.File]::WriteAllLines($csv, $lines)
        $summary += [pscustomobject]@{ Ticker = $ticker; TF = $tf; Rows = $all.Count; First = $first; Last = $last; File = $csv }
        Write-Host ("SAVED {0} {1} -> {2} rows [{3} .. {4}]" -f $ticker, $tf, $all.Count, $first, $last)
    }
}

Write-Host 'Loading into postgres...' -ForegroundColor Yellow
foreach ($row in $summary) {
    if ($row.Rows -le 0) { continue }
    $head = "BEGIN; CREATE TEMP TABLE _stg (like candles INCLUDING DEFAULTS); \copy _stg FROM STDIN WITH (FORMAT csv, HEADER true, FORCE_NULL(volume));"
    $tail = "INSERT INTO candles SELECT * FROM _stg ON CONFLICT (ticker, timeframe, time) DO NOTHING; DROP TABLE _stg; COMMIT;"
    $csvLines = @(Get-Content -LiteralPath $row.File)
    $feed = @($head) + $csvLines + @('\.', $tail)
    $feed -join "`n" | docker exec -i $Container psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1 | Out-Null
    Write-Host ("LOADED {0} {1}" -f $row.Ticker, $row.TF) -ForegroundColor Green
}

Write-Host ""
Write-Host "=== SUMMARY ==="
$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
