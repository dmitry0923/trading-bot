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
    oosPF = [math]::Round([double]$o.profitFactor, 2)
    oosRet = [math]::Round([double]$o.totalReturn, 3)
    oosSh = [math]::Round([double]$o.sharpeRatio, 2)
    oosTr = $o.totalTrades
    oosP = $o.probabilityOfNoEdge
  }
}

$U = "&universe=$Universe&reversalEnabled=true"
$grid = @(
  @("rev29 carry1", "$U&lookbackBars=29&rebalanceBars=29&longCount=3&shortCount=3&carryWeight=1"),
  @("rev29 carry2", "$U&lookbackBars=29&rebalanceBars=29&longCount=3&shortCount=3&carryWeight=2"),
  @("rev29 vs", "$U&lookbackBars=29&rebalanceBars=29&longCount=3&shortCount=3&carryWeight=0&volScalingEnabled=true&volLookbackBars=210"),
  @("rev29 carry1 vs", "$U&lookbackBars=29&rebalanceBars=29&longCount=3&shortCount=3&carryWeight=1&volScalingEnabled=true&volLookbackBars=210")
)
$rows = @()
foreach ($g in $grid) {
  $r = Test-Cfg $g[0] $g[1]
  $rows += $r
  Write-Output ("DONE {0} oosPF={1} oosP={2} oosTr={3} oosRet={4}" -f $r.cfg, $r.oosPF, $r.oosP, $r.oosTr, $r.oosRet)
}
$rows | Sort-Object oosPF -Descending | Format-Table -AutoSize
$rows | Sort-Object oosPF -Descending | Export-Csv -Path "build/portfolio_reversal2_oos.csv" -NoTypeInformation