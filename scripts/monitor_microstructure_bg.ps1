param(
  [int]$Pid = $null,
  [int]$IntervalSec = 600
)
$ts = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
Add-Content -Path $env:TEMP\opencode\microstructure_monitor.log -Value "$ts test"
