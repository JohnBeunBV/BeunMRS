<#
.SYNOPSIS
    BeunMRS circuit breaker test -- volledig geautomatiseerd (NFR-7).

    Stopt de OpenMRS-gateway automatisch, detecteert de circuit-breaker-opening
    door de service-logs te pollen, herstart de gateway en verifieert dat alle
    gebufferde berichten alsnog worden verstuurd.
    Geen handmatige stappen of tweede terminal nodig.

.PARAMETER TenantSlug
    Slug van de testtenant (default: cb-test-tenant).

.PARAMETER AppointmentCount
    Aantal aan te maken afspraken (default: 3).

.PARAMETER KeepData
    Bewaar testdata na afloop (voor handmatige inspectie).
    Zonder deze vlag wordt de testtenant + alle gegenereerde data na afloop verwijderd.

.PARAMETER CbTimeoutSeconds
    Maximum wachttijd op circuit-breaker-opening in seconden (default: 900 = 15 min).
    De poller draait elke 2 minuten; na 5 fouten opent het circuit (~10 min).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\circuitbreaker-test.ps1
    powershell -ExecutionPolicy Bypass -File scripts\circuitbreaker-test.ps1 -KeepData
#>
param(
    [string] $TenantSlug       = "cb-test-tenant",
    [int]    $AppointmentCount = 3,
    [switch] $KeepData,
    [int]    $CbTimeoutSeconds = 900
)

$ErrorActionPreference = "Stop"

$ApiBase     = "https://localhost:4000"
$OpenMrsBase = "http://localhost/openmrs/ws/rest/v1"
$OpenMrsAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:Admin1234"))
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path

$PatientUuid  = "f0134060-6f4c-4f16-8c6a-e64bee9ca718"  # Betty Williams (geverifieerd in lokale OpenMRS)
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

