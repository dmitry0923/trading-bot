# Research: holdout-протокол IS70/OOS30 для cross-sectional momentum + carry
# портфеля (18_10_2026, P1 шаг 1; docs/26 §6, план
# «протокол IS70/OOS30 — вердикт только по holdout, никакого перебора на holdout»).
#
# Два вызова на замороженный конфиг:
#   1) /portfolio/momentum/validate?holdoutFraction=.. — walk-forward на ПЕРВЫХ
#      (1 − HoldoutFraction) истории (dev-часть): consistency/robust/aggregate —
#      диагностика и гейт consistency, НЕ вердикт по доходности;
#   2) /portfolio/momentum?oosFraction=.. — непрерывный holdout-сегмент
#      (последние HoldoutFraction истории), вердикт ТОЛЬКО по нему.
#
# Вердикт (гейты §4 PLAN-001 в связке с docs/26):
#   REJECTED     — OOS PF < MinOosPf;
#   INCONCLUSIVE — OOS сделок < MinOosTrades ИЛИ P(noEdge) >= 0.05
#                  ИЛИ dev-consistency < MinConsistency;
#   PASS         — все гейты пройдены (= research-кандидат, НЕ live).
#
# Конфиги задаются заранее (ConfigCsv = "имя;query-параметры"): скрипт ничего
# не подбирает. Дефолт — замороженные победители docs/26 (§5/§6/§8a/§8c).
#
# Фоновый запуск с heartbeat — как research_oos70.ps1:
#   Start-Job -ScriptBlock { & .\scripts\research_oos70_portfolio.ps1 }
# Скрипт пишет прогресс в status-файл (см. -StatusFile, путь печатается).
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$HoldoutFraction = 0.30,
    [double]$MinOosPf = 1.3,
    [int]$MinOosTrades = 30,
    [double]$MinConsistency = 0.6,
    [int]$TimeoutSec = 21600,
    [string]$OutCsv = "",
    [string]$OutJson = "",
    [string]$StatusFile = "",
    # "имя;query-параметры" — каждый элемент = один замороженный конфиг.
    # Пусто = дефолтный список (baseline + победители docs/26).
    [string[]]$ConfigCsv = @()
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "oos70-portfolio-$stamp.csv" }
if (-not $OutJson) { $OutJson = Join-Path $env:TEMP "oos70-portfolio-$stamp.json" }
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "oos70-portfolio-$stamp.status" }

function Write-Status([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $StatusFile -Value $line -Encoding UTF8
}

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}
$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"

function Get-Headers {
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($login.accessToken)" }
}
$headers = Get-Headers

$u5 = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF"
$u13 = "$u5,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN"
# P1 шаг 2 (18_10_2026): расширение юниверса без перебора параметров —
#   U7 = U5 + SBERF/GAZPF (perpetual'ы, carry-eligible: funding 508 дат в БД);
#   U15 = U13 + SBERF/GAZPF (momentum-only, у акций funding нет).
$u7 = "$u5,SBERF,GAZPF"
$u15 = "$u13,SBERF,GAZPF"
$defaultConfigs = @(
    "baseline U5 mom L1S1 rb420;",
    "mom+carry U5 L1S1 rb210 cw1;&rebalanceBars=210&carryWeight=1",
    "mom+carry U5 L1S3 rb210 cw1;&rebalanceBars=210&longCount=1&shortCount=3&carryWeight=1",
    "mom U13 L2S4 rb300 vs210 vt30;&universe=$u13&longCount=2&shortCount=4&rebalanceBars=300&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.30",
    "mom U13 L2S3 rb210 vs420 vt15;&universe=$u13&longCount=2&shortCount=3&rebalanceBars=210&volScalingEnabled=true&volLookbackBars=420&volTargetEnabled=true&volTargetAnnualized=0.15",
    "carry-only U5 L0S3 rb630 cl5;&momentumWeight=0&carryWeight=1&longCount=0&shortCount=3&rebalanceBars=630&carryLookbackDays=5",
    # --- шаг 2: расширенный юниверс (замороженные параметры + плато cw/rb) ---
    "s2 U7 mom+carry L1S1 rb210 cw1;&universe=$u7&rebalanceBars=210&carryWeight=1",
    "s2 U7 mom+carry L1S3 rb210 cw1;&universe=$u7&longCount=1&shortCount=3&rebalanceBars=210&carryWeight=1",
    "s2 U7 mom+carry L1S1 rb210 cw0.5;&universe=$u7&rebalanceBars=210&carryWeight=0.5",
    "s2 U7 mom+carry L1S1 rb210 cw2;&universe=$u7&rebalanceBars=210&carryWeight=2",
    "s2 U7 mom+carry L1S1 rb150 cw1;&universe=$u7&rebalanceBars=150&carryWeight=1",
    "s2 U7 mom+carry L1S1 rb420 cw1;&universe=$u7&rebalanceBars=420&carryWeight=1",
    "s2 U7 carry-only L0S3 rb630 cl5;&universe=$u7&momentumWeight=0&carryWeight=1&longCount=0&shortCount=3&rebalanceBars=630&carryLookbackDays=5",
    "s2 U15 mom L2S4 rb300 vs210 vt30;&universe=$u15&longCount=2&shortCount=4&rebalanceBars=300&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.30",
    "s2 U15 mom L2S3 rb210 vs420 vt15;&universe=$u15&longCount=2&shortCount=3&rebalanceBars=210&volScalingEnabled=true&volLookbackBars=420&volTargetEnabled=true&volTargetAnnualized=0.15"
)
$raw = if ($ConfigCsv.Count -eq 0) { $defaultConfigs } else { $ConfigCsv }
$configs = @()
foreach ($c in $raw) {
    if (-not $c) { continue }
    $parts = $c.Split(";", 2)
    if ($parts.Count -lt 2) {
        throw "ConfigCsv: элемент '$c' без разделителя ';' — молча ушёл бы в baseline (см. harness-баг strategies-cores-730d)"
    }
    $configs += @{ Name = $parts[0]; Query = $parts[1] }
}

