param(
  [int]$Days = 730,
  [double]$Oos = 0.30,
  [string]$Universe = "CNYRUBF,USDRUBF,EURRUBF,GLDRUBF,IMOEXF",
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
    oosTr = $o.totalTrades
    oosP = [math]::Round([double]$o.probabilityOfNoEdge, 3)
  }
}

$c = "&universe=$Universe&momentumWeight=0&carryWeight=1&lookbackBars=1"
$grid = @(
  @("L0S3 rb420 cl5",  "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=5"),
  @("L0S2 rb420 cl5",  "$c&rebalanceBars=420&longCount=0&shortCount=2&carryLookbackDays=5"),
  @("L0S4 rb420 cl5",  "$c&rebalanceBars=420&longCount=0&shortCount=4&carryLookbackDays=5"),
  @("L0S3 rb420 cl1",  "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=1"),
  @("L0S3 rb420 cl3",  "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=3"),
  @("L0S3 rb420 cl10", "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=10"),
  @("L0S3 rb420 cl20", "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=20"),
  @("L0S3 rb210 cl5",  "$c&rebalanceBars=210&longCount=0&shortCount=3&carryLookbackDays=5"),
  @("L0S3 rb630 cl5",  "$c&rebalanceBars=630&longCount=0&shortCount=3&carryLookbackDays=5"),
  @("L0S3 vs rb420",   "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=5&volScalingEnabled=true&volLookbackBars=420"),
  @("L0S3 rv rb420",   "$c&rebalanceBars=420&longCount=0&shortCount=3&carryLookbackDays=5&carryWeight=-1"),
  @("L1S3 rb420 cl5",  "$c&rebalanceBars=420&longCount=1&shortCount=3&carryLookbackDays=5"),
  @("L1S3 rb420 cl10", "$c&rebalanceBars=420&longCount=1&shortCount=3&carryLookbackDays=10"),
  @("L0S3 s4 rb420",   "$c&rebalanceBars=420&longCount=0&shortCount=4&carryLookbackDays=10")
)
$rows = @()
foreach ($g in $grid) {
  $r = Test-Cfg $g[0] $g[1]
  $rows += $r
  Write-Output ("DONE {0} oosPF={1} oosP={2} oosTr={3} oosRet={4}" -f $r.cfg, $r.oosPF, $r.oosP, $r.oosTr, $r.oosRet)
}
$rows | Sort-Object oosPF -Descending | Format-Table -AutoSize
$rows | Sort-Object oosPF -Descending | Export-Csv -Path "build/portfolio_carry2_oos.csv" -NoTypeInformation
Write-Output "ALL DONE"