<#
.SYNOPSIS
    BeunMRS circuit breaker test -- toont dat het systeem stabiel blijft
    tijdens en na een OpenMRS-storing (NFR-7).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\circuitbreaker-test.ps1
#>
param(
    [string] $TenantSlug       = "cb-test-tenant",
    [int]    $AppointmentCount = 3
)

$ErrorActionPreference = "Stop"

$ApiBase     = "https://localhost:4000"
$OpenMrsBase = "http://localhost/openmrs/ws/rest/v1"
$OpenMrsAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:Admin1234"))

$PatientUuid  = "02aeb913-8c55-461b-99a6-3270387dbb2e"
$ServiceUuid  = "7ba3aa21-cc56-47ca-bb4d-a60549f666c0"
$LocationUuid = "ba685651-ed3b-4e63-9b35-78893060758a"

Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCerts2 : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert,
        WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCerts2

function Write-Step($n, $msg) { Write-Host ""; Write-Host "[$n] $msg" -ForegroundColor Yellow }
function Write-Ok($msg)       { Write-Host "  OK  $msg" -ForegroundColor Green }
function Write-Info($msg)     { Write-Host "  ... $msg" -ForegroundColor DarkGray }
function Write-Warn($msg)     { Write-Host "  !   $msg" -ForegroundColor DarkYellow }

Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host " BeunMRS -- Circuit Breaker Test (NFR-7)"                     -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host " Toont: systeem buffert berichten tijdens OpenMRS-storing"    -ForegroundColor DarkCyan
Write-Host " en herstelt volledig zonder berichtenverlies."               -ForegroundColor DarkCyan

# Stap 1: Tenant registreren
Write-Step 1 "Tenant '$TenantSlug' registreren"

$registerBody = @{
    slug            = $TenantSlug
    displayName     = "Circuit Breaker Test"
    openmrsHost     = "http://gateway/openmrs"
    openmrsUser     = "admin"
    openmrsPassword = "Admin1234"
    providerName    = "SwiftSend"
    providerApiKey  = "sk-swiftsend-demo"
    timezone        = "Europe/Amsterdam"
} | ConvertTo-Json

$ApiKey = $null
try {
    $reg    = Invoke-RestMethod -Uri "$ApiBase/api/register" -Method POST `
                -ContentType "application/json" -Body $registerBody
    $ApiKey = $reg.apiKey
    Write-Ok "Tenant aangemaakt"
} catch {
    Write-Warn "Tenant bestaat al of registratie mislukt."
    $ApiKey = Read-Host "  Voer de API key in voor '$TenantSlug'"
}

# Stap 2: Afspraken aanmaken terwijl OpenMRS online is
Write-Step 2 "$AppointmentCount afspraken aanmaken (OpenMRS online)"

$baseTime = (Get-Date).AddHours(25).ToUniversalTime()
$uuids    = @()

for ($i = 1; $i -le $AppointmentCount; $i++) {
    $start = $baseTime.AddMinutes($i * 15)
    $end   = $start.AddMinutes(15)
    $body  = @{
        patientUuid     = $PatientUuid
        serviceUuid     = $ServiceUuid
        locationUuid    = $LocationUuid
        startDateTime   = $start.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        endDateTime     = $end.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        appointmentKind = "Scheduled"
        comments        = "CB-test afspraak $i"
    } | ConvertTo-Json

    try {
        $resp   = Invoke-RestMethod -Uri "$OpenMrsBase/appointment" -Method POST `
                    -ContentType "application/json" `
                    -Headers @{ Authorization = "Basic $OpenMrsAuth" } `
                    -Body $body
        $uuids += $resp.uuid
        Write-Info "Afspraak $i aangemaakt: $($resp.uuid)"
    } catch {
        Write-Warn "Afspraak $i mislukt: $($_.Exception.Message)"
    }
}
Write-Ok "$($uuids.Count) afspraken aangemaakt"

