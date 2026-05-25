<#
.SYNOPSIS
    BeunMRS loadtest -- maakt N afspraken aan en meet throughput + end-to-end latency.

.PARAMETER Count
    Aantal afspraken (default: scenario-afhankelijk)

.PARAMETER Scenario
    "baseline" | "load" | "stress"

.PARAMETER WaitSeconds
    Seconden wachten na aanmaken afspraken (default: 180)

.PARAMETER KeepData
    Bewaar testdata na afloop (voor handmatige inspectie in Grafana/DB).
    Zonder deze vlag wordt de testtenant + alle gegenereerde data na afloop verwijderd.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\loadtest.ps1 -Scenario baseline
    powershell -ExecutionPolicy Bypass -File scripts\loadtest.ps1 -Scenario load
    powershell -ExecutionPolicy Bypass -File scripts\loadtest.ps1 -Scenario stress
    powershell -ExecutionPolicy Bypass -File scripts\loadtest.ps1 -Scenario load -KeepData
#>
param(
    [int]    $Count        = 0,
    [string] $Scenario     = "load",
    [string] $TenantSlug   = "loadtest-swiftsend",
    [string] $ProviderName = "SwiftSend",
    [int]    $WaitSeconds  = 180,
    [switch] $KeepData
)

$ErrorActionPreference = "Stop"

$ApiBase     = "https://localhost:4000"
$OpenMrsBase = "http://localhost/openmrs/ws/rest/v1"
$OpenMrsAuth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:Admin1234"))

$PatientUuid  = "f0134060-6f4c-4f16-8c6a-e64bee9ca718"  # Betty Williams (geverifieerd in lokale OpenMRS)
$ServiceUuid  = "7ba3aa21-cc56-47ca-bb4d-a60549f666c0"
$LocationUuid = "ba685651-ed3b-4e63-9b35-78893060758a"

if ($Count -eq 0) {
    $Count = switch ($Scenario) {
        "baseline" { 1 }
        "load"     { 20 }
        "stress"   { 50 }
        default    { 20 }
    }
}

# Negeer self-signed cert
Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCerts : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert,
        WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCerts

