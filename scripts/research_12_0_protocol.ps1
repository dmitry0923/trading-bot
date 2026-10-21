# Research: отдельный предрегистрированный протокол для `U8 12−0` (momentum БЕЗ skip-month
# на юниверсе акций) — docs/26 §11.8 (предрегистрация зафиксирована ДО прогона).
#
# Зачем: §11.7 оставил единственный формальный PASS-конфиг класса (`12−0`: OOS PF 1.746,
# Sharpe 2.30, P(noEdge)=0.042, dev-cons 0.60, 70 OOS-сделок), но при `robust=false` и
# CI95 средней сделки, содержащем ноль. Вопрос «edge или режимный бет?» донастройкой сетки
# на том же окне §6 PLAN-001 запрещает — поэтому здесь три независимые декомпозиции
# ОДНОГО замороженного конфига (L249 / S0 / rb21), без единого нового подбираемого числа.
#
# Уровни (все — только через query-параметры существующего эндпоинта, нового кода нет):
#   L1 (решающий)     — WFA на ДВУХ непересекающихся под-периодах (days=1600 / days=1500), folds=3
#   L2 (решающий)     — leave-one-out: 8 прогонов WFA, каждый без одного тикера (7 из 8), folds=8
#   L2sig (решающий)  — на полном U8: P(noEdge) < 0.05 И CI95 средней сделки НЕ содержит ноль
#   L3 (диагностика)  — контрольные rb42 / rb10 / L187 (на вердикт НЕ влияют)
#
# Вердикт (правило зафиксировано в §11.8 ДО прогона, здесь не меняется):
#   PASS         — L1 обе половины PF>=1.30 (>=30 сделок) И L2 >=6/8 PF>=1.30
#                  И L2sig (P<0.05 и CI95 w/o zero) И robust=true хотя бы на одном уровне
#                  И MDD<=40% на полном U8;
#   REJECTED     — на полном U8 OOS PF<1.30 И P(noEdge)>=0.05 одновременно;
#   INCONCLUSIVE — всё остальное (в т.ч. расхождение L1/L2 = «единый режимный бет»).
#
# holdout НЕ используется: 30%-holdout израсходован в §11.7 (повтор = двойное
# использование истории). Вердикт — на WFA-агрегате + CI95 + декомпозиции.
#
# Запуск (в фоне, с heartbeat):
#   Start-Job -ScriptBlock { & .\scripts\research_12_0_protocol.ps1 }
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$FullDays = 3220,
    [int]$FullFolds = 8,
    [int]$HalfFolds = 3,
    [int]$EarlyDays = 1600,
    [int]$LateDays = 1500,
    [double]$MinPf = 1.30,
    [int]$MinTrades = 30,
    [double]$MaxPNoEdge = 0.05,
    [double]$MaxMddPct = 40.0,
    [int]$TimeoutSec = 7200,
    [string]$OutDir = "artifacts",
    [string]$StatusFile = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "p6-12-0-protocol-$stamp.status" }
if (-not [System.IO.Path]::IsPathRooted($OutDir)) { $OutDir = Join-Path $root $OutDir }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

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

# Замороженный юниверс U8 (§11.4/§11.6) — 8 акций TQBR, DAY_1, 2018-01-03..
$u8 = @("GAZP", "LKOH", "MGNT", "NVTK", "PLZL", "ROSN", "SBER", "TATN")
# Замороженный конфиг `12−0`: lookback 249, skip 0, rebalance 21 (§11.4/§11.6, без изменений)
# — теперь задаётся как дефолты внутри Run-Wfa (см. `$p`), чтобы override не дублировал их.

$wfaBase = "$BaseUrl/api/v1/portfolio/momentum/validate"

Write-Status ("start: U8={0} full=d{1}/f{2} halves=d{3},{4}/f{5}" -f ($u8 -join ","), $FullDays, $FullFolds, $EarlyDays, $LateDays, $HalfFolds)
Write-Status ("status file: {0}" -f $StatusFile)

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

