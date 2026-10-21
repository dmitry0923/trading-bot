<#
.SYNOPSIS
  Собирает JVM-truststore с цепочкой сертификатов www.cbr.ru (источник ставок ЦБ РФ).

.DESCRIPTION
  Зачем: сервер www.cbr.ru отдаёт ТОЛЬКО листовой сертификат (CN=*.cbr.ru), без промежуточного.
  Windows доводит цепочку сам (AIA-дозагрузка из хранилища ОС), а JVM этого не делает и
  в `cacerts` нет ни промежуточного `HARICA DV TLS RSA`, ни корневого
  `HARICA TLS RSA Root CA 2021` (в JDK есть только HARICA-корни 2015) → при обращении
  WebClient к ЦБ получаем `PKIX path building failed: unable to find valid certification path`,
  `CbrRatesProvider` fail-soft отдаёт пустой список, а `/api/v1/macro/rates-history` возвращает
  `loaded: 0`. Скрипт берёт реальную цепочку из TLS-рукопожатия (не хранит сертификаты в репозитории)
  и импортирует все элементы кроме листового в КОПИЮ `$JAVA_HOME/lib/security/cacerts`,
  которую приложение подхватывает через `-Djavax.net.ssl.trustStore`.

  Пример:
    .\scripts\make_cbr_truststore.ps1
    java "-Djavax.net.ssl.trustStore=<abs path>" -Djavax.net.ssl.trustStorePassword=changeit `
         -jar build\libs\trading-bot-2.0.0.jar

  Альтернатива без флагов JVM — импортировать сертификаты прямо в cacerts (нужны права
  администратора на `C:\Program Files\Amazon Corretto\...`).
#>
[CmdletBinding()]
param(
    # Внимание: имя $Host/$PID в PowerShell — read-only авто-переменные, поэтому $Domain.
    [string]$Domain = 'www.cbr.ru',
    [string]$OutFile = 'build\cbr-certs\cbr-truststore.jks',
    [string]$StorePass = 'changeit'
)

$ErrorActionPreference = 'Stop'

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME не задан — не могу найти cacerts/keytool' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
$cacerts = Join-Path $env:JAVA_HOME 'lib\security\cacerts'
if (-not (Test-Path $keytool)) { throw "keytool не найден: $keytool" }
if (-not (Test-Path $cacerts)) { throw "cacerts не найден: $cacerts" }

# 1. Реальная цепочка из TLS-рукопожатия (leaf + intermediate + root).
$tcp = New-Object System.Net.Sockets.TcpClient($Domain, 443)
$ssl = New-Object System.Net.Security.SslStream($tcp.GetStream(), $false, ({ $true }))
$ssl.AuthenticateAsClient($Domain)
$leaf = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($ssl.RemoteCertificate)
$chain = New-Object System.Security.Cryptography.X509Certificates.X509Chain
$chain.ChainPolicy.RevocationMode = 'NoCheck'
if (-not $chain.Build($leaf)) { throw "не удалось построить цепочку для $Domain" }
$ssl.Dispose(); $tcp.Dispose()

$elements = @($chain.ChainElements)
Write-Host "цепочка $Domain ($($elements.Count) элемента):"
for ($i = 0; $i -lt $elements.Count; $i++) {
    Write-Host ("  [$i] {0}" -f $elements[$i].Certificate.Subject)
}

# 2. Копия cacerts + импорт всех не-листовых элементов (именно их не хватает JVM).
$certDir = Split-Path -Parent $OutFile
if ($certDir) { New-Item -ItemType Directory -Force -Path $certDir | Out-Null }
$outAbs = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutFile))
Copy-Item $cacerts $outAbs -Force

for ($i = 1; $i -lt $elements.Count; $i++) {
    $cert = $elements[$i].Certificate
    $name = if ($cert.Subject -match 'CN=([^,]+)') { $Matches[1].Trim() } else { "cert$i" }
    $alias = ($name -replace '[^A-Za-z0-9\-_]', '-')
    $pem = Join-Path $certDir "$alias.pem"
    $body = "-----BEGIN CERTIFICATE-----`r`n" + `
        [Convert]::ToBase64String($cert.RawData, 'InsertLineBreaks') + `
        "`r`n-----END CERTIFICATE-----`r`n"
    Set-Content -Path $pem -Value $body -Encoding ascii
    & $keytool -importcert -noprompt -alias $alias -file $pem -keystore $outAbs -storepass $StorePass | Out-Host
}

Write-Host ''
Write-Host "truststore: $outAbs"
Write-Host 'флаги запуска приложения:'
Write-Host "  -Djavax.net.ssl.trustStore=$outAbs -Djavax.net.ssl.trustStorePassword=$StorePass"
