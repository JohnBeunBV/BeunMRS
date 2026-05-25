<#
.SYNOPSIS
    Voert alle tests uit de scripts-map achter elkaar uit in een
    GEISOLEERDE testdatabase -- de productie-database (notifications)
    wordt nooit aangeraakt.

    Werking (vergelijkbaar met Testcontainers in de JUnit-integratietests):
      1. Maak tijdelijke database  notifications_test_<timestamp>  aan
      2. Initialiseer het schema (00_schema.sql)
      3. Herstart notification-svc met DB_NAME=notifications_test_<timestamp>
      4. Draai: baseline -> load -> stress loadtest (elk schoont eigen tenant op)
      5. Vraag optioneel: circuit breaker test uitvoeren?
      6. Zet notification-svc terug naar de originele notifications-database
      7. Verwijder de tijdelijke testdatabase

    Na afloop is de productie-database exact zoals voor de tests.

.PARAMETER Quick
    Gebruik 30 seconden wachttijd per loadtest-scenario i.p.v. 180 seconden.

.PARAMETER LoadOnly
    Sla de circuit-breaker-test over (geen interactieve stappen vereist).

.PARAMETER KeepData
    Bewaar de testdatabase na afloop (wordt NIET verwijderd).
    Handig om na de tests in de DB te kijken via psql of Grafana.
    De naam van de testdatabase wordt getoond bij het opstarten.

.PARAMETER Pause
    Pauzeer tussen elk scenario zodat je de testdatabase tussendoor
    kunt inspecteren (psql-commando wordt getoond). Druk Enter om door
    te gaan naar het volgende scenario.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -Quick -LoadOnly
    powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1
    powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -Quick -KeepData
    powershell -ExecutionPolicy Bypass -File scripts\run-all-tests.ps1 -Quick -LoadOnly -Pause
#>
param(
    [switch] $Quick,
    [switch] $LoadOnly,
    [switch] $KeepData,
    [switch] $Pause
)

$ErrorActionPreference = "Stop"

$WaitSeconds = if ($Quick) { 30 } else { 180 }
$ScriptsDir  = $PSScriptRoot
$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$SchemaFile  = Join-Path $ProjectRoot "infra\postgres\init\00_schema.sql"

$results    = [System.Collections.Generic.List[PSObject]]::new()
$suiteStart = Get-Date
$testDbName = $null   # ingevuld door Initialize-TestDatabase

# ---- Hulpfuncties ------------------------------------------------------------

function Write-Banner {
    param([string]$Msg, [string]$Color = "Cyan")
    Write-Host ""
    Write-Host ("=" * 62) -ForegroundColor $Color
    Write-Host "  $Msg" -ForegroundColor $Color
    Write-Host ("=" * 62) -ForegroundColor $Color
}

# Wacht totdat notification-svc healthy is (max $TimeoutSec seconden).
function Wait-NotificationSvcHealthy {
    param([int]$TimeoutSec = 90)
    Write-Host "  Wachten op notification-svc (max ${TimeoutSec}s)..." -ForegroundColor DarkGray
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = docker inspect --format="{{.State.Health.Status}}" notification-svc 2>&1
        if ($status -eq "healthy") {
            Write-Host "  notification-svc is healthy." -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 3
    }
    throw "notification-svc werd niet healthy binnen ${TimeoutSec}s."
}

