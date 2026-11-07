# Daily wrapper для forward paper B (docs/46): гарантирует postgres/redis,
# донакачивает DAY_1-свечи и funding с MOEX ISS (load_paper_data_daily.ps1),
# запускает paper_momentum_daily.ps1 и пишет лог. Ставится в планировщик
# на 23:55 МСК (после закрытия; DAY_1-бар считается завершённым).
#
# Ручной запуск: & .\scripts\paper_momentum_cron.ps1
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$logDir = Join-Path $repo 'artifacts/paper_momentum'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'cron.log'

function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $log -Value $line -Encoding utf8
    Write-Host $line
}

try {
    Log 'cron start'
    $db = docker inspect -f '{{.State.Health.Status}}' trading-bot-db 2>$null
    $redis = docker inspect -f '{{.State.Health.Status}}' trading-bot-redis 2>$null
    if ($db -ne 'healthy' -or $redis -ne 'healthy') {
        Log "starting containers (db=$db redis=$redis)"
        docker start trading-bot-db trading-bot-redis | Out-Null
    }
    for ($i = 0; $i -lt 30; $i++) {
        $db = docker inspect -f '{{.State.Health.Status}}' trading-bot-db 2>$null
        $redis = docker inspect -f '{{.State.Health.Status}}' trading-bot-redis 2>$null
        if ($db -eq 'healthy' -and $redis -eq 'healthy') { break }
        Start-Sleep -Seconds 2
    }
    if ($db -ne 'healthy' -or $redis -ne 'healthy') {
        throw "containers not healthy (db=$db redis=$redis)"
    }
    Log 'containers healthy; loading fresh candles/funding'
    try {
        & (Join-Path $PSScriptRoot 'load_paper_data_daily.ps1') *>> $log
        if ($LASTEXITCODE -ne 0) { throw "load_paper_data_daily exit=$LASTEXITCODE" }
    } catch {
        # Не блокируем раннер: он идемпотентен, следующий запуск догонит.
        # Но строка FAILED обязана быть видна в логе — молчаливый простой
        # 2026-11-03..05 случился именно из-за отсутствия этого шага.
        Log "DATA LOAD FAILED (continuing): $($_.Exception.Message)"
    }
    Log 'running paper_momentum_daily'
    & (Join-Path $PSScriptRoot 'paper_momentum_daily.ps1') *>> $log
    if ($LASTEXITCODE -ne 0) { throw "paper_momentum_daily exit=$LASTEXITCODE" }
    Log 'cron done'
} catch {
    Log "cron FAILED: $($_.Exception.Message)"
    exit 1
}