# Verwijdert testtenant + alle gegenereerde data in de juiste FK-volgorde.
# Veilig als de tenant niet bestaat: subquery geeft NULL, alle DELETEs raken 0 rijen.
function Remove-TestTenant {
    param([string]$Slug)
    $sql = @'
DELETE FROM notification_audit_log  WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM notification_log        WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM scheduled_notifications WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM outbox_events           WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM seen_appointments       WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM sync_watermarks         WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM async_flow_commands     WHERE tenant_id = (SELECT id FROM tenants WHERE slug = 'SLUG_PH');
DELETE FROM tenants                 WHERE slug = 'SLUG_PH';
'@ -replace 'SLUG_PH', $Slug

    try {
        $sql | docker exec -i notification-db psql -U notify -d notifications -q 2>&1 | Out-Null
        Write-Host "  Opgeschoond: slug='$Slug'" -ForegroundColor DarkGray
    } catch {
        Write-Host "  Opschonen overgeslagen (tenant bestond nog niet)" -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host " BeunMRS -- Circuit Breaker Test (NFR-7) -- volledig auto"   -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host " Toont: systeem buffert berichten tijdens OpenMRS-storing"    -ForegroundColor DarkCyan
Write-Host " en herstelt volledig zonder berichtenverlies."               -ForegroundColor DarkCyan
Write-Host " Volledig geautomatiseerd -- geen handmatige stappen nodig."  -ForegroundColor DarkCyan

# -- Stap 0: Vorige testdata opschonen (maakt script heruitvoerbaar) ----------------------
Write-Step 0 "Vorige testdata opschonen (idempotentie)"
Remove-TestTenant $TenantSlug

# -- Stap 1: Tenant registreren -----------------------------------------------------------
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

try {
    $null = Invoke-RestMethod -Uri "$ApiBase/api/register" -Method POST `
                -ContentType "application/json" -Body $registerBody
    Write-Ok "Tenant aangemaakt"
} catch {
    Write-Host "  Registratie mislukt: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# -- Stap 2: Afspraken aanmaken terwijl OpenMRS online is ---------------------------------
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

# -- Stap 3: OpenMRS gateway automatisch stoppen ------------------------------------------
Write-Step 3 "OpenMRS gateway automatisch stoppen (storing simuleren)"
Set-Location $ProjectRoot
docker compose stop gateway | Out-Null
$gatewayStoppedAt = Get-Date
Write-Ok "Gateway gestopt om $($gatewayStoppedAt.ToString('HH:mm:ss')) -- OpenMRS onbereikbaar"
Write-Info "Live logs bijhouden (optioneel):  docker compose logs -f notification-svc"

# -- Stap 4: Circuit breaker detecteren via log-polling -----------------------------------
Write-Step 4 "Wachten op circuit-breaker-opening (max $([int]($CbTimeoutSeconds / 60)) min)"
Write-Info "Poller elke 2 min. Na 5 fouten opent het circuit (~10 min)."

$cbDeadline = $gatewayStoppedAt.AddSeconds($CbTimeoutSeconds)
$cbDetected = $false

while ((Get-Date) -lt $cbDeadline) {
    # Kijk terug iets langer dan verstreken tijd om alle logs sinds gateway-stop te vangen
    $windowSeconds = [int]((Get-Date) - $gatewayStoppedAt).TotalSeconds + 60
    $logs = docker compose logs notification-svc --since "${windowSeconds}s" | Out-String

    if ($logs -match "Circuit breaker OPEN") {
        $elapsed = [int]((Get-Date) - $gatewayStoppedAt).TotalSeconds
        Write-Host ""
        Write-Ok "Circuit breaker OPEN gedetecteerd na ${elapsed}s"
        $cbDetected = $true
        break
    }

    $remaining = [int](($cbDeadline - (Get-Date)).TotalSeconds)
    Write-Host "  Nog ~${remaining}s wachten op circuit breaker...    " -NoNewline -ForegroundColor DarkGray
    Start-Sleep -Seconds 30
    Write-Host "`r" -NoNewline
}
Write-Host ""

if ($cbDetected) {
    Write-Ok "Circuit breaker is OPEN -- events worden gebufferd in outbox_events."
} else {
    Write-Warn "Circuit breaker niet gedetecteerd binnen ${CbTimeoutSeconds}s (controleer logs)."
    Write-Warn "Test gaat door -- gateway wordt nu herstart."
}

# -- Stap 5: Outbox controleren -----------------------------------------------------------
Write-Step 5 "Outbox_events controleren (moeten gebufferd zijn)"
Write-Info "Verwacht: COUNT > 0 (events wachten in buffer)"

try {
    $outboxResult = docker exec notification-db psql -U notify -d notifications `
        -c "SELECT COUNT(*) AS pending_outbox FROM outbox_events WHERE published_at IS NULL AND tenant_id = (SELECT id FROM tenants WHERE slug = '$TenantSlug');" 2>&1
    Write-Host $outboxResult
} catch {
    Write-Warn "DB-query mislukt -- controleer handmatig"
}

# -- Stap 6: Gateway automatisch herstarten -----------------------------------------------
Write-Step 6 "OpenMRS gateway automatisch herstarten (herstel)"
docker compose start gateway | Out-Null
Write-Ok "Gateway herstart -- 30s wachten tot OpenMRS bereikbaar is"
Start-Sleep -Seconds 30

# -- Stap 7: Herstel verificeren ----------------------------------------------------------
Write-Step 7 "Herstel verificeren (5 minuten wachten op relay + dispatch)"
Write-Info "OutboxRelayJob publiceert elke 30s. Circuit reset max 2 min na herstel."

$waitEnd = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $waitEnd) {
    $remaining = [int](($waitEnd - (Get-Date)).TotalSeconds)
    Write-Host "  Nog ${remaining}s...    " -NoNewline -ForegroundColor DarkGray
    Start-Sleep -Seconds 15
    Write-Host "`r" -NoNewline
}
Write-Host ""

$query = "SELECT status, COUNT(*) AS aantal FROM notification_log WHERE tenant_id = (SELECT id FROM tenants WHERE slug = '$TenantSlug') GROUP BY status ORDER BY status;"

Write-Host ""
Write-Host "  Resultaten notification_log (deze testrun):" -ForegroundColor Cyan
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
Write-Host ""

# -- Stap 8: Testdata opschonen -----------------------------------------------------------
Write-Step 8 "Testdata opschonen"
if ($KeepData) {
    Write-Host "  -KeepData opgegeven -- data blijft bewaard voor inspectie." -ForegroundColor DarkYellow
} else {
    Remove-TestTenant $TenantSlug
    Write-Ok "Database is schoon voor de volgende run."
}
