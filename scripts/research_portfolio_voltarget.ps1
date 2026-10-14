param(
  [int]$Days = 730,
  [double]$Oos = 0.30,
  [string]$Universe = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF,GAZP,LKOH,MGNT,NVTK,PLZL,ROSN,SBER,TATN",
  [string]$Base = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30
$h = @{ Authorization = "Bearer $($login.accessToken)" }

function Test-Cfg {
  param($Name, $Query)
  $w = Invoke-RestMethod -Method Get -Uri "$Base/api/v1/portfolio/momentum/validate?days=$Days&folds=8$Query" -Headers $h -TimeoutSec 21600
  $a = $w.aggregate
  $m = Invoke-RestMethod -Method Get -Uri "$Base/api/v1/portfolio/momentum?days=$Days&oosFraction=$Oos$Query" -Headers $h -TimeoutSec 21600
  $o = $m.outOfSample
  [pscustomobject]@{
    cfg = $Name
    wfaPF = [math]::Round($a.profitFactor, 2)
    cons = $w.consistency
    rob = $w.robust
    wfaP = $a.probabilityOfNoEdge
    oosPF = [math]::Round([double]$o.profitFactor, 2)
    oosRet = [math]::Round([double]$o.totalReturn, 3)
    oosSh = [math]::Round([double]$o.sharpeRatio, 2)
    oosMDD = [math]::Round([double]$o.maxDrawdown, 3)
    oosTr = $o.totalTrades
  }
}

$U = "&universe=$Universe"
$rows = @()
$rows += Test-Cfg "L2S4 rb300 vs210 vt05" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.05"
$rows += Test-Cfg "L2S4 rb300 vs210 vt10" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.10"
$rows += Test-Cfg "L2S4 rb300 vs210 vt15" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.15"
$rows += Test-Cfg "L2S4 rb300 vs210 vt20" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.20"
$rows += Test-Cfg "L2S4 rb300 vs210 vt30" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=210&volTargetEnabled=true&volTargetAnnualized=0.30"
$rows += Test-Cfg "L2S4 rb300 vs105 vt15" "$U&carryWeight=0&rebalanceBars=300&longCount=2&shortCount=4&volScalingEnabled=true&volLookbackBars=105&volTargetEnabled=true&volTargetAnnualized=0.15"
$rows += Test-Cfg "L2S3 rb210 vs420 vt10" "$U&carryWeight=0&rebalanceBars=210&longCount=2&shortCount=3&volScalingEnabled=true&volLookbackBars=420&volTargetEnabled=true&volTargetAnnualized=0.10"
$rows += Test-Cfg "L2S3 rb210 vs420 vt15" "$U&carryWeight=0&rebalanceBars=210&longCount=2&shortCount=3&volScalingEnabled=true&volLookbackBars=420&volTargetEnabled=true&volTargetAnnualized=0.15"
$rows += Test-Cfg "L2S3 rb210 vs420 vt20" "$U&carryWeight=0&rebalanceBars=210&longCount=2&shortCount=3&volScalingEnabled=true&volLookbackBars=420&volTargetEnabled=true&volTargetAnnualized=0.20"

$rows | Sort-Object oosPF -Descending | Format-Table -AutoSize
$rows | Sort-Object oosPF -Descending | Export-Csv -Path "build/portfolio_voltarget_oos.csv" -NoTypeInformation