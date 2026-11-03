# Проверка Prometheus/Alertmanager конфигурации (docs/54, P1-4).
# Запуск: & .\scripts\check_alerting.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host 'promtool check rules'
docker run --rm --entrypoint promtool -v "${root}:/work:ro" -w /work prom/prometheus:v2.48.1 check rules prometheus-alerting-rules.yml
if ($LASTEXITCODE -ne 0) { throw "promtool check rules failed" }

Write-Host 'promtool test rules'
docker run --rm --entrypoint promtool -v "${root}:/work:ro" -w /work prom/prometheus:v2.48.1 test rules prometheus-alerting-rules-test.yml
if ($LASTEXITCODE -ne 0) { throw "promtool test rules failed" }

Write-Host 'amtool check-config'
docker run --rm --entrypoint amtool -v "${root}:/work:ro" -w /work prom/alertmanager:v0.27.0 check-config alertmanager.yml
if ($LASTEXITCODE -ne 0) { throw "amtool check-config failed" }

Write-Host 'Alerting config OK'
