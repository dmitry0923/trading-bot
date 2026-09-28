# Heartbeat: раз в N секунд печатает состояние research-процесса WFA.
#
# Запускается отдельным процессом (Start-Process pwsh -File ...), потому что
# Start-Job не переживает вызовы инструмента. Каждая строка = одно состояние,
# поэтому по размеру файла видно, что процесс ЖИВ (а не завис).
param(
    [int]$WatchPid = 0,
    [int]$IntervalSec = 300,
    [int]$MaxMinutes = 240,
    [string]$StatusFile = "$env:TEMP\wfa4-smoke.status",
    [string]$OutFile = ""
)

if (-not $OutFile) {
    $OutFile = Join-Path $env:TEMP ("heartbeat-{0}.log" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
}
$deadline = (Get-Date).AddMinutes($MaxMinutes)
$start = Get-Date

while ((Get-Date) -lt $deadline) {
    $alive = $false
    $cpu = 0
    $rssMb = 0
    if ($WatchPid -gt 0) {
        $proc = Get-Process -Id $WatchPid -ErrorAction SilentlyContinue
        if ($proc) {
            $alive = $true
            $cpu = [math]::Round($proc.CPU, 1)
            $rssMb = [math]::Round($proc.WorkingSet64 / 1MB, 0)
        }
    }
    $last = (Get-Content -LiteralPath $StatusFile -ErrorAction SilentlyContinue |
        Where-Object { $_ -notmatch "^(start|csv):" } | Select-Object -Last 1)
    $lines = (Get-Content -LiteralPath $OutFile -ErrorAction SilentlyContinue | Measure-Object).Count
    $mins = [math]::Round(((Get-Date) - $start).TotalMinutes, 1)
    ("[{0}] t={1}min pid={2} alive={3} cpu={4}s rss={5}MB hb={6} | {7}" -f `
        (Get-Date -Format "HH:mm:ss"), $mins, $WatchPid, $alive, $cpu, $rssMb, $lines, $last) |
        Tee-Object -FilePath $OutFile -Append
    if (-not $alive -and $WatchPid -gt 0) { break }
    Start-Sleep -Seconds $IntervalSec
}
"HEARTBEAT-END" | Add-Content -LiteralPath $OutFile -Encoding UTF8
