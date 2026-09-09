# Research-пайплайн донакачки истории CNYRUBF + финальная статистическая
# валидация (WFA validate + deployment-gate: holdout + Monte Carlo).
#
# Почему нужен: TimescaleDB retention policy (`policy_retention`, drop_after 90
# дней, миграция 012) автоматически удаляет старые чанки candles — в БД живёт
# только ~3 мес истории, а для валидации conf 0.60/0.63 нужны 6–12 мес.
#
# Пайплайн:
#   1) (-DropRetention) ВЫКЛЮЧАЕТ retention policy на candles — иначе донакачка
#      на 365д+ будет удалена фоновым worker'ом;
#   2) auth → GET /api/v1/backtest/CNYRUBF?loadHistory=true&days=365 — MOEX ISS
#      качает+pерсистит всю историю (HistoricalDataLoader, клейка контрактов);
#   3) GET /validate?loadHistory=true&days=365&folds=6&adaptiveConfidenceThreshold=…
#      — walk-forward OOS (для фьючерсов это корректный инструмент: SL/TP в пунктах);
#   4) GET /deployment-gate?loadHistory=true&days=365&folds=6&researchMode=true
#      — консолидированный backtest + WFA + независимый holdout + Monte Carlo.
#
# Пример:
#   ./scripts/research_wfa_cnyrubf.ps1 -Days 365 -Folds 6 -DropRetention
#   ./scripts/research_wfa_cnyrubf.ps1 -Days 365 -Folds 6 -Conf 0.60 -DropRetention
#
# Требования: app запущен на $BaseUrl (java -jar с --spring.mvc.async.request-timeout=600000),
# docker-контейнер postgres ($DbContainer) жив, в .env заданы AUTH_USER/AUTH_PASSWORD.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Ticker = "CNYRUBF",
    [int]$Days = 365,
    [int]$Folds = 6,
    [double]$Conf = 0.63,
    [string]$DbContainer = "trading-bot-db",
    [switch]$DropRetention,
    [string]$DropAfterDays = "730 days"
)

$ErrorActionPreference = "Stop"
$envPath = Join-Path $PSScriptRoot ".." ".env"

if (-not (Test-Path $envPath)) {
    throw ".env not found at $envPath (AUTH_USER/AUTH_PASSWORD required)"
}
function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"
if (-not $authUser -or -not $authPassword) {
    throw "AUTH_USER/AUTH_PASSWORD not set in $envPath"
}

if ($DropRetention) {
    Write-Host "[1/4] Disabling candles retention policy ($DropAfterDays)…"
    $pass = Resolve-EnvValue "DB_PASS"
    if (-not $pass) { throw "DB_PASS not set in $envPath" }
    $sql = @"
SELECT remove_retention_policy('candles', if_exists => true);
SELECT add_retention_policy('candles', INTERVAL '$DropAfterDays', if_not_exists => true);
"@
    $sql | docker exec -i -e PGPASSWORD=$pass $DbContainer psql -U trader -d trading_bot -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Failed to update retention policy" }
}

# ---- Auth (JWT access token) ----
Write-Host "[2/4] Authenticating as $authUser…"
$loginBody =
    @{
        username = $authUser
        password = $authPassword
    } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }

function Invoke-GateRequest([string]$suffix) {
    $full = "$BaseUrl$suffix"
    Write-Host "  GET $full"
    try {
        return Invoke-RestMethod -Method Get -Uri $full -Headers $headers -TimeoutSec 600
    } catch {
        Write-Host "  FAILED: $($_.Exception.Message)"
        return $null
    }
}

# ---- Донакачка (HistoricalDataLoader: MOEX ISS, пагинация, склейка контрактов) ----
Write-Host "[3/4] Refill + base backtest: loadHistory=true&days=$Days…"
$base = Invoke-GateRequest "/api/v1/backtest/$Ticker`?loadHistory=true&days=$Days"
if ($base) {
    Write-Host ("  base: passable={0} totalReturn={1:P1} maxDrawdown={2:P1} trades={3} pf={4}" -f `
            $base.passable, $base.totalReturn, $base.maxDrawdown, $base.totalTrades, $base.profitFactor)
}

# ---- Walk-forward (conf из фьючерсной калибровки; request-параметр) ----
Write-Host "[4/4] WFA validate: days=$Days folds=$Folds conf=$Conf…"
$wfa = Invoke-GateRequest "/api/v1/backtest/$Ticker/validate`?loadHistory=true&days=$Days&folds=$Folds&adaptiveConfidenceThreshold=$Conf"
if ($wfa) {
    Write-Host ("  wfa: robust={0} consistency={1} oosRet={2:P1} oosSharpe={3} oosPF={4} oosTrades={5} edge={6}" -f `
            $wfa.robust, $wfa.consistency, $wfa.oosReturn, $wfa.oosSharpe, $wfa.oosProfitFactor, $wfa.oosTrades, $wfa.oosEdgeStatisticallySignificant)
}

# ---- Deployment-gate (holdout + Monte Carlo, тот же loadHistory+days) ----
Write-Host "Deployment-gate (research): days=$Days folds=$Folds…"
$gate = Invoke-GateRequest "/api/v1/backtest/$Ticker/deployment-gate`?loadHistory=true&days=$Days&folds=$Folds&researchMode=true"
if ($gate) {
    Write-Host ("  gate: status={0} liveAllowed={1} researchMode={2} frozenConf={3}" -f `
            $gate.status, $gate.liveAllowed, $gate.researchMode, $gate.frozenConfidenceThreshold)
    foreach ($c in $gate.checks) {
        $mark = if ($c.passed) { "PASS" } else { "FAIL" }
        Write-Host ("    [{0}] {1}: {2}" -f $mark, $c.label, $c.detail)
    }
}
Write-Host "Done."