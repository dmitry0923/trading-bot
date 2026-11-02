# Cross-sectional momentum/carry U5 (docs/50, 2026-11-02).
# /portfolio/momentum (IS70/OOS30) + /portfolio/momentum/validate (dev WFA).
#
# Запуск: & .\scripts\research_portfolio_xs.ps1
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$User = 'admin',
    [string]$Password = 'admin123456',
    [string]$Tickers = 'CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF',
    [int]$Days = 730,
    [string]$Timeframe = 'MINUTE_10',
    [int]$Folds = 8,
    [double]$HoldoutFraction = 0.30,
    [double]$InitialCapital = 100000,
    [string]$OutDir = 'artifacts'
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body (@{ username = $User; password = $Password } | ConvertTo-Json) -TimeoutSec 60
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$tickerList = $Tickers.Split(',') | Where-Object { $_ -ne '' }
$universeQuery = ($tickerList | ForEach-Object { "universe=$_" }) -join '&'

$configs = @(
    @{
        id = 'X1_mom_L2S4_rb300_vs210_vt30'
        extra = 'lookbackBars=1680&rebalanceBars=300&longCount=2&shortCount=4&momentumWeight=1&carryWeight=0&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=30&volTargetBarsPerYear=21000'
    },
    @{
        id = 'X2_mom_carry_L2S4_rb300_cw1_vs210_vt30'
        extra = 'lookbackBars=1680&rebalanceBars=300&longCount=2&shortCount=4&momentumWeight=1&carryWeight=1&carryLookbackDays=5&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=30&volTargetBarsPerYear=21000'
    },
    @{
        id = 'X3_carry_L0S3_rb630_cl5'
        extra = 'lookbackBars=1680&rebalanceBars=630&longCount=0&shortCount=3&momentumWeight=0&carryWeight=1&carryLookbackDays=5&volScalingEnabled=false&volTargetEnabled=false'
    }
)

$rows = @()
foreach ($cfg in $configs) {
    $common = "$universeQuery&timeframe=$Timeframe&days=$Days&capitalSlice=1.0&initialCapital=$InitialCapital&minUniverse=3&$($cfg.extra)"
    $btUrl = "$BaseUrl/api/v1/portfolio/momentum?$common&oosFraction=$HoldoutFraction"
    $wfaUrl = "$BaseUrl/api/v1/portfolio/momentum/validate?$common&folds=$Folds&holdoutFraction=$HoldoutFraction"
    Write-Host "GET $btUrl"
    $bt = Invoke-RestMethod -Uri $btUrl -Headers $headers -TimeoutSec 900
    $bt | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir ("portfolio_xs_2026-11-02_{0}_oos.json" -f $cfg.id)) -Encoding utf8
    Write-Host "GET $wfaUrl"
    $wfa = Invoke-RestMethod -Uri $wfaUrl -Headers $headers -TimeoutSec 900
    $wfa | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $OutDir ("portfolio_xs_2026-11-02_{0}_wfa.json" -f $cfg.id)) -Encoding utf8

    $oos = $bt.outOfSample
    $pass = ($oos.totalTrades -ge 30) -and ($oos.profitFactor -ge 1.30) -and ($oos.probabilityOfNoEdge -lt 0.05) -and ($oos.meanTradeCI95Low -gt 0) -and ($oos.maxDrawdown -le 0.40) -and ($wfa.consistency -ge 0.60)
    $verdict = if ($pass) { 'PASS' } elseif ($oos.profitFactor -lt 1.30 -or $oos.maxDrawdown -gt 0.40) { 'REJECTED' } else { 'INCONCLUSIVE' }
    $rows += [pscustomobject]@{
        config = $cfg.id
        oosTrades = $oos.totalTrades
        oosPF = $oos.profitFactor
        oosReturn = $oos.totalReturn
        oosSharpe = $oos.sharpeRatio
        oosP = $oos.probabilityOfNoEdge
        oosCiLow = $oos.meanTradeCI95Low
        oosCiHigh = $oos.meanTradeCI95High
        oosMdd = $oos.maxDrawdown
        devConsistency = $wfa.consistency
        wfaRobust = $wfa.robust
        verdict = $verdict
    }
    $rows[-1] | Format-List
}

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    tickers = $tickerList
    timeframe = $Timeframe
    days = $Days
    folds = $Folds
    holdoutFraction = $HoldoutFraction
    results = $rows
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $OutDir 'portfolio_xs_2026-11-02_summary.json') -Encoding utf8
$rows | Format-Table -AutoSize
