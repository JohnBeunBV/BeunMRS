<#
.SYNOPSIS
    Verwijdert ALLEEN testdata uit de BeunMRS notification-db.
    Productiedata (echte tenants, hun logs) wordt nooit aangeraakt.

.DESCRIPTION
    Verwijdert rijen die horen bij tenants waarvan de slug begint met
    "loadtest-" of "cb-test-" -- de vaste prefixen die de testscripts
    gebruiken. Rijen van andere tenants worden niet aangeraakt.

    Daarna vult het script seen_appointments opnieuw voor alle ACTIEVE
    (niet-test) tenants, zodat de poller de bestaande OpenMRS-afspraken
    overslaat en de database niet opnieuw volloopt.

    Gebruik dit script alleen als je de testscripts DIRECT hebt gedraaid
    (zonder run-all-tests.ps1). run-all-tests.ps1 gebruikt een tijdelijke
    geisoleerde database en raakt de productie-database nooit aan.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\cleanup-db.ps1
#>

$ErrorActionPreference = "Stop"

# Negeer self-signed cert voor OpenMRS-query
Add-Type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllCleanup : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert,
        WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllCleanup

Write-Host ""
Write-Host ("=" * 58) -ForegroundColor Cyan
Write-Host "  BeunMRS DB Cleanup -- alleen testdata" -ForegroundColor Cyan
Write-Host ("=" * 58) -ForegroundColor Cyan
Write-Host "  Prefixen: 'loadtest-*' en 'cb-test-*'" -ForegroundColor DarkGray

# -- Stap 1: toon wat we gaan verwijderen --------------------------------------
Write-Host ""
Write-Host "  [1/3] Testtenants opzoeken..." -ForegroundColor Yellow
$testTenants = docker exec notification-db psql -U notify -d notifications -t -c `
    "SELECT id, slug FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%';" 2>&1 |
    Where-Object { $_.Trim() -match '\S' }

if (-not $testTenants) {
    Write-Host "  Geen testtenants gevonden -- database is al schoon." -ForegroundColor Green
} else {
    Write-Host "  Te verwijderen testtenants:" -ForegroundColor DarkGray
    $testTenants | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
}

# -- Stap 2: verwijder alleen testdata -----------------------------------------
Write-Host ""
Write-Host "  [2/3] Testdata verwijderen (alleen loadtest-* en cb-test-* tenants)..." -ForegroundColor Yellow

$sql = @'
DELETE FROM notification_audit_log
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM notification_log
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM scheduled_notifications
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM outbox_events
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM seen_appointments
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM sync_watermarks
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM async_flow_commands
    WHERE tenant_id IN (SELECT id FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%');
DELETE FROM tenants
    WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%';
'@
$sql | docker exec -i notification-db psql -U notify -d notifications

# -- Stap 3: vul seen_appointments voor echte tenants (poller-blokkade) ---------
Write-Host ""
Write-Host "  [3/3] seen_appointments opvullen voor actieve (productie-)tenants..." -ForegroundColor Yellow

$auth    = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:Admin1234"))
$headers = @{ Authorization = "Basic $auth" }
$body    = '{"startDate":"2020-01-01T00:00:00.000Z","endDate":"2030-12-31T23:59:59.000Z"}'

try {
    $appts = Invoke-RestMethod -Uri "http://localhost/openmrs/ws/rest/v1/appointment/search" `
                 -Method POST -ContentType "application/json" -Headers $headers -Body $body
    Write-Host "  $($appts.Count) OpenMRS-afspraken gevonden." -ForegroundColor DarkGray
} catch {
    Write-Host "  WAARSCHUWING: OpenMRS niet bereikbaar -- seen_appointments overgeslagen." -ForegroundColor DarkYellow
    $appts = @()
}

if ($appts.Count -gt 0) {
    $tenantRows = docker exec notification-db psql -U notify -d notifications -t -c `
        "SELECT id FROM tenants WHERE active = true AND slug NOT LIKE 'loadtest-%' AND slug NOT LIKE 'cb-test-%';" 2>&1 |
        Where-Object { $_.Trim() -match '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' }
    $tenantIds = @($tenantRows | ForEach-Object { $_.Trim() })

    if ($tenantIds.Count -gt 0) {
        $values = foreach ($appt in $appts) {
            foreach ($tid in $tenantIds) {
                "('$($appt.uuid)', '$tid', '$($appt.status)', NOW())"
            }
        }
        $insert = "INSERT INTO seen_appointments (appointment_uuid, tenant_id, openmrs_status, queued_at) VALUES " +
                  ($values -join ",") + " ON CONFLICT DO NOTHING;"
        $insert | docker exec -i notification-db psql -U notify -d notifications -q
        Write-Host "  $($values.Count) rijen toegevoegd voor $($tenantIds.Count) productie-tenant(s)." -ForegroundColor DarkGray
    } else {
        Write-Host "  Geen productie-tenants -- seen_appointments overgeslagen." -ForegroundColor DarkGray
    }
}

# -- Verificatie ---------------------------------------------------------------
Write-Host ""
docker exec notification-db psql -U notify -d notifications -c "
SELECT
    (SELECT COUNT(*) FROM tenants WHERE slug NOT LIKE 'loadtest-%' AND slug NOT LIKE 'cb-test-%') AS prod_tenants_bewaard,
    (SELECT COUNT(*) FROM tenants WHERE slug LIKE 'loadtest-%' OR slug LIKE 'cb-test-%')          AS test_tenants_over,
    (SELECT COUNT(*) FROM notification_log)   AS notification_log,
    (SELECT COUNT(*) FROM seen_appointments)  AS seen_appts;"

Write-Host ""
Write-Host "  Klaar. Alleen testdata verwijderd; productiedata onaangetast." -ForegroundColor Green
Write-Host ""
