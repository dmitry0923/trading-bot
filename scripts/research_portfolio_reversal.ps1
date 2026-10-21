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
    wfaRet = [math]::Round($a.totalReturn, 3)
    cons = $w.consistency
    rob = $w.robust
    wfaP = $a.probabilityOfNoEdge
    oosPF = [math]::Round([double]$o.profitFactor, 2)
    oosRet = [math]::Round([double]$o.totalReturn, 3)
    oosSh = [math]::Round([double]$o.sharpeRatio, 2)
    oosTr = $o.totalTrades
    oosP = $o.probabilityOfNoEdge
  }
}

$U = "&universe=$Universe&reversalEnabled=true&carryWeight=0"
$grid = @(
  @("rev lb29 rb29 L3S3", "$U&lookbackBars=29&rebalanceBars=29&longCount=3&shortCount=3"),
  @("rev lb58 rb29 L3S3", "$U&lookbackBars=58&rebalanceBars=29&longCount=3&shortCount=3"),
  @("rev lb58 rb58 L3S3", "$U&lookbackBars=58&rebalanceBars=58&longCount=3&shortCount=3"),
  @("rev lb116 rb58 L3S3", "$U&lookbackBars=116&rebalanceBars=58&longCount=3&shortCount=3"),
  @("rev lb116 rb116 L3S3", "$U&lookbackBars=116&rebalanceBars=116&longCount=3&shortCount=3"),
  @("rev lb232 rb58 L3S3", "$U&lookbackBars=232&rebalanceBars=58&longCount=3&shortCount=3"),
  @("rev lb116 rb58 L4S4", "$U&lookbackBars=116&rebalanceBars=58&longCount=4&shortCount=4"),
  @("rev lb58 rb29 L4S4", "$U&lookbackBars=58&rebalanceBars=29&longCount=4&shortCount=4"),
  @("rev lb58 rb58 L4S4", "$U&lookbackBars=58&rebalanceBars=58&longCount=4&shortCount=4"),
  @("rev lb29 rb29 L4S4", "$U&lookbackBars=29&rebalanceBars=29&longCount=4&shortCount=4")
)
$rows = @()
foreach ($g in $grid) {
  $r = Test-Cfg $g[0] $g[1]
  $rows += $r
  Write-Output ("DONE {0} oosPF={1} oosP={2} oosTr={3} oosRet={4}" -f $r.cfg, $r.oosPF, $r.oosP, $r.oosTr, $r.oosRet)
}

$rows | Sort-Object oosPF -Descending | Format-Table -AutoSize
$rows | Sort-Object oosPF -Descending | Export-Csv -Path "build/portfolio_reversal_oos.csv" -NoTypeInformation