# Метрики forward paper momentum. Только закрытые сделки: это не mark-to-market equity.
function Get-PaperMomentumStats {
    param(
        [AllowEmptyCollection()]
        [object[]]$Trades,
        [double]$PaperCapitalRub
    )
    if ($PaperCapitalRub -le 0) {
        throw "PaperCapitalRub must be positive, got $PaperCapitalRub."
    }

    $arr = @($Trades)
    if ($arr.Count -eq 0) {
        return [pscustomobject]@{
            trades = 0
            net = 0.0
            pf = 0.0
            winRate = 0.0
            avgNet = 0.0
            medianNet = 0.0
            mddRub = 0.0
            mddPct = 0.0
            mddMethod = 'daily-closed-trades-by-exit-date'
        }
    }

    $pnls = [double[]]::new($arr.Count)
    $sumWin = 0.0
    $sumLoss = 0.0
    $wins = 0
    $dailyPnl = @{}
    for ($i = 0; $i -lt $arr.Count; $i++) {
        $v = [double]$arr[$i].net
        $pnls[$i] = $v
        if ($v -gt 0) { $sumWin += $v; $wins++ } else { $sumLoss += [math]::Abs($v) }
        $exitDate = [string]$arr[$i].exitDate
        if ([string]::IsNullOrWhiteSpace($exitDate)) {
            throw 'Every closed trade must have exitDate for daily drawdown aggregation.'
        }
        if (-not $dailyPnl.ContainsKey($exitDate)) { $dailyPnl[$exitDate] = 0.0 }
        $dailyPnl[$exitDate] += $v
    }

    $pf = if ($sumLoss -gt 0) { $sumWin / $sumLoss } else { if ($sumWin -gt 0) { 999.0 } else { 0.0 } }
    $net = 0.0
    foreach ($x in $pnls) { $net += $x }
    $avg = $net / $arr.Count
    $sortedPnl = @($pnls | Sort-Object)
    $mid = [int][math]::Floor($sortedPnl.Count / 2)
    $median = if ($sortedPnl.Count % 2 -eq 1) {
        $sortedPnl[$mid]
    } else {
        ($sortedPnl[$mid - 1] + $sortedPnl[$mid]) / 2.0
    }

    $cum = 0.0
    $peak = 0.0
    $mdd = 0.0
    foreach ($date in @($dailyPnl.Keys | Sort-Object)) {
        $cum += [double]$dailyPnl[$date]
        if ($cum -gt $peak) { $peak = $cum }
        $dd = $peak - $cum
        if ($dd -gt $mdd) { $mdd = $dd }
    }

    return [pscustomobject]@{
        trades = $arr.Count
        net = [math]::Round($net, 2)
        pf = [math]::Round($pf, 3)
        winRate = [math]::Round(100.0 * $wins / $arr.Count, 1)
        avgNet = [math]::Round($avg, 2)
        medianNet = [math]::Round($median, 2)
        mddRub = [math]::Round($mdd, 2)
        mddPct = [math]::Round(100.0 * $mdd / $PaperCapitalRub, 2)
        mddMethod = 'daily-closed-trades-by-exit-date'
    }
}
