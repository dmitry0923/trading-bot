# Research-runner cross-sectional momentum + carry портфеля (docs/26).
# Логинится в API и последовательно прогоняет WFA-конфиги
#   baseline (carryWeight=0) и momentum+carry (carryWeight=1),
# сохраняя ответы в JSON. Foreground; прогон короткий (движок O(n) на выровненном юниверсе).
param(
    [string]$Base = "http://localhost:8080",
    [int]$Days = 730,
    [int]$Folds = 8,
    [double]$MomentumFloor = 0,
    [string]$OutDir = "build"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

function Invoke-PortfolioWfa([string]$name, [string]$query) {
    $url = "$Base/api/v1/portfolio/momentum/validate?days=$Days&folds=$Folds$query"
    Write-Output "[$(Get-Date -Format 'HH:mm:ss')] RUN $name -> $url"
    $out = Join-Path $OutDir "portfolio_$name.json"
    $resp = Invoke-RestMethod -Method Get -Uri $url -Headers $script:headers -TimeoutSec 21600
    $resp | ConvertTo-Json -Depth 12 | Set-Content $out
    $agg = $resp.aggregate
    Write-Output ("[$(Get-Date -Format 'HH:mm:ss')] DONE $name bars={0} folds={1} cons={2} robust={3} PF={4} ret={5} sharpe={6} trades={7} PnoEdge={8} verdict={9}" -f `
        $resp.bars, $resp.folds, $resp.consistency, $resp.robust, $agg.profitFactor, $agg.totalReturn, $agg.sharpeRatio, $agg.totalTrades, $agg.probabilityOfNoEdge, $resp.verdict)
    Write-Output "  -> $out"
}

$login = Invoke-RestMethod -Method Post -Uri "$Base/api/v1/auth/login" -ContentType "application/json" -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 30
$script:headers = @{ Authorization = "Bearer $($login.accessToken)" }
Write-Output "auth ok"

# baseline: чистый cross-sectional momentum
Invoke-PortfolioWfa "momentum_730d_f$Folds" ""
# momentum + carry (funding tilt lambda=1)
Invoke-PortfolioWfa "momentum_carry_730d_f$Folds" "&carryWeight=1.0"

Write-Output "ALL DONE"
