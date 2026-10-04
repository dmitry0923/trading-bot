param(
  [string]$Portfolio = "D52465",
  [string]$Tickers = "CNYRUBF",
  [switch]$NoMonitor,
  [int]$IntervalSec = 600
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path $env:TEMP "opencode"
$jar = Join-Path $root "build\libs\trading-bot-2.0.0.jar"
$out = Join-Path $tmp "bot.out.log"
$err = Join-Path $tmp "bot.err.log"
$pidFile = Join-Path $tmp "bot_pid.txt"
$monPidFile = Join-Path $tmp "monitor_pid.txt"
$monScript = Join-Path $root "scripts\monitor_microstructure_bg.ps1"

New-Item -ItemType Directory -Path $tmp -Force | Out-Null

# --- 1. Токен: refresh из .env; access бот получит сам (AlorTokenProvider) ---
$envFile = Join-Path $root ".env"
$envMap = @{}
foreach ($line in (Get-Content $envFile)) {
  if ($line -match '^\s*#' -or -not $line.Contains('=')) { continue }
  $k, $v = $line -split '=', 2
  $envMap[$k.Trim()] = $v.Trim().Trim('"')
}
foreach ($k in $envMap.Keys) { Set-Item -Path "Env:$k" -Value $envMap[$k] }

if (-not $envMap.ContainsKey('ALOR_REFRESH_TOKEN') -or -not $envMap['ALOR_REFRESH_TOKEN']) {
  throw "ALOR_REFRESH_TOKEN отсутствует в .env — без него WS не авторизуется"
}
$env:ALOR_PORTFOLIO = $Portfolio
$env:TRADING_MODE = "SIMULATION"
$env:MICROSTRUCTURE_ENABLED = "true"
$env:MICROSTRUCTURE_REST_POLLING_ENABLED = "true"
$env:MICROSTRUCTURE_TICKERS = $Tickers

# --- 2. Остановка прошлых экземпляров ---
foreach ($f in @($pidFile, $monPidFile)) {
  if (Test-Path $f) {
    $old = [int](Get-Content $f -Raw).Trim()
    if ($old -gt 0) {
      Stop-Process -Id $old -Force -ErrorAction SilentlyContinue
      Write-Host "stopped previous pid=$old ($([System.IO.Path]::GetFileName($f)))"
    }
  }
}
Start-Sleep -Seconds 3

# --- 3. Старт бота в фоне (логи: bot.out.log / bot.err.log; монитор читает bot.out.log) ---
foreach ($f in @($out, $err)) { if (Test-Path $f) { Move-Item $f "$f.prev" -Force } }
$p = Start-Process -FilePath "java" `
  -ArgumentList '-jar', "`"$jar`"", '--spring.mvc.async.request-timeout=21600000' `
  -WorkingDirectory $root -RedirectStandardOutput $out -RedirectStandardError $err `
  -PassThru -WindowStyle Hidden
$p.Id | Set-Content $pidFile
Write-Host "bot started pid=$($p.Id) portfolio=$Portfolio tickers=$Tickers refresh=set"

# --- 4. Монитор в фоне ---
if (-not $NoMonitor) {
  $m = Start-Process -FilePath "pwsh" `
    -ArgumentList '-NoProfile', '-File', "`"$monScript`"", '-BotPid', "$($p.Id)", '-IntervalSec', "$IntervalSec", '-BotLog', "`"$out`"" `
    -WindowStyle Hidden -PassThru
  $m.Id | Set-Content $monPidFile
  Write-Host "monitor started pid=$($m.Id) interval=${IntervalSec}s"
}
Write-Host "logs: $out | $err"