# Maakt een tijdelijke testdatabase aan en schakelt notification-svc erop over.
function Initialize-TestDatabase {
    Write-Banner "ISOLATIE: tijdelijke testdatabase aanmaken" "Magenta"

    # Unieke naam zodat parallelle runs nooit conflicteren
    $script:testDbName = "notifications_test_$(Get-Date -Format 'yyyyMMddHHmmss')"
    Write-Host "  Database: $($script:testDbName)" -ForegroundColor DarkGray

    # Stap 1: Maak database aan (notify is superuser in de Docker Postgres image)
    docker exec notification-db psql -U notify -d postgres `
        -c "CREATE DATABASE $($script:testDbName) OWNER notify;" 2>&1 | Out-Null
    Write-Host "  Database aangemaakt." -ForegroundColor DarkGray

    # Stap 2: Initialiseer schema (strip comments, splits op ;)
    $sql = Get-Content $SchemaFile -Raw -Encoding UTF8
    $statements = ($sql -split "`n" | Where-Object { $_ -notmatch '^\s*--' }) -join "`n" -split ";"
    foreach ($stmt in $statements) {
        $t = $stmt.Trim()
        if ($t.Length -gt 0) {
            $t | docker exec -i notification-db psql -U notify -d $($script:testDbName) -q 2>&1 | Out-Null
        }
    }
    Write-Host "  Schema geinitialiseerd." -ForegroundColor DarkGray

    # Stap 3: Herstart notification-svc met nieuwe DB_NAME
    # Docker Compose vervangt ${NOTIFICATION_DB_NAME:-notifications} met de env-var.
    $env:NOTIFICATION_DB_NAME = $script:testDbName
    Set-Location $ProjectRoot
    docker compose up -d notification-svc 2>&1 | Out-Null
    Write-Host "  notification-svc herstarten met testdatabase..." -ForegroundColor DarkGray
    Wait-NotificationSvcHealthy
    Write-Host ""
    Write-Host "  Testdatabase actief:" -ForegroundColor Green
    Write-Host "    $($script:testDbName)" -ForegroundColor White
    Write-Host "  Inloggen op de testdatabase:" -ForegroundColor DarkGray
    Write-Host "    docker exec -it notification-db psql -U notify -d $($script:testDbName)" -ForegroundColor White
    Write-Host "  Productie-database wordt niet aangeraakt." -ForegroundColor Green
}

# Zet notification-svc terug op de productiedatabase en verwijdert de testdatabase.
function Restore-ProductionDatabase {
    if (-not $script:testDbName) { return }

    Write-Banner "ISOLATIE: productiedatabase herstellen" "Magenta"

    # Stap 1: Verwijder env-var zodat Docker Compose de standaard pakt
    Remove-Item Env:NOTIFICATION_DB_NAME -ErrorAction SilentlyContinue

    Set-Location $ProjectRoot
    docker compose up -d notification-svc 2>&1 | Out-Null
    Write-Host "  notification-svc herstarten met productiedatabase..." -ForegroundColor DarkGray
    Wait-NotificationSvcHealthy

    if ($KeepData) {
        Write-Host "  -KeepData: testdatabase blijft bewaard voor inspectie:" -ForegroundColor DarkYellow
        Write-Host "    docker exec -it notification-db psql -U notify -d $($script:testDbName)" -ForegroundColor White
        Write-Host "  Verwijderen achteraf:" -ForegroundColor DarkGray
        Write-Host "    docker exec notification-db psql -U notify -d postgres -c ""DROP DATABASE $($script:testDbName);""" -ForegroundColor DarkGray
    } else {
        # Stap 2: Verwijder testdatabase (niemand mag er nog op verbonden zijn)
        docker exec notification-db psql -U notify -d postgres `
            -c "DROP DATABASE IF EXISTS $($script:testDbName);" 2>&1 | Out-Null
        Write-Host "  Testdatabase '$($script:testDbName)' verwijderd." -ForegroundColor Green
    }

    $script:testDbName = $null
}

# Roept een subscript aan in een aparte PowerShell-instantie (geen Add-Type conflicten).
function Invoke-TestScript {
    param(
        [string]   $Label,
        [string]   $ScriptPath,
        [string[]] $ExtraArgs = @()
    )
    Write-Banner "START: $Label"
    $start  = Get-Date
    $psArgs = @("-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $ExtraArgs
    & powershell @psArgs
    $exitCode = $LASTEXITCODE
    $elapsed  = (Get-Date) - $start
    $ok       = ($exitCode -eq 0)
    $results.Add([PSCustomObject]@{
        Test   = $Label
        Status = if ($ok) { "OK" } else { "FOUT (exit $exitCode)" }
        Tijd   = "$([math]::Round($elapsed.TotalMinutes, 1)) min"
    })
    if (-not $ok) {
        Write-Host ""
        Write-Host "  WAARSCHUWING: '$Label' eindigde met exitcode $exitCode -- suite gaat door." `
            -ForegroundColor DarkYellow
    }
}

# ---- Banner ------------------------------------------------------------------
Write-Host ""
Write-Host ("=" * 62) -ForegroundColor White
Write-Host "  BeunMRS -- Volledige testscript-suite (geisoleerde DB)" -ForegroundColor White
if ($Quick)    { Write-Host "  Modus: Quick (wachttijd $WaitSeconds s/scenario)" -ForegroundColor DarkCyan }
else           { Write-Host "  Modus: Volledig (wachttijd $WaitSeconds s/scenario)" -ForegroundColor DarkCyan }
if ($LoadOnly) { Write-Host "  Circuit breaker test: overgeslagen (-LoadOnly)" -ForegroundColor DarkGray }
Write-Host ("=" * 62) -ForegroundColor White

# ---- Stap 0: tijdelijke testdatabase aanmaken --------------------------------
try {
    Initialize-TestDatabase
} catch {
    Write-Host "  FOUT bij aanmaken testdatabase: $_" -ForegroundColor Red
    exit 1
}

# ---- Stap 1-3: loadtest-scenario's -------------------------------------------
foreach ($scenario in @("baseline", "load", "stress")) {
    $label    = "Loadtest -- $scenario"
    $loadArgs = @("-Scenario", $scenario, "-WaitSeconds", "$WaitSeconds")
    if ($KeepData) { $loadArgs += "-KeepData" }
    Invoke-TestScript $label "$ScriptsDir\loadtest.ps1" $loadArgs

    if ($Pause) {
        Write-Host ""
        Write-Host "  --- PAUZE na $scenario ---" -ForegroundColor DarkYellow
        Write-Host "  Inspecteer de testdatabase:" -ForegroundColor DarkGray
        Write-Host "    docker exec -it notification-db psql -U notify -d $($script:testDbName)" -ForegroundColor White
        Write-Host "  Nuttige queries:" -ForegroundColor DarkGray
        Write-Host "    SELECT status, COUNT(*) FROM notification_log GROUP BY status;" -ForegroundColor DarkGray
        Write-Host "    SELECT slug, active FROM tenants;" -ForegroundColor DarkGray
        Write-Host "    SELECT COUNT(*) FROM seen_appointments;" -ForegroundColor DarkGray
        Write-Host ""
        $null = Read-Host "  Druk Enter om door te gaan naar het volgende scenario"
    }
}

# ---- Stap 4: circuit breaker test (interactief, optioneel) -------------------
if (-not $LoadOnly) {
    Write-Host ""
    Write-Host ("-" * 62) -ForegroundColor DarkGray
    Write-Host "  De circuit-breaker-test vereist handmatige Docker-acties." -ForegroundColor White
    Write-Host "  (gateway stoppen en herstarten via een apart terminalvenster)" -ForegroundColor DarkGray
    Write-Host "  Duur: ~10-15 minuten inclusief wachttijd." -ForegroundColor DarkGray
    Write-Host ("-" * 62) -ForegroundColor DarkGray
    Write-Host ""
    $antwoord = Read-Host "  Circuit-breaker-test uitvoeren? [j/N]"
    if ($antwoord -match "^[jJ]") {
        $cbArgs = @()
        if ($KeepData) { $cbArgs += "-KeepData" }
        Invoke-TestScript "Circuit Breaker test (NFR-7)" "$ScriptsDir\circuitbreaker-test.ps1" $cbArgs
    } else {
        Write-Host "  Circuit-breaker-test overgeslagen." -ForegroundColor DarkGray
        $results.Add([PSCustomObject]@{ Test = "Circuit Breaker test (NFR-7)"; Status = "Overgeslagen"; Tijd = "-" })
    }
}

# ---- Stap 5: productiedatabase herstellen ------------------------------------
Restore-ProductionDatabase

# ---- Eindsamenvatting --------------------------------------------------------
$totalElapsed = (Get-Date) - $suiteStart

Write-Host ""
Write-Host ("=" * 62) -ForegroundColor White
Write-Host "  EINDSAMENVATTING" -ForegroundColor White
Write-Host ("=" * 62) -ForegroundColor White
Write-Host ""

foreach ($r in $results) {
    $color     = switch ($r.Status) { "OK" { "Green" } "Overgeslagen" { "DarkGray" } default { "Red" } }
    $statusPad = $r.Status.PadRight(20)
    $tijdPad   = $r.Tijd.PadLeft(8)
    Write-Host "  [$statusPad] $tijdPad   $($r.Test)" -ForegroundColor $color
}

Write-Host ""
Write-Host "  Totale looptijd : $([math]::Round($totalElapsed.TotalMinutes, 1)) minuten" -ForegroundColor White
Write-Host "  Productie-DB    : ONAANGETAST" -ForegroundColor Green

$fouten = $results | Where-Object { $_.Status -notmatch "^(OK|Overgeslagen)$" }
if ($fouten) {
    Write-Host "  LET OP: $($fouten.Count) test(s) eindigden met een fout." -ForegroundColor Red
} else {
    Write-Host "  Alle uitgevoerde tests geslaagd." -ForegroundColor Green
}
Write-Host ""
