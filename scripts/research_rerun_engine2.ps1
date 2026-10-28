# Пересчёт всех ранее измеренных направлений на текущем движке (engineVersion 2026-10-26.2).
#
# Зачем: числа всех research-направлений до 2026-10-26 помечены INVALID (docs/29 §4),
# потому что движок с тех пор изменился (проверка SL/TP на баре входа, equity-кривая,
# funding point-in-time, выравнивание уровней по шагу цены, окно старшего ТФ).
# Этот скрипт повторяет ИЗМЕРЕНИЕ каждого направления на актуальном движке.
#
# Метод: in-sample `/backtest` на том же окне (days) и той же конфигурации, что в исходном
# измерении, с явными research-параметрами сайзинга. Это скрининг: он отвечает на вопрос
# «какие числа теперь», а не «есть ли OOS-edge». OOS-вердикт по выжившим — отдельным
# предрегистрированным прогоном (docs/30).
#
# Каждый кандидат: "имя;тикер;дни;query". Скрипт идемпотентен: уже записанные в CSV
# кандидаты пропускаются (можно прервать и продолжить).
param(
    [string]$BaseUrl = "http://127.0.0.1:8081",
    [string]$OutCsv = "artifacts/rerun_engine2/candidates.csv",
    [string]$StatusFile = "",
    [int]$TimeoutSec = 3600,
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $root ".env"

function Write-Status([string]$line) {
    Write-Host $line
    if ($StatusFile) { Add-Content -LiteralPath $StatusFile -Value $line -Encoding UTF8 }
}

function Resolve-EnvValue([string]$name, [string]$def = "") {
    $line = Get-Content $envPath | Where-Object { $_ -match "^$name=" } | Select-Object -First 1
    if (-not $line) { return $def }
    return ($line -replace "^$name=", "").Trim('"')
}

$authUser = Resolve-EnvValue "AUTH_USER"
$authPassword = Resolve-EnvValue "AUTH_PASSWORD"

function Get-Headers {
    $login =
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType "application/json" `
            -Body (@{ username = $authUser; password = $authPassword } | ConvertTo-Json)
    return @{ Authorization = "Bearer $($login.accessToken)" }
}

# name;ticker;days;query — конфигурации скопированы из исходных измерений (AGENTS/docs 17-28),
# БЕЗ донастройки: цель — узнать, какими стали числа, а не улучшить их.
$candidates = @(
    "base_730;CNYRUBF;730;futuresMaxContractsPerPosition=1",
    "fv_365_2_2;CNYRUBF;365;futuresMaxContractsPerPosition=1&fundingVetoEnabled=true&fundingVetoLongThresholdRub=2&fundingVetoShortThresholdRub=2",
    "fv_365_6_6;CNYRUBF;365;futuresMaxContractsPerPosition=1&fundingVetoEnabled=true&fundingVetoLongThresholdRub=6&fundingVetoShortThresholdRub=6",
    "fv_365_9_2;CNYRUBF;365;futuresMaxContractsPerPosition=1&fundingVetoEnabled=true&fundingVetoLongThresholdRub=9&fundingVetoShortThresholdRub=2",
    "mh_365_368;CNYRUBF;365;futuresMaxContractsPerPosition=1&maxHoldBars=368",
    "td_365_l9;CNYRUBF;365;futuresMaxContractsPerPosition=1&timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9",
    "orb_365_w12;CNYRUBF;365;futuresMaxContractsPerPosition=1&orbEnabled=true&orbWindowBars=12&orbStrictBreakout=false",
    "ml_365_on;CNYRUBF;365;futuresMaxContractsPerPosition=1&mlDirectionEnabled=true",
    "session_365_14_18;CNYRUBF;365;futuresMaxContractsPerPosition=1&sessionFilterEnabled=true&sessionFilterStartMinutes=840&sessionFilterEndMinutes=1080",
    "pullback_365_03;CNYRUBF;365;futuresMaxContractsPerPosition=1&pullbackFilterEnabled=true&pullbackFilterEmaPeriod=20&pullbackFilterMaxDeviationPercent=0.3",
    "squeeze_365;CNYRUBF;365;futuresMaxContractsPerPosition=1&squeezeEnabled=true&squeezeBlockOnUnknown=true",
    "macro_365;CNYRUBF;365;futuresMaxContractsPerPosition=1&macroTrendEnabled=true&macroTrendMaxDeviationPercent=0.5&macroTrendBlockOnUnknown=true",
    "vwapmr_365;CNYRUBF;365;futuresMaxContractsPerPosition=1&vwapMrEnabled=true&vwapMrDeviationSigma=2.0&vwapMrMaxAdx=25&vwapMrBlockOnUnknown=true",
    "combo730;CNYRUBF;730;riskPerTradePercent=30&futuresMaxContractsPerPosition=100&maxHoldBars=368&fundingVetoEnabled=true&fundingVetoLongThresholdRub=6&fundingVetoShortThresholdRub=6&timeDirectionEnabled=true&timeDirectionLongBlockUntilHour=9",
    "risk_5_20;CNYRUBF;730;riskPerTradePercent=5&futuresMaxContractsPerPosition=20&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0",
    "risk_10_50;CNYRUBF;730;riskPerTradePercent=10&futuresMaxContractsPerPosition=50&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0",
    "risk_15_75;CNYRUBF;730;riskPerTradePercent=15&futuresMaxContractsPerPosition=75&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0",
    "risk_20_100;CNYRUBF;730;riskPerTradePercent=20&futuresMaxContractsPerPosition=100&mlDirectionEnabled=false&fundingVetoEnabled=false&maxHoldBars=0",
    "stock_gazp;GAZP;365;leverage=5&mlDirectionEnabled=false",
    "stock_nvtk;NVTK;365;leverage=5&mlDirectionEnabled=false",
    "stock_plzl;PLZL;365;leverage=5&mlDirectionEnabled=false",
    "stock_sber;SBER;365;leverage=5&mlDirectionEnabled=false",
    "cny_h1;CNYRUBF;365;timeframe=HOUR_1&futuresMaxContractsPerPosition=1",
    "cny_d1;CNYRUBF;365;timeframe=DAY_1&futuresMaxContractsPerPosition=1",
    "perp_usdrubf;USDRUBF;730;riskPerTradePercent=30&futuresMaxContractsPerPosition=100",
    "perp_eurrubf;EURRUBF;730;riskPerTradePercent=30&futuresMaxContractsPerPosition=100",
    "perp_gldrubf;GLDRUBF;730;riskPerTradePercent=30&futuresMaxContractsPerPosition=100",
    "perp_imoexf;IMOEXF;730;riskPerTradePercent=30&futuresMaxContractsPerPosition=100",
    "panic_imoexf;IMOEXF;730;panicReversalEnabled=true&panicMinSessionDropPercent=3&panicMaxRsi=30&panicBlockOnUnknown=true"
)

New-Item -ItemType Directory -Force -Path (Split-Path -Parent (Join-Path $root $OutCsv)) | Out-Null
$csvPath = Join-Path $root $OutCsv
$done = @{}
if ((Test-Path $csvPath) -and -not $Force) {
    Import-Csv $csvPath | ForEach-Object { $done[$_.Config] = $true }
}

$headers = Get-Headers
Write-Status ("[{0}] start: engine rerun, candidates={1}, already done={2}" -f (Get-Date -Format "HH:mm:ss"), $candidates.Count, $done.Count)

foreach ($raw in $candidates) {
    $parts = $raw.Split(";")
    if ($parts.Count -ne 4) { throw "bad candidate spec: $raw" }
    $name = $parts[0].Trim()
    $ticker = $parts[1].Trim()
    $days = [int]$parts[2].Trim()
    $query = $parts[3].Trim()

    if ($done.ContainsKey($name)) { Write-Status ("skip {0} (already measured)" -f $name); continue }

    $url = "$BaseUrl/api/v1/backtest/$ticker" + "?days=$days&loadHistory=false&includeTrades=false" + "&" + $query
    Write-Status ("[{0}] run {1}: {2}" -f (Get-Date -Format "HH:mm:ss"), $name, $url)

    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        try {
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
        } catch {
            Write-Status ("[{0}] re-auth after: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
            $headers = Get-Headers
            $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec $TimeoutSec
        }
        $sw.Stop()
        $row = [pscustomobject]@{
            Config        = $name
            Ticker        = $ticker
            Days          = $days
            Query         = $query
            EngineVersion = $r.engineVersion
            ReturnPct     = [math]::Round([double]$r.totalReturn * 100, 3)
            ProfitFactor  = [math]::Round([double]$r.profitFactor, 4)
            Sharpe        = [math]::Round([double]$r.sharpeRatio, 3)
            MaxDDPct      = [math]::Round([double]$r.maxDrawdown * 100, 3)
            WinRatePct    = [math]::Round([double]$r.winRate * 100, 2)
            Trades        = [int]$r.totalTrades
            PNoEdge       = [math]::Round([double]$r.probabilityOfNoEdge, 4)
            EdgeSig       = [bool]$r.edgeStatisticallySignificant
            Passable      = [bool]$r.passable
            ElapsedSec    = [int]$sw.Elapsed.TotalSeconds
            MeasuredAt    = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        }
        if (Test-Path $csvPath) { $row | Export-Csv -Path $csvPath -NoTypeInformation -Append -Encoding UTF8 }
        else { $row | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8 }
        Write-Status ("[{0}] done {1}: ret={2}% pf={3} trades={4} pNoEdge={5} ({6}s)" -f `
                (Get-Date -Format "HH:mm:ss"), $name, $row.ReturnPct, $row.ProfitFactor, $row.Trades, $row.PNoEdge, $row.ElapsedSec)
    } catch {
        $sw.Stop()
        $row = [pscustomobject]@{
            Config        = $name
            Ticker        = $ticker
            Days          = $days
            Query         = $query
            EngineVersion = "ERROR"
            ReturnPct     = ""
            ProfitFactor  = ""
            Sharpe        = ""
            MaxDDPct      = ""
            WinRatePct    = ""
            Trades        = ""
            PNoEdge       = ""
            EdgeSig       = ""
            Passable      = ""
            ElapsedSec    = [int]$sw.Elapsed.TotalSeconds
            MeasuredAt    = (Get-Date -Format "yyyy-MM-dd HH:mm:ss")
        }
        if (Test-Path $csvPath) { $row | Export-Csv -Path $csvPath -NoTypeInformation -Append -Encoding UTF8 }
        else { $row | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8 }
        Write-Status ("[{0}] FAILED {1}: {2}" -f (Get-Date -Format "HH:mm:ss"), $name, $_.Exception.Message)
    }
}

Write-Status ("[{0}] finished" -f (Get-Date -Format "HH:mm:ss"))
