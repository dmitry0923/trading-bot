param(
  [string]$TickerCsv = "CNYRUBF,USDRUBF,EURRUBF,IMOEXF,GLDRUBF",
  [string]$From = "2024-10-01",
  [string]$Till = "2026-10-01",
  [string]$IntervalCsv = "60,24",
  [string]$OutDir = "data/candles",
  [int]$PageSize = 500,
  [int]$SleepMs = 200
)

$Tickers = $TickerCsv.Split(',') | Where-Object { $_ -ne "" }
$Intervals = $IntervalCsv.Split(',') | Where-Object { $_ -ne "" }

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls11 -bor [Net.SecurityProtocolType]::Tls
$headers = @{ "User-Agent" = "trading-bot-candles-loader/1.0" }
$dateFmt = "yyyy-MM-dd"

if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$tfName = @{ "60" = "HOUR_1"; "24" = "DAY_1" }

function Get-Json($url) {
  $last = $null
  for ($i = 1; $i -le 6; $i++) {
    try {
      return (Invoke-WebRequest -Uri $url -Headers $headers -TimeoutSec 120).Content | ConvertFrom-Json
    } catch {
      $last = $_
      Start-Sleep -Milliseconds (1500 * $i)
    }
  }
  throw $last
}

$summary = @()

foreach ($t in $Tickers) {
  # определяем доску: FORTS фьючерсы = RFUD, акции = TQBR. Все 5 тикеров — фьючерсы FORTS.
  $url = "https://iss.moex.com/iss/engines/futures/markets/forts/boards/RFUD/securities/$t/candles.json"
  foreach ($iv in $Intervals) {
    $name = if ($tfName.ContainsKey($iv)) { $tfName[$iv] } else { "int_$iv" }
    $fromFull = "$From 00:00:00"
    $tillDate = ([datetime]::ParseExact($Till, $dateFmt, $null))
    $tillFull = $tillDate.AddDays(-1).ToString($dateFmt) + " 23:59:59"

    $all = New-Object System.Collections.Generic.List[object]
    $header = $null
    $start = 0
    $done = $false
    while (-not $done) {
      try {
        $j = Get-Json "$url`?interval=$iv&from=$fromFull&until=$tillFull&start=$start&iss.meta=off"
      } catch {
        Write-Warning ("{0} {1}: page start={2} FAILED: {3}" -f $t, $name, $start, $_.Exception.Message)
        break
      }
      if (-not $j.candles) { break }
      $cols = @($j.candles.columns)
      if (-not $header) { $header = $cols }
      $rows = @($j.candles.data)
      if ($rows.Count -eq 0) { break }
      foreach ($r in $rows) { $all.Add($r) }
      $start += $rows.Count
      Write-Host ("  {0} {1}: +{2} = {3} rows (start={4})" -f $t, $name, $rows.Count, $all.Count, $start)
      if ($rows.Count -lt $PageSize) { $done = $true }
      if ($start -gt 300000) { Write-Warning "safety stop $t $name"; break }
      if ($SleepMs -gt 0) { Start-Sleep -Milliseconds $SleepMs }
    }

    $fname = Join-Path $OutDir ("{0}_{1}_{2}_{3}.csv" -f $t, $name, ($From -replace '-',''), ($Till -replace '-',''))
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine(($header -join ","))
    # MOEX ISS отдаёт числа с запятой как десятичным разделителем ("13,224").
    # Без нормализации CSV нечитаем (в строке 10 полей вместо 8).
    # Числовые колонки = все, кроме begin/end (текстовые даты).
    $textCols = @("begin", "end", "begins", "ends")
    $idx = @()
    for ($c = 0; $c -lt $header.Count; $c++) { if ($textCols -notcontains $header[$c].ToLower()) { $idx += $c } }
    foreach ($r in $all) {
      $vals = @()
      for ($c = 0; $c -lt $r.Count; $c++) {
        $v = [string]$r[$c]
        if ($idx -contains $c) { $v = $v.Replace(',', '.') }
        $vals += $v
      }
      [void]$sb.AppendLine(($vals -join ","))
    }
    [System.IO.File]::WriteAllText($fname, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

    $tsIdx = -1
    for ($c = 0; $c -lt $header.Count; $c++) { if ($header[$c] -eq "begin") { $tsIdx = $c } }
    $first = if ($all.Count -gt 0 -and $tsIdx -ge 0) { $all[0][$tsIdx] } else { "?" }
    $last = if ($all.Count -gt 0 -and $tsIdx -ge 0) { $all[$all.Count - 1][$tsIdx] } else { "?" }
    $summary += [pscustomobject]@{ Ticker = $t; TF = $name; Rows = $all.Count; First = $first; Last = $last; File = $fname }
    Write-Host ("SAVED {0} {1} -> {2} rows [{3} .. {4}]" -f $t, $name, $all.Count, $first, $last)
  }
}

Write-Host ""
Write-Host "=== SUMMARY ==="
$summary | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
