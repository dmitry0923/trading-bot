# Research P4 (docs/27-funding-window-capture.md) -- шаг 3: прогон §4 + контроли.
#
# Предрегистрированный список конфигов (зафиксирован в docs/27 Д7 до прогона):
#   off-legacy  -- окно off, direction-aware=false  -> детерминированная стратегия,
#                  историческая (legacy) модель funding: сравнимо с прежними артефактами;
#   off-dir     -- окно off, direction-aware=true   -> изолирует эффект смены учёта funding;
#   thr0.5/1/3/5-- окно on, пороги обе стороны N    -> плато (проверка, что результат
#                  не острый пик одного порога);
#   thr2        -- ОСНОВНОЙ конфиг (правило F1, пороги 2.0/2.0, direction-aware);
#   random2     -- placebo §7.2: те же дни в рынке, сторона -- (ticker, дата), не знак.
#
# Запуск: .\scripts\research_p4_funding_window.ps1
# Оценка: /validate 730д folds=8, live-риск (maxC=1, Kelly), SL/TP пиннятся 300/600 (F1).
param(
    [string]$OutTag = ""
)
$ErrorActionPreference = "Stop"
$stamp = if ($OutTag) { $OutTag } else { Get-Date -Format "yyyyMMdd-HHmmss" }

$configs = @(
    "off-legacy;false;2.0;2.0;false;false",
    "off-dir;false;2.0;2.0;true;false",
    "thr0.5;true;0.5;0.5;true;false",
    "thr1;true;1.0;1.0;true;false",
    "thr2;true;2.0;2.0;true;false",
    "thr3;true;3.0;3.0;true;false",
    "thr5;true;5.0;5.0;true;false",
    "random2;true;2.0;2.0;true;true"
)

& (Join-Path $PSScriptRoot "research_funding_window.ps1") `
    -ConfigCsv $configs `
    -OutCsv "artifacts\p4_oos_$stamp.csv" `
    -OutJson "artifacts\p4_oos_$stamp.json"
