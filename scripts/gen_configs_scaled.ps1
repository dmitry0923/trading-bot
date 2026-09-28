# Генератор конфигов S2/S3/S4 с инструмент-специфичными SL/TP.
#
# Зачем: штатная сетка SL/TP (25-600 пт) посчитана под CNYRUBF (~90 руб).
# На GLDRUBF (~5500) 600 пт = 1.1% цены, 25 пт = 0.05% (шум) - отсюда
# GLDRUBF baseline MDD 572% и 91 минута на прогон (in-sample перебирает всю
# сетку). На IMOEXF (~2800) 600 пт = 21%.
#
# Поэтому SL/TP фиксируются ЗАРАНЕЕ (без in-sample-выбора - это исключает
# подгонку) и подбираются по масштабу цены:
#   GLDRUBF: SL 150/300/600, TP 300/600/1200  (2.7-11% SL)
#   IMOEXF:  SL 100/200/400, TP 200/400/800   (3.6-14% SL)
$ErrorActionPreference = "Stop"
$src = Join-Path $PSScriptRoot "configs_four_cores_nos1.csv"
$dst = Join-Path $PSScriptRoot "configs_four_cores_scaled.csv"

$out = @(
    "# S2/S3/S4 с инструмент-специфичными SL/TP (2026-09-28).",
    "# GLDRUBF SL 300 / TP 600 (5.5% SL), IMOEXF SL 200 / TP 400, CNYRUBF SL 40 / TP 80.",
    "# SL/TP заданы заранее, без in-sample-выбора: исключает подгонку и ускоряет",
    "# прогон ~x6 (in-sample грид 25-600 пт на GLDRUBF давал MDD 572% и 91 мин).",
    "# Размер сетки исходный пользователя: S2 9 + S3 3 + S4 3 = 15.",
    ""
)

foreach ($line in (Get-Content -LiteralPath $src -Encoding UTF8)) {
    if (-not $line -or $line.StartsWith("#")) { continue }
    $parts = $line.Split(";", 2)
    $name = $parts[0]
    $query = $parts[1]
    if ($query -match "GLDRUBF") {
        # Средняя пара: SL 300 (5.5% цены), TP 600. Размер сетки НЕ раздуваем -
        # исходная сетка пользователя была 9 конфигов (hold x atr).
        $out += "$name-sl300-tp600;$query&wfaSlPoints=300&wfaTpPoints=600"
    } elseif ($query -match "IMOEXF") {
        $out += "$name-sl200-tp400;$query&wfaSlPoints=200&wfaTpPoints=400"
    } elseif ($query -match "CNYRUBF") {
        # CNYRUBF: штатный масштаб корректен, но фиксируем - так прогон в 6 раз
        # быстрее (без перебора in-sample сетки) и нет подгонки.
        $out += "$name-sl40-tp80;$query&wfaSlPoints=40&wfaTpPoints=80"
    } else {
        $out += $line
    }
}

[System.IO.File]::WriteAllLines($dst, $out, (New-Object System.Text.UTF8Encoding($false)))
$cfg = $out | Where-Object { $_ -and -not $_.StartsWith("#") }
Write-Host "конфигов: $($cfg.Count) -> $dst"
