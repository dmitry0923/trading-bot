# Монитор гейта: каждые 300с пишет статус в artifacts/gate_monitor.log.
# Завершается сам, когда появился JSON-результат или по истечении MaxHours.
param(
    [string]$Name = "baseline_730d_gates",
    [string]$OutDir = "artifacts",
    [int]$IntervalSec = 300,
    [int]$MaxHours = 6
)
$ErrorActionPreference = "SilentlyContinue"
$json = Join-Path $OutDir "$Name.json"
$hb = Join-Path $OutDir "$Name.heartbeat"
$status = Join-Path $OutDir "$Name.status.log"
$mon = Join-Path $OutDir "gate_monitor.log"
$deadline = (Get-Date).AddHours($MaxHours)

function Line([string]$msg) {
    $l = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Add-Content -Path $mon -Value $l
    Write-Output $l
}

while ((Get-Date) -lt $deadline) {
    if (Test-Path $json) {
        Line "DONE: $json exists ($((Get-Item $json).Length) bytes)"
        exit 0
    }
    $hbAge = "?"
    if (Test-Path $hb) {
        $raw = (Get-Content $hb -Raw)
        $t = [regex]::Match($raw, 't=(\d+)').Groups[1].Value -as [long]
        if ($t) { $hbAge = "$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds() - $t)s" }
    }
    $java = Get-Process java -ErrorAction SilentlyContinue | Sort-Object WorkingSet64 -Descending | Select-Object -First 1
    $srv = "java=?"
    if ($java) { $srv = "pid=$($java.Id) rss=$([math]::Round($java.WorkingSet64/1MB))MB cpu=$([math]::Round($java.CPU))s" }
    $lastBacktest = ""
    $bl = Select-String -Path build\server_gate.log -Pattern 'Backtest CNYRUBF' | Select-Object -Last 1
    if ($bl) {
        try { $lastBacktest = ($bl.Line | ConvertFrom-Json).message.Substring(0, 90) } catch { $lastBacktest = "?" }
    }
    $lastStatus = ""
    if (Test-Path $status) { $lastStatus = (Get-Content $status -Tail 1) }
    Line "hb_age=$hbAge $srv | $lastStatus | $lastBacktest"
    Start-Sleep -Seconds $IntervalSec
}
Line "DEADLINE reached, no json"
exit 1
