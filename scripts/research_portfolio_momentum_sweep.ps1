# Sweep устойчивости cross-sectional momentum+carry (docs/26).
# Прогоняет WFA по сетке carryWeight / rebalanceBars / lookbackBars и печатает сводку.
# Плато (широкий диапазон положительных) отличаем от острого пика (selection).
param(
    [string]$Base = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [string]$OutDir = "build"
)

$ErrorActionPreference = "Stop"
$sweep = Join-Path $OutDir "portfolio_sweep"
if (-not (Test-Path $sweep)) { New-Item -ItemType Directory -Path $sweep | Out-Null }

$login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Write-Output "auth ok"

$configs = @(
    @{ name = "cw0.5";  q = "&carryWeight=0.5" },
    @{ name = "cw0.75"; q = "&carryWeight=0.75" },
    @{ name = "cw1.0";  q = "&carryWeight=1.0" },
    @{ name = "cw1.25"; q = "&carryWeight=1.25" },
    @{ name = "cw1.5";  q = "&carryWeight=1.5" },
    @{ name = "cw2.0";  q = "&carryWeight=2.0" },
    @{ name = "rebal210"; q = "&carryWeight=1.0&rebalanceBars=210" },
    @{ name = "rebal840"; q = "&carryWeight=1.0&rebalanceBars=840" },
    @{ name = "lookback840"; q = "&carryWeight=1.0&lookbackBars=840" },
    @{ name = "lookback3360"; q = "&carryWeight=1.0&lookbackBars=3360" }
)

$rows = @()
foreach ($cfg in $configs) {
    $url = "$Base/api/v1/portfolio/momentum/validate?days=$Days&folds=$Folds$($cfg.q)"
    try {
        $r = Invoke-RestMethod -Method Get -Uri $url -Headers $headers -TimeoutSec 21600
        $r | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $sweep "portfolio_$($cfg.name).json")
        $a = $r.aggregate
        $row = [pscustomobject]@{
            cfg = $cfg.name; bars = $r.bars; cons = $r.consistency; robust = $r.robust
            PF = [math]::Round($a.profitFactor, 3); ret = [math]::Round($a.totalReturn, 4)
            sharpe = [math]::Round($a.sharpeRatio, 3); trades = $a.totalTrades
            dd = [math]::Round($a.maxDrawdown, 3); PnoEdge = $a.probabilityOfNoEdge
        }
        $rows += $row
        Write-Output ("{0,-12} cons={1,-6} robust={2,-6} PF={3,-6} ret={4,-8} sharpe={5,-7} trades={6,-4} dd={7,-6} PnoEdge={8}" -f `
            $row.cfg, $row.cons, $row.robust, $row.PF, $row.ret, $row.sharpe, $row.trades, $row.dd, $row.PnoEdge)
    } catch {
        Write-Output "ERR $($cfg.name): $($_.Exception.Message)"
    }
}
$rows | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $sweep "summary.json")
Write-Output "ALL DONE"