# ── Hulpfunctie: verwijder testtenant + alle gegenereerde data ─────────────────
# Verwijdert in de juiste volgorde (kindrijen voor ouderrij) omdat de FK's geen
# ON DELETE CASCADE hebben. Veilig om aan te roepen als de tenant niet bestaat:
# de subquery geeft dan NULL terug en elke DELETE verwijdert 0 rijen.
function Remove-TestTenant {
    param([string]$Slug)
    # Gebruik single-quoted here-string zodat $ niet door PowerShell wordt uitgebreid,
    # vervang daarna de placeholder met de echte slug.
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
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " BeunMRS Loadtest -- scenario: $Scenario ($Count afspraken)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# ── Stap 0: Vorige testdata opschonen (maakt script heruitvoerbaar) ────────────
Write-Host "`n[0/5] Vorige testdata opschonen..." -ForegroundColor Yellow
Remove-TestTenant $TenantSlug

# ── Stap 1: Tenant registreren ─────────────────────────────────────────────────
Write-Host "`n[1/5] Tenant registreren..." -ForegroundColor Yellow

$registerBody = @{
    slug            = $TenantSlug
    displayName     = "Loadtest $ProviderName"
    openmrsHost     = "http://gateway/openmrs"
    openmrsUser     = "admin"
    openmrsPassword = "Admin1234"
    providerName    = $ProviderName
    providerApiKey  = "sk-swiftsend-demo"
    timezone        = "Europe/Amsterdam"
} | ConvertTo-Json

try {
    $reg = Invoke-RestMethod -Uri "$ApiBase/api/register" -Method POST `
               -ContentType "application/json" -Body $registerBody
    Write-Host "  Tenant aangemaakt: $TenantSlug" -ForegroundColor Green
    Write-Host "  API key: $($reg.apiKey.Substring(0,16))..." -ForegroundColor DarkGray
} catch {
    Write-Host "  Registratie mislukt: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ── Stap 2: Afspraken aanmaken ─────────────────────────────────────────────────
Write-Host "`n[2/5] $Count afspra(a)k(en) aanmaken in OpenMRS..." -ForegroundColor Yellow

$startTime = Get-Date
$uuids     = @()
$baseTime  = (Get-Date).AddHours(25).ToUniversalTime()

for ($i = 1; $i -le $Count; $i++) {
    $apptStart = $baseTime.AddMinutes($i * 30)
    $apptEnd   = $apptStart.AddMinutes(30)

    $body = @{
        patientUuid     = $PatientUuid
        serviceUuid     = $ServiceUuid
        locationUuid    = $LocationUuid
        startDateTime   = $apptStart.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        endDateTime     = $apptEnd.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        appointmentKind = "Scheduled"
        comments        = "Loadtest afspraak $i van $Count -- scenario $Scenario"
    } | ConvertTo-Json

    try {
        $resp  = Invoke-RestMethod -Uri "$OpenMrsBase/appointment" -Method POST `
                     -ContentType "application/json" `
                     -Headers @{ Authorization = "Basic $OpenMrsAuth" } `
                     -Body $body
        $uuids += $resp.uuid
        if ($i % 10 -eq 0 -or $i -eq $Count) {
            Write-Host "  $i/$Count aangemaakt" -ForegroundColor DarkGray
        }
    } catch {
        Write-Host "  WAARSCHUWING: afspraak $i mislukt -- $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}

$createDuration = (Get-Date) - $startTime
Write-Host "  Aanmaken klaar in $([math]::Round($createDuration.TotalSeconds, 1))s" -ForegroundColor Green

# ── Stap 3: Wachten op verwerking ─────────────────────────────────────────────
Write-Host "`n[3/5] Wachten $WaitSeconds seconden op poller + dispatch..." -ForegroundColor Yellow
Write-Host "  Live monitoring: http://localhost:3000/d/beunmrs-perf" -ForegroundColor DarkCyan

$waitEnd = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $waitEnd) {
    $remaining = [int](($waitEnd - (Get-Date)).TotalSeconds)
    Write-Host "  Nog $remaining sec...    " -NoNewline
    Start-Sleep -Seconds 15
    Write-Host "`r" -NoNewline
}
Write-Host ""

# ── Stap 4: Resultaten ophalen (gefilterd op déze testrun) ────────────────────
Write-Host "`n[4/5] Resultaten uit notification_log (alleen deze testrun)..." -ForegroundColor Yellow

$query = "SELECT status, channel AS provider, COUNT(*) AS aantal, ROUND(AVG(EXTRACT(EPOCH FROM (sent_at - created_at)))::numeric,2) AS gem_sec, ROUND(MIN(EXTRACT(EPOCH FROM (sent_at - created_at)))::numeric,2) AS min_sec, ROUND(MAX(EXTRACT(EPOCH FROM (sent_at - created_at)))::numeric,2) AS max_sec FROM notification_log WHERE tenant_id = (SELECT id FROM tenants WHERE slug = '$TenantSlug') GROUP BY status, channel ORDER BY status, channel;"

try {
    $result = docker exec notification-db psql -U notify -d notifications -c $query 2>&1
    Write-Host ""
    Write-Host $result
} catch {
    Write-Host "  DB-query mislukt: $($_.Exception.Message)" -ForegroundColor DarkYellow
}

$totalDuration = (Get-Date) - $startTime

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " SAMENVATTING"                                                 -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Scenario      : $Scenario"
Write-Host "  Afspraken     : $($uuids.Count) aangemaakt"
Write-Host "  Aanmaaktijd   : $([math]::Round($createDuration.TotalSeconds, 1))s"
Write-Host "  Totale runtime: $([math]::Round($totalDuration.TotalMinutes, 1)) min"
Write-Host "  Grafana       : http://localhost:3000/d/beunmrs-perf"
Write-Host ""

# ── Stap 5: Testdata opschonen ─────────────────────────────────────────────────
Write-Host "[5/5] Testdata opschonen..." -ForegroundColor Yellow
if ($KeepData) {
    Write-Host "  -KeepData opgegeven -- data blijft bewaard voor inspectie." -ForegroundColor DarkYellow
    Write-Host "  Handmatig opschonen: Remove-TestTenant '$TenantSlug'" -ForegroundColor DarkGray
} else {
    Remove-TestTenant $TenantSlug
    Write-Host "  Klaar. Database is schoon voor de volgende run." -ForegroundColor Green
}
