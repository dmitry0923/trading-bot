# Запуск: pwsh -NoProfile -File scripts/paper_momentum_metrics_test.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'paper_momentum_metrics.ps1')

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "$Message Expected=$Expected, actual=$Actual."
    }
}

$day1a = [pscustomobject]@{ exitDate = '2026-11-05'; net = 1000.0 }
$day1b = [pscustomobject]@{ exitDate = '2026-11-05'; net = -35000.0 }
$day2 = [pscustomobject]@{ exitDate = '2026-11-06'; net = -1000.0 }
$ordered = @($day1a, $day1b, $day2)
$reversed = @($day1b, $day1a, $day2)

$stats50k = Get-PaperMomentumStats -Trades $ordered -PaperCapitalRub 50000.0
Assert-Equal $stats50k.mddRub 35000.0 'Daily aggregation must not depend on same-day trade order.'
Assert-Equal $stats50k.mddPct 70.0 '35000 RUB is 70 percent of 50000 RUB.'

$stats50kReversed = Get-PaperMomentumStats -Trades $reversed -PaperCapitalRub 50000.0
Assert-Equal $stats50kReversed.mddRub $stats50k.mddRub 'Reversing same-day rows changed mddRub.'
Assert-Equal $stats50kReversed.mddPct $stats50k.mddPct 'Reversing same-day rows changed mddPct.'

$stats100k = Get-PaperMomentumStats -Trades $ordered -PaperCapitalRub 100000.0
Assert-Equal $stats100k.mddPct 35.0 '35000 RUB is 35 percent of 100000 RUB.'

$empty = Get-PaperMomentumStats -Trades @() -PaperCapitalRub 100000.0
Assert-Equal $empty.mddRub 0.0 'Empty state mddRub.'
Assert-Equal $empty.mddPct 0.0 'Empty state mddPct.'

$invalidRejected = $false
try {
    Get-PaperMomentumStats -Trades @() -PaperCapitalRub 0.0 | Out-Null
} catch {
    $invalidRejected = $true
}
Assert-Equal $invalidRejected $true 'Non-positive paper capital must fail closed.'

Write-Host 'paper_momentum_metrics_test: 10 assertions passed.' -ForegroundColor Green
