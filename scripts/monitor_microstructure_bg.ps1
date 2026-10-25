param(
  [int]$BotPid = 0,
  [int]$IntervalSec = 600,
  [int]$HangMinutes = 30,
  [string]$Container = "trading-bot-db",
  [string]$DbUser = "trader",
  [string]$DbName = "trading_bot",
  [string]$BotLog = "$env:TEMP\opencode\trading-bot-microstructure.log",
  [string]$OutLog = "$env:TEMP\opencode\microstructure_monitor.log"
)
$pollsPerHang = [math]::Max(1, [int]($HangMinutes * 60 / $IntervalSec))
$zeroStreak = 0
$lastCount = -1
$lastSample = Get-Date
$mskZone = [TimeZoneInfo]::FindSystemTimeZoneById('Russian Standard Time')
$sessionOpen = [TimeSpan]::new(6, 50, 0)
$sessionClose = [TimeSpan]::new(23, 50, 0)
while ($true) {
  $ts = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
  # Вне сессии сбор намеренно не пишет (гейт свежести REST-снимка):
  # отсутствие прироста ночью/в выходные — не зависание.
  $mskNow = [TimeZoneInfo]::ConvertTimeFromUtc((Get-Date).ToUniversalTime(), $mskZone)
  $marketOpen =
    $mskNow.DayOfWeek -ne [DayOfWeek]::Saturday -and
    $mskNow.DayOfWeek -ne [DayOfWeek]::Sunday -and
    $mskNow.TimeOfDay -ge $sessionOpen -and
    $mskNow.TimeOfDay -lt $sessionClose
  $alive = $false
  if ($BotPid -gt 0) {
    try { $alive = [bool](Get-Process -Id $BotPid -ErrorAction SilentlyContinue) } catch { $alive = $false }
  }
  $count = -1
  try {
    $c = & docker exec $Container psql -U $DbUser -d $DbName -tAc "SELECT count(*) FROM microstructure_snapshots;" 2>$null
    if ($c -match '(\d+)') { $count = [int]$Matches[1] }
  } catch { $count = -1 }
  $delta = 0
  if ($lastCount -ge 0 -and $count -ge 0) { $delta = $count - $lastCount }
  if ($count -ge 0 -and $delta -le 0) { $zeroStreak++ } elseif ($delta -gt 0) { $zeroStreak = 0 }
  if (-not $marketOpen) { $zeroStreak = 0 }
  $rssMb = 0; $cpuSec = 0
  if ($BotPid -gt 0) {
    try {
      $p = Get-Process -Id $BotPid -ErrorAction SilentlyContinue
      if ($p) {
        $rssMb = [math]::Round($p.WorkingSet64 / 1MB, 1)
        $cpuSec = [math]::Round($p.TotalProcessorTime.TotalSeconds, 0)
      }
    } catch {}
  }
  $errCount = 0
  try {
    if (Test-Path $BotLog) {
      $tail = Get-Content $BotLog -Tail 200 -ErrorAction SilentlyContinue
      $errCount = ($tail | Select-String -Pattern '"level":"ERROR"' -SimpleMatch).Count
    }
  } catch {}
  $line = "$ts alive=$alive pid=$BotPid rows=$count delta10m=$delta zeroStreak=$zeroStreak marketOpen=$marketOpen rssMb=$rssMb cpuSec=$cpuSec errorsInTail=$errCount"
  Add-Content -Path $OutLog -Value $line -Encoding UTF8
  if ($zeroStreak -ge $pollsPerHang) {
    Add-Content -Path $OutLog -Value "$ts [HANG DETECTED] rows не растут >= $HangMinutes мин — нужен ручной разбор" -Encoding UTF8
  }
  if (-not $alive -and $BotPid -gt 0) {
    Add-Content -Path $OutLog -Value "$ts [PROCESS GONE] pid=$BotPid" -Encoding UTF8
  }
  $lastCount = $count
  Start-Sleep -Seconds $IntervalSec
}
