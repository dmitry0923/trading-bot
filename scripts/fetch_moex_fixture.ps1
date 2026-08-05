# Загрузка реальных 10-минутных свечей MOEX ISS в CSV-фикстуру для тестов.
#
# Фикстура используется тестом RealDataBacktestFixtureTest (детерминированный
# бэктест на реальных данных без обращения к сети во время CI).
#
# Пример:
#   ./scripts/fetch_moex_fixture.ps1 -Ticker SBER -From "2026-04-06" -Until "2026-07-03"
param(
    [string]$Ticker = "SBER",
    [string]$From = "2026-04-06",
    [string]$Until = "2026-07-03",
    [string]$OutFile = ""
)

$ErrorActionPreference = "Stop"

if (-not $OutFile) {
    $OutFile = "src/test/resources/fixtures/moex_$($Ticker.ToLower())_minute10.csv"
}
$OutFile = (Resolve-Path -LiteralPath (Split-Path $OutFile -Parent)).Path + "\" + (Split-Path $OutFile -Leaf)

$base = "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/$Ticker/candles.json"
$fromStr = "$From 09:00:00"
$untilStr = "$Until 19:00:00"

$all = New-Object System.Collections.Generic.List[object]
$start = 0
$columns = @()

while ($true) {
    $url = "$base`?interval=10&from=$([uri]::EscapeDataString($fromStr))&until=$([uri]::EscapeDataString($untilStr))&start=$start"
    $json = Invoke-RestMethod -Uri $url -TimeoutSec 30
    if ($columns.Count -eq 0) { $columns = $json.candles.columns }
    $rows = $json.candles.data
    if (-not $rows -or $rows.Count -eq 0) { break }
    foreach ($r in $rows) { $all.Add($r) }
    $start += $rows.Count
    if ($rows.Count -lt 500) { break }
    Write-Host "Fetched $($all.Count) candles..."
}

# Позиции нужных колонок в ответе ISS (порядок может меняться)
$idx = @{}
for ($i = 0; $i -lt $columns.Count; $i++) { $idx[$columns[$i]] = $i }
$need = @("begin", "open", "high", "low", "close", "volume")
if (($need | Where-Object { -not $idx.ContainsKey($_) }).Count -gt 0) {
    throw "MOEX columns mismatch: got [$($columns -join ', ')]"
}

# Дедупликация по begin
$seen = @{}
$unique = New-Object System.Collections.Generic.List[object]
foreach ($r in ($all | Sort-Object { $_[$idx["begin"]] })) {
    $key = [string]$r[$idx["begin"]]
    if (-not $seen.ContainsKey($key)) {
        $seen[$key] = $true
        $unique.Add($r)
    }
}

$inv = [System.Globalization.CultureInfo]::InvariantCulture
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("begin,open,high,low,close,volume")
foreach ($r in $unique) {
    $fields = foreach ($name in $need) {
        $v = $r[$idx[$name]]
        if ($v -is [string]) { $v } else { [System.Convert]::ToString($v, $inv) }
    }
    $lines.Add(($fields -join ","))
}
[System.IO.File]::WriteAllLines($OutFile, $lines)
Write-Host "Saved $($unique.Count) candles -> $OutFile"