# Stap 3: OpenMRS stoppen
Write-Step 3 "OpenMRS gateway stoppen (storing simuleren)"
Write-Host ""
Write-Host "  Voer uit in een APART terminalvenster:" -ForegroundColor White
Write-Host ""
Write-Host "      docker compose stop gateway" -ForegroundColor White -BackgroundColor DarkRed
Write-Host ""
Write-Host "  Druk Enter als de gateway gestopt is..." -ForegroundColor DarkYellow
$null = Read-Host

Write-Ok "Gateway gestopt -- OpenMRS onbereikbaar"

# Stap 4: Circuit breaker observeren
Write-Step 4 "Circuit breaker observeren"
Write-Info "Pollerinterval = 2 min. Na 5 mislukkingen opent het circuit."
Write-Info "Controleer logs in een apart venster:"
Write-Host ""
Write-Host "  docker compose logs -f notification-svc" -ForegroundColor White
Write-Host ""
Write-Host "  Verwachte log-regels:"
Write-Host "    '[Poller] Fout ...' (5x)"                          -ForegroundColor DarkGray
Write-Host "    '[Poller] Circuit breaker OPEN voor tenant ...'"   -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Druk Enter als je circuit-breaker-logs ziet (of na 12 min)..." -ForegroundColor DarkYellow
$null = Read-Host

# Stap 5: Outbox controleren
Write-Step 5 "Outbox_events controleren (moeten gebufferd zijn)"
Write-Host ""
Write-Host "  Verwacht: COUNT > 0 (events wachten in buffer)"
Write-Host ""

try {
    $outboxResult = docker exec notification-db psql -U notify -d notifications `
        -c "SELECT COUNT(*) AS pending_outbox FROM outbox_events WHERE published_at IS NULL;" 2>&1
    Write-Host $outboxResult
} catch {
    Write-Warn "DB-query mislukt -- voer handmatig uit"
}

Write-Host "  Druk Enter om verder te gaan..." -ForegroundColor DarkYellow
$null = Read-Host

# Stap 6: Gateway herstarten
Write-Step 6 "OpenMRS gateway herstarten (herstel)"
Write-Host ""
Write-Host "  Voer uit in het andere venster:" -ForegroundColor White
Write-Host ""
Write-Host "      docker compose start gateway" -ForegroundColor White -BackgroundColor DarkGreen
Write-Host ""
Write-Host "  Druk Enter als de gateway herstart is..." -ForegroundColor DarkYellow
$null = Read-Host

Write-Ok "Gateway herstart"

# Stap 7: Herstel verificeren
Write-Step 7 "Herstel verificeren (wacht 5 minuten op relay + dispatch)"
Write-Info "OutboxRelayJob publiceert elke 30s. Circuit reset max 2 min na herstel."
Start-Sleep -Seconds 300

$query = "SELECT status, COUNT(*) AS aantal FROM notification_log WHERE created_at > NOW() - INTERVAL '60 minutes' GROUP BY status ORDER BY status;"

Write-Host ""
Write-Host "  Resultaten notification_log (laatste 60 min):" -ForegroundColor Cyan
try {
    $result = docker exec notification-db psql -U notify -d notifications -c $query 2>&1
    Write-Host $result
} catch {
    Write-Warn "DB-query mislukt"
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host " CONCLUSIE"                                                    -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "  Verwacht resultaat:"
Write-Host "  - status='sent' aanwezig na herstel"        -ForegroundColor Green
Write-Host "  - geen 'permanently_failed' entries"        -ForegroundColor Green
Write-Host "  - outbox_events.published_at gevuld"        -ForegroundColor Green
Write-Host ""
Write-Host "  Bewijs voor NFR-7: geen berichtenverlies tijdens storing."
Write-Host "  Noteer bevindingen in docs/PERFORMANCE-RAPPORT.md"          -ForegroundColor DarkCyan