$wfaBase = "$BaseUrl/api/v1/portfolio/momentum/validate?days=$Days&folds=$Folds&holdoutFraction=$HoldoutFraction"
$oosBase = "$BaseUrl/api/v1/portfolio/momentum?days=$Days&oosFraction=$HoldoutFraction"

Write-Status ("start: days={0} folds={1} holdout={2} configs={3}" -f $Days, $Folds, $HoldoutFraction, $configs.Count)
Write-Status ("csv: {0}" -f $OutCsv)

function Invoke-Get([string]$url) {
    try {
        return Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    } catch {
        Write-Status ("retry after re-auth: {0}" -f $_.Exception.Message)
        Start-Sleep -Seconds 10
        $script:headers = Get-Headers
        return Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
    }
}

$rows = @()
$results = @()
$swAll = [System.Diagnostics.Stopwatch]::StartNew()
foreach ($cfg in $configs) {
    $suffix = if ($cfg.Query) { "&$($cfg.Query)" } else { "" }
    $wfaUrl = "$wfaBase$suffix"
    $oosUrl = "$oosBase$suffix"
    Write-Status ("run: {0}" -f $cfg.Name)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    $w = Invoke-Get $wfaUrl
    $m = Invoke-Get $oosUrl
    $sw.Stop()

    $oosPf = [double]$m.outOfSample.profitFactor
    $oosTrades = [int]$m.outOfSample.totalTrades
    $oosRetPct = [math]::Round([double]$m.outOfSample.totalReturn * 100, 2)
    $oosSharpe = [math]::Round([double]$m.outOfSample.sharpeRatio, 2)
    $oosMddPct = [math]::Round([double]$m.outOfSample.maxDrawdown * 100, 2)
    $oosP = [math]::Round([double]$m.outOfSample.probabilityOfNoEdge, 4)
    $cons = [math]::Round([double]$w.consistency, 3)

    $failed = @()
    if ($oosPf -lt $MinOosPf) { $failed += "PF<$MinOosPf" }
    if ($oosTrades -lt $MinOosTrades) { $failed += "trades<$MinOosTrades" }
    if ($oosP -ge 0.05) { $failed += "P>=0.05" }
    if ($cons -lt $MinConsistency) { $failed += "cons<$MinConsistency" }

    $hardReject = $failed -contains "PF<$MinOosPf"
    $verdict = if ($failed.Count -eq 0) { "PASS (research-кандидат)" }
    elseif ($hardReject) { "REJECTED ($($failed -join ', '))" }
    else { "INCONCLUSIVE ($($failed -join ', '))" }

    $rows += [pscustomobject]@{
        Config = $cfg.Name
        Verdict = $verdict
        OOS_PF = [math]::Round($oosPf, 3)
        OOS_RetPct = $oosRetPct
        OOS_Sharpe = $oosSharpe
        OOS_MDD_Pct = $oosMddPct
        OOS_Trades = $oosTrades
        OOS_PNoEdge = $oosP
        Dev_Cons = $cons
        Dev_Robust = $w.robust
        Dev_AggPF = [math]::Round([double]$w.aggregate.profitFactor, 3)
        Dev_Bars = $w.bars
        Dev_Folds = $w.folds
        Dev_Verdict = $w.verdict
        Secs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    $results += [pscustomobject]@{
        config = $cfg.Name
        query = $cfg.Query
        verdict = $verdict
        failedGates = $failed
        holdout = $m.outOfSample
        holdoutServiceVerdict = $m.verdict
        devWfa = [pscustomobject]@{
            bars = $w.bars
            folds = $w.folds
            consistency = $w.consistency
            robust = $w.robust
            verdict = $w.verdict
            aggregate = $w.aggregate
            foldResults = $w.foldResults
        }
        wfaUrl = $wfaUrl
        oosUrl = $oosUrl
        elapsedSecs = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    Write-Host ""
    $rows | Format-Table -AutoSize
    Write-Status ("done: {0} -> {1} OOS PF={2} trades={3} P={4} cons={5} {6}s" -f `
            $cfg.Name, $verdict, [math]::Round($oosPf, 3), $oosTrades, $oosP, $cons, [math]::Round($sw.Elapsed.TotalSeconds, 0))
}
$swAll.Stop()

Write-Host ""
$rows | Format-Table -AutoSize
$rows | Export-Csv -Path $OutCsv -NoTypeInformation -Encoding UTF8
$results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutJson -Encoding UTF8
Write-Status ("csv: {0}" -f $OutCsv)
Write-Status ("json: {0}" -f $OutJson)
Write-Status ("total: {0}s" -f [math]::Round($swAll.Elapsed.TotalSeconds, 0))
Write-Host ""
Write-Host "Вердикт построен ТОЛЬКО по holdout-сегменту (последние $HoldoutFraction истории)."
Write-Host "dev-колонки (consistency/robust/aggregate) — диагностика + гейт consistency."
Write-Host "PASS = research-кандидат, не live: live-риск (maxC=1, Kelly), LIVE-guard CNYRUBF"
Write-Host "и все research-флаги (bt.portfolio.*) не менялись."
