# Wrapper: запускает research_wfa_four_cores.ps1 как ОТДЕЛЬНЫЙ процесс.
#
# Нужен потому, что Start-Job/Session не переживают вызовы инструмента:
# каждый вызов - новый процесс PowerShell, и job из предыдущего теряется.
# Start-Process создаёт реальный OS-процесс, живущий независимо.
param(
    [string[]]$ConfigCsv = @(),
    [string]$ConfigsFile = "",
    [switch]$SkipBaseline,
    [string]$OutCsv = "",
    [string]$StatusFile = "",
    [int]$Days = 730,
    [int]$Folds = 8
)

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutCsv) { $OutCsv = Join-Path $env:TEMP "wfa4-$stamp.csv" }
if (-not $StatusFile) { $StatusFile = Join-Path $env:TEMP "wfa4-$stamp.status" }
$log = Join-Path $env:TEMP "wfa4-$stamp.log"

$params = @{
    Days = $Days
    Folds = $Folds
    OutCsv = $OutCsv
    StatusFile = $StatusFile
}
if ($ConfigsFile) { $params.ConfigsFile = $ConfigsFile }
if ($ConfigCsv.Count -gt 0) { $params.ConfigCsv = $ConfigCsv }
if ($SkipBaseline) { $params.SkipBaseline = $true }

& (Join-Path $PSScriptRoot "research_wfa_four_cores.ps1") @params *>> $log
"EXIT=$LASTEXITCODE csv=$OutCsv log=$log" | Add-Content -LiteralPath $StatusFile -Encoding UTF8