# ВНИМАНИЕ (harness-грабли, AGENTS.md): Spring берёт ПЕРВОЕ значение дублированного
# query-параметра. Собираем параметры хеш-таблицей и сериализуем один раз, чтобы
# override НЕ оказался дубликатом базового значения (иначе фильтр молча не работает).
function Run-Wfa([string]$name, [int]$days, [int]$folds, [string[]]$universe, [hashtable]$overrides = @{}) {
    $uni = ($universe -join ",")
    $p = [ordered]@{
        days        = $days
        folds       = $folds
        universe    = $uni
        timeframe   = "DAY_1"
        lookbackBars = 249
        skipBars    = 0
        rebalanceBars = 21
    }
    foreach ($k in $overrides.Keys) { $p[$k] = $overrides[$k] }
    $qs = ($p.Keys | ForEach-Object { "$_=$($p[$_])" }) -join "&"
    $url = "$wfaBase`?$qs"
    $ovText = if ($overrides.Count -gt 0) { ($overrides.Keys | Sort-Object | ForEach-Object { "$_=$($overrides[$_])" }) -join " " } else { "" }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $resp = Invoke-Get $url
    $sw.Stop()
    $agg = $resp.aggregate
    if ([int]$resp.folds -eq 0) {
        throw "level '$name' returned 0 folds (bars=$($resp.bars), folds requested=$folds) — уровень не исполним, протокол без него недействителен"
    }
    $row = [pscustomobject]@{
        Level        = $name
        Universe     = $uni
        Days         = $days
        Folds        = $folds
        Extra        = $ovText
        Bars         = $resp.bars
        RanFolds     = $resp.folds
        Consistency  = [math]::Round([double]$resp.consistency, 3)
        Robust       = [bool]$resp.robust
        AggPf        = [math]::Round([double]$agg.profitFactor, 3)
        AggRetPct    = [math]::Round([double]$agg.totalReturn * 100, 2)
        AggSharpe    = [math]::Round([double]$agg.sharpeRatio, 2)
        AggMddPct    = [math]::Round([double]$agg.maxDrawdown * 100, 2)
        Trades       = [int]$agg.totalTrades
        PNoEdge      = [math]::Round([double]$agg.probabilityOfNoEdge, 4)
        Ci95Low      = [math]::Round([double]$agg.meanTradeCI95Low, 2)
        Ci95High     = [math]::Round([double]$agg.meanTradeCI95High, 2)
        ServiceVerdict = $resp.verdict
        Secs         = [math]::Round($sw.Elapsed.TotalSeconds, 0)
    }
    Write-Status ("  {0,-28} PF={1,-6} trades={2,-4} P={3,-7} robust={4} cons={5} ({6}s)" -f `
            $name, $row.AggPf, $row.Trades, $row.PNoEdge, $row.Robust, $row.Consistency, $row.Secs)
    return [pscustomobject]@{ Row = $row; Raw = $resp }
}

# ---------------------------------------------------------------- L3: параметрические контроли
# Идут ПЕРВЫМИ и заранее объявлены диагностикой — их результат вердикт не меняет.
# rb42 требует minBars/фолд = 249+0+42+2 = 293 → 8 фолдов = 2344 > 2330, поэтому folds=7
# (зафиксировано в §11.8 ДО прогона; guard ниже падает, если уровень не исполним).
$l3 = @()
$l3 += Run-Wfa "L3 rb42 (folds=7)"  $FullDays 7 $u8 @{ rebalanceBars = 42 }
$l3 += Run-Wfa "L3 rb10"            $FullDays $FullFolds $u8 @{ rebalanceBars = 10 }
$l3 += Run-Wfa "L3 L187 rb21"       $FullDays $FullFolds $u8 @{ lookbackBars = 187 }

# ---------------------------------------------------------------- L2: leave-one-out (пространственная)
$l2 = @()
foreach ($drop in $u8) {
    $uni = $u8 | Where-Object { $_ -ne $drop }
    $l2 += Run-Wfa "L2 -$drop" $FullDays $FullFolds $uni
}

# ---------------------------------------------------------------- L1: два непересекающихся под-периода
$l1 = @()
$l1 += Run-Wfa "L1 early (2018-2022)" $EarlyDays $HalfFolds $u8
$l1 += Run-Wfa "L1 late (2022-2026)"  $LateDays $HalfFolds $u8

# ---------------------------------------------------------------- полный U8 (вердикт + CI95)
$full = Run-Wfa "FULL U8 (verdict)" $FullDays $FullFolds $u8

# ================================================================ вердикт (§11.8, не меняется)
$l1Pass = @($l1 | Where-Object { $_.Row.AggPf -ge $MinPf -and $_.Row.Trades -ge $MinTrades })
$l1Ok = ($l1Pass.Count -eq $l1.Count)
$l2Pass = @($l2 | Where-Object { $_.Row.AggPf -ge $MinPf })
$l2Ok = ($l2Pass.Count -ge 6)
$ciHasZero = ($full.Row.Ci95Low -le 0 -and $full.Row.Ci95High -ge 0)
$sigOk = ($full.Row.PNoEdge -lt $MaxPNoEdge) -and (-not $ciHasZero)
$robustAny = (@($full.Row, $l1[0].Row, $l1[1].Row) | Where-Object { $_.Robust }).Count -gt 0
$mddOk = ($full.Row.AggMddPct -le $MaxMddPct)

$reject = ($full.Row.AggPf -lt $MinPf) -and ($full.Row.PNoEdge -ge $MaxPNoEdge)
$passOk = $l1Ok -and $l2Ok -and $sigOk -and $robustAny -and $mddOk

$verdict = if ($passOk) { "PASS (research-кандидат)" }
elseif ($reject) { "REJECTED (полный U8: PF<$MinPf и P>=$MaxPNoEdge)" }
else { "INCONCLUSIVE" }

$summary = [pscustomobject]@{
    protocol      = "12-0 momentum bez skip, U8 DAY_1 (docs/26 s11.8)"
    frozenConfig  = "lookbackBars=249 skipBars=0 rebalanceBars=21"
    universe      = ($u8 -join ",")
    verdict       = $verdict
    gateL1        = [pscustomobject]@{ passed = $l1Ok; detail = "$($l1Pass.Count)/$($l1.Count) половин PF>=$MinPf и trades>=$MinTrades" }
    gateL2        = [pscustomobject]@{ passed = $l2Ok; detail = "$($l2Pass.Count)/$($l2.Count) leave-one-out PF>=$MinPf" }
    gateSignificance = [pscustomobject]@{ passed = $sigOk; pNoEdge = $full.Row.PNoEdge; ci95 = @($full.Row.Ci95Low, $full.Row.Ci95High); ciHasZero = $ciHasZero }
    gateRobust    = [pscustomobject]@{ passed = $robustAny }
    gateMdd       = [pscustomobject]@{ passed = $mddOk; mddPct = $full.Row.AggMddPct; limitPct = $MaxMddPct }
    full          = $full.Row
    l1            = @($l1 | ForEach-Object { $_.Row })
    l2            = @($l2 | ForEach-Object { $_.Row })
    l3Diagnostic  = @($l3 | ForEach-Object { $_.Row })
    generatedAt   = (Get-Date).ToString("s")
}

$l1Csv = Join-Path $OutDir "p6_12_0_protocol_l1.csv"
$l2Csv = Join-Path $OutDir "p6_12_0_protocol_l2.csv"
$l3Csv = Join-Path $OutDir "p6_12_0_protocol_l3.csv"
$sumJson = Join-Path $OutDir "p6_12_0_protocol_summary.json"
@($l1 | ForEach-Object { $_.Row }) | Export-Csv -LiteralPath $l1Csv -NoTypeInformation -Encoding UTF8
@($l2 | ForEach-Object { $_.Row }) | Export-Csv -LiteralPath $l2Csv -NoTypeInformation -Encoding UTF8
@($l3 | ForEach-Object { $_.Row }) | Export-Csv -LiteralPath $l3Csv -NoTypeInformation -Encoding UTF8
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $sumJson -Encoding UTF8

Write-Status ("VERDICT: {0}" -f $verdict)
Write-Status ("  L1={0}  L2={1}  sig={2}  robust={3}  mdd={4}" -f $l1Ok, $l2Ok, $sigOk, $robustAny, $mddOk)
Write-Status ("csv: {0} | {1} | {2}" -f $l1Csv, $l2Csv, $l3Csv)
Write-Status ("json: {0}" -f $sumJson)

$summary | ConvertTo-Json -Depth 8
