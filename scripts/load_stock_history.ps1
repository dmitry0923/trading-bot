param(
    [string[]]$Tickers = @('GAZP', 'NVTK', 'PLZL', 'SBER'),
    [int]$Days = 730,
    [string]$Container = 'trading-bot-db',
    [string]$DbUser = 'trader',
    [string]$DbName = 'trading_bot'
)

# Загрузка 10-минутных свечей акций (TQBR) с MOEX ISS в candles hypertable.
# Время begin от MOEX ISS приходит уже в UTC — совпадает с меткой времени в БД.

$ErrorActionPreference = 'Stop'
$to = Get-Date
$from = $to.AddDays(-$Days)
$fromStr = $from.ToString('yyyy-MM-dd')
$toStr = $to.ToString('yyyy-MM-dd')
$tmp = Join-Path $env:TEMP 'moex_candles'
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function Invoke-IssJson {
    param([string]$Uri)
    for ($i = 0; $i -lt 6; $i++) {
        try {
            return Invoke-RestMethod -Uri $Uri -TimeoutSec 45
        } catch {
            if ($i -eq 5) { throw }
            Start-Sleep -Seconds (2 + $i * 2)
        }
    }
}

foreach ($ticker in $Tickers) {
    Write-Host "[$ticker] fetching from=$fromStr to=$toStr" -ForegroundColor Yellow
    $all = [System.Collections.Generic.List[object]]::new()
    $start = 0
    while ($true) {
        $url = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/$ticker/candles.json?" +
            "interval=10&from=$fromStr&until=$toStr&start=$start"
        $j = Invoke-IssJson -Uri $url
        $rows = $j.candles.data
        if ($null -eq $rows -or $rows.Count -eq 0) { break }
        foreach ($r in $rows) {
            $all.Add($r)
        }
        $start += $rows.Count
        if ($rows.Count -lt 500) { break }
        if ($start % 10000 -eq 0) { Write-Host "[$ticker] ...$start rows" }
        Start-Sleep -Milliseconds 200
    }
    Write-Host "[$ticker] fetched $($all.Count) rows"

    $csv = Join-Path $tmp "$ticker.csv"
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('ticker,timeframe,open_price,high_price,low_price,close_price,volume,time')
    foreach ($r in $all) {
        $cv = $r[5]; if ($null -eq $cv) { $cv = 0 }
        $open = ([double]$r[0]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
        $high = ([double]$r[2]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
        $low = ([double]$r[3]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
        $close = ([double]$r[1]).ToString('0.######', [System.Globalization.CultureInfo]::InvariantCulture)
        $vol = [long]$cv
        $lines.Add("$ticker,MINUTE_10,$open,$high,$low,$close,$vol,$($r[6])")
    }
    [System.IO.File]::WriteAllLines($csv, $lines)
    Write-Host "[$ticker] csv written: $csv" -ForegroundColor Green
}

Write-Host 'Loading into postgres...' -ForegroundColor Yellow
foreach ($ticker in $Tickers) {
    $csv = Join-Path $tmp "$ticker.csv"
    # Единый stdin-поток psql: temp-таблица -> \copy из CSV -> идемпотентная вставка.
    $head = "BEGIN; CREATE TEMP TABLE _stg (like candles INCLUDING DEFAULTS); \copy _stg FROM STDIN WITH (FORMAT csv, HEADER true, FORCE_NULL(volume));"
    $tail = "INSERT INTO candles SELECT * FROM _stg ON CONFLICT (ticker, timeframe, time) DO NOTHING; DROP TABLE _stg; COMMIT;"
    $csvLines = @(Get-Content -LiteralPath $csv)
    $feed = @($head) + $csvLines + @('\.', $tail)
    $feed -join "`n" | docker exec -i $Container psql -U $DbUser -d $DbName -v ON_ERROR_STOP=1
    Write-Host "[$ticker] loaded OK" -ForegroundColor Green
}
Write-Host 'Done.' -ForegroundColor Green