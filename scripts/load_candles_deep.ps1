# Донакачка DAY_1/HOUR_1 на максимальную глубину MOEX ISS (18_10_2026, пункт 6 —
# честный протокол 12−1 требует >= 3 лет дневной истории).
#
# Границы НЕ одинаковы (измерено 2026-10-20 живыми запросами к ISS):
#   * акции TQBR           — с 2018-01-03 (~8.8 года)
#   * CNYRUBF/USDRUBF/EURRUBF — с 2022-04-26 (~4.5 года, перпетуал запущен тогда)
#   * GLDRUBF              — с 2023-07-11
#   * IMOEXF               — с 2023-11-14
#   * SBERF/GAZPF          — с 2024-10-01 (продлить нельзя)
# Скрипт задаёт старт ПО ТИКЕРУ, идемпотентно вливает в candles
# (temp-таблица -> \copy -> INSERT ... ON CONFLICT DO NOTHING).
#
# Запуск: & .\scripts\load_candles_deep.ps1
#         & .\scripts\load_candles_deep.ps1 -IntervalCsv 24     # только дневки
param(
    [string]$Till = '2026-10-19',
    # ISS intervals: 60 = HOUR_1, 24 = DAY_1.
    [string]$IntervalCsv = '60,24',
    [string]$OutDir = 'data/candles',
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot',
    [int]$SleepMs = 150,
    [int]$PageSize = 500
)

$ErrorActionPreference = 'Stop'
$tfName = @{ "60" = "HOUR_1"; "24" = "DAY_1" }
$Intervals = $IntervalCsv.Split(',') | Where-Object { $_ -ne "" }

if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$tillStr = ([datetime]::ParseExact($Till, 'yyyy-MM-dd', $null)).ToString('yyyy-MM-dd') + " 23:59:59"

# ticker -> (board, from)
$plan = @(
    # --- акции TQBR: с 2018-01-01 (реальный первый бар 2018-01-03) ---
    @{ T = 'GAZP'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'LKOH'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'MGNT'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'NVTK'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'PLZL'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'ROSN'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'SBER'; B = 'stock'; F = '2018-01-01' }
    @{ T = 'TATN'; B = 'stock'; F = '2018-01-01' }
    # --- перпетуалы RFUD: со своего реального старта ---
    @{ T = 'CNYRUBF'; B = 'forts'; F = '2022-04-26' }
    @{ T = 'USDRUBF'; B = 'forts'; F = '2022-04-26' }
    @{ T = 'EURRUBF'; B = 'forts'; F = '2022-04-26' }
    @{ T = 'GLDRUBF'; B = 'forts'; F = '2023-07-11' }
    @{ T = 'IMOEXF'; B = 'forts'; F = '2023-11-14' }
    @{ T = 'SBERF'; B = 'forts'; F = '2024-10-01' }
    @{ T = 'GAZPF'; B = 'forts'; F = '2024-10-01' }
)

function Invoke-IssJson {
    param([string]$Uri)
    for ($i = 0; $i -lt 6; $i++) {
        try {
            return Invoke-RestMethod -Uri $Uri -TimeoutSec 90
        } catch {
            if ($i -eq 5) { throw }
            Start-Sleep -Seconds (2 + $i * 2)
        }
    }
}

$summary = @()
foreach ($item in $plan) {
    $ticker = $item.T
    $board = if ($item.B -eq 'stock') {
        "engines/stock/markets/shares/boards/TQBR"
    } else {
        "engines/futures/markets/forts/boards/RFUD"
    }
    $fromStr = "$($item.F) 00:00:00"

    foreach ($iv in $Intervals) {
        $tf = if ($tfName.ContainsKey($iv)) { $tfName[$iv] } else { "int_$iv" }
        $base = "https://iss.moex.com/iss/$board/securities/$ticker/candles.json"
        $all = New-Object System.Collections.Generic.List[object]
        $start = 0
        $pages = 0
        while ($true) {
            $url = "$base`?interval=$iv&from=$fromStr&until=$tillStr&start=$start&iss.meta=off"
            $j = Invoke-IssJson -Uri $url
            $rows = @($j.candles.data)
            if ($rows.Count -eq 0) { break }
            foreach ($r in $rows) { $all.Add($r) }
            $start += $rows.Count
            $pages++
            if ($rows.Count -lt $PageSize) { break }
            if ($start -gt 300000) { Write-Warning "safety stop $ticker $tf"; break }
            if ($SleepMs -gt 0) { Start-Sleep -Milliseconds $SleepMs }
        }

        $csv = Join-Path $OutDir ("{0}_{1}_{2}_{3}.csv" -f $ticker, $tf, ($item.F -replace '-', ''), ($Till -replace '-', ''))
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
        $summary += [pscustomobject]@{ Ticker = $ticker; TF = $tf; Rows = $all.Count; Pages = $pages; First = $first; Last = $last; File = $csv }
        Write-Host ("FETCHED {0} {1} <- {2} rows / {3} pages [{4} .. {5}]" -f $ticker, $tf, $all.Count, $pages, $first, $last)
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
$summary | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
