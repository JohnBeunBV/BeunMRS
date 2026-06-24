# BeunMRS — Multi-Tenant SaaS Notificatiemodule voor OpenMRS

Een event-driven notificatieservice die naast OpenMRS draait en patiënten automatisch herinnert aan afspraken via SMS. Elke ziekenhuisorganisatie (tenant) wordt volledig geïsoleerd beheerd met eigen OpenMRS-koppeling, eigen API-sleutels en één gekozen berichtprovider.

**Team:** Wassim Balouda · Storm Kroonen · Nick de Rooij · Thijs van de Veen  
**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL 16 · RabbitMQ 3.13 · Docker Compose · Grafana/Loki · React (Vite)

---

## Vereisten

| Tool | Minimale versie |
|------|-----------------|
| Docker Desktop | 4.x |
| Docker Compose | v2 (`docker compose`) |
| Vrije poorten | 80, 1337, 3000, 3001, 3100, 4000, 5433, 9090, 15672 |

> OpenMRS heeft 5–10 minuten nodig om op te starten (Liquibase + module loading). Wacht op `Server startup in X ms` in de logs.

---

## Quickstart

```powershell
# 1. Omgevingsvariabelen instellen
cp .env.example .env
# (Optioneel: pas wachtwoorden aan in .env — standaardwaarden werken lokaal)

# 2. Volledige stack starten (inclusief OpenMRS)
docker compose up -d

# 3. Wachten tot OpenMRS klaar is
docker compose logs -f backend | Select-String "Server startup"
```

### Alleen de notificatieservice (zonder OpenMRS)

```powershell
docker compose -f docker-compose.noopenmrs.yml up -d
```

### Na een codewijziging in de notificatieservice

```powershell
docker compose build notification-svc
docker compose up -d notification-svc
```

### Schema-reset (na databasewijziging)

```powershell
docker compose down -v
docker compose up -d
```

---

## Dienst-URLs

| Service | URL | Inloggegevens |
|---------|-----|---------------|
| OpenMRS | http://localhost/openmrs | admin / Admin1234 |
| Tenant registratieportaal | **https://localhost:3001** | — (self-signed cert → waarschuwing accepteren) |
| Notificatie API (TLS 1.3) | **https://localhost:4000** | `X-API-Key` header |
| Health check | https://localhost:4000/actuator/health | — |
| RabbitMQ beheer | http://localhost:15672 | rabbit / rabbit_secure_token |
| Grafana monitoring | http://localhost:3000 | admin / grafana_secure_dashboard_pass |
| FakeComWorld (mock SMS) | http://localhost:1337 | — |

> **Let op:** `https://localhost:3001` en `https://localhost:4000` gebruiken self-signed TLS 1.3 certificaten. Klik in de browser op "Geavanceerd → Toch doorgaan" om de waarschuwing te omzeilen.

---

## Architectuur

```
┌──────────────────────────────────────────────────────────────────┐
│                        Docker host                               │
│                                                                  │
│  ┌─────────────────┐   MariaDB   ┌──────────────┐               │
│  │  OpenMRS O3     │◄───────────►│     db       │               │
│  │  (gateway :80)  │             └──────────────┘               │
│  └────────┬────────┘                                            │
│           │ REST v1 polling (elke 2 min, 30-daags venster)      │
│           ▼                                                      │
│  ┌─────────────────────────────────────────────────────┐        │
│  │              notification-svc (Spring Boot)         │        │
│  │                                                     │        │
│  │  • OpenMrsAppointmentPoller  (REST v1 polling)      │        │
│  │  • AppointmentEventConsumer  (RabbitMQ consumer)    │        │
│  │  • ReminderScheduler         (24h + 1h reminders)   │        │
│  │  • OutboxRelayJob            (at-least-once relay)  │        │
│  │  • FailedNotificationRetryJob (3 pogingen, backoff) │        │
│  │  • DataRetentionJob          (14 dagen PII / 1 jaar)│        │
│  └──────────────┬──────────────────────┬──────────────┘        │
│                 │ PostgreSQL            │ AMQP                  │
│                 ▼                       ▼                        │
│  ┌──────────────────┐     ┌──────────────────────┐             │
│  │  notification-db │     │      RabbitMQ        │             │
│  │  (PostgreSQL 16) │     │  exchange: openmrs   │             │
│  └──────────────────┘     │  .events (topic)     │             │
│                            └──────────────────────┘             │
│                                                                  │
│  ┌──────────────────┐     ┌──────────────────────┐             │
│  │ notification-    │     │    FakeComWorld       │             │
│  │ nginx (TLS 1.3)  │────►│  (mock SMS providers)│             │
│  │ :4000 → :8080    │     │  SwiftSend, SecurePost│             │
│  └──────────────────┘     │  LegacyLink, AsyncFlow│            │
│                            └──────────────────────┘             │
│                                                                  │
│  ┌──────────────────────────────────────────┐                   │
│  │  Observability: Prometheus · Loki ·      │                   │
│  │  Promtail · Grafana (:3000)              │                   │
│  └──────────────────────────────────────────┘                   │
└──────────────────────────────────────────────────────────────────┘
```

### Veerkrachtmechanismen

| Risico | Mitigatie |
|--------|-----------|
| OpenMRS event gemist bij downtime | **REST v1 polling** met watermark-cursor — poller haalt gemiste afspraken op bij herstel |
| Crash tijdens versturen | **Transactionele outbox** — event wordt atomisch opgeslagen vóór externe call |
| Provider onbereikbaar | **Circuit breaker** — 5 fouten → 2 min pauze per tenant; `FailedNotificationRetryJob` met backoff (5 → 15 min) |
| Broker onbereikbaar | Durable queues + Dead Letter Exchange (DLX); berichten overleven herstart |
| Dubbele verwerking | `seen_appointments` tabel — duplicate guard per `(appointment_uuid, tenant_id)` |

---

## Tenant registreren

### Via de UI (aanbevolen voor demo)

Ga naar **https://localhost:3001** en vul het registratieformulier in.

### Via de API (Postman of curl)

```http
POST https://localhost:4000/api/register
Content-Type: application/json

{
  "slug":             "amc",
  "displayName":      "Amsterdam UMC",
  "openmrsHost":      "http://gateway/openmrs",
  "openmrsUser":      "admin",
  "openmrsPassword":  "Admin1234",
  "providerName":     "SwiftSend",
  "providerApiKey":   "swiftsend_crypto_key_abc123",
  "timezone":         "Europe/Amsterdam"
}
```

**Voorbeeld response:**

```json
{
  "tenantId":  "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "slug":      "amc",
  "apiKey":    "beunmrs_<gegenereerde-sleutel>"
}
```

> ⚠️ **Sla de `apiKey` direct op** — deze wordt nooit opnieuw getoond.  
> Gebruik de sleutel als `X-API-Key` header bij alle volgende API-aanroepen.

**Geldige `providerName` waarden:** `SwiftSend` · `SecurePost` · `LegacyLink` · `AsyncFlow`

---

## Afspraak aanmaken (voorbeeld request)

```http
POST http://localhost/openmrs/ws/rest/v1/appointment
Authorization: Basic admin:Admin1234
Content-Type: application/json

{
  "patientUuid":     "4df50238-84b9-45ec-9f43-b85701234506",
  "serviceUuid":     "7ba3aa21-cc56-47ca-bb4d-a60549f666c0",
  "locationUuid":    "ba685651-ed3b-4e63-9b35-78893060758a",
  "startDateTime":   "2026-06-10T10:00:00.000Z",
  "endDateTime":     "2026-06-10T10:30:00.000Z",
  "appointmentKind": "Scheduled",
  "comments":        "Nuchter komen. Paspoort meenemen."
}
```

Na aanmaken worden automatisch twee reminders ingepland:
- **24 uur vóór** de afspraak
- **1 uur vóór** de afspraak

De notificaties zijn zichtbaar in de `scheduled_notifications` tabel en worden verstuurd via de gekozen SMS-provider van de tenant.

---

## Voorbeeld output

Wanneer een notificatie wordt verstuurd, is dit zichtbaar in de logs:

```
[notification-svc] INFO  NotificationDispatcher - [amc] Dispatching via SwiftSend to +31****678
[notification-svc] INFO  ReminderDispatchJob    - [amc] Reminder sent: uuid=... status=sent
```

En in de `notification_log` tabel:

```sql
SELECT id, tenant_id, provider_name, status, created_at
FROM notification_log
ORDER BY created_at DESC
LIMIT 10;
```

---

## Multi-tenant isolatie

Elke tenant heeft:
- Eigen OpenMRS-koppeling (`openmrs_host`, credentials)
- Eigen gegenereerde API-sleutel (`X-API-Key` header)
- Één SMS-provider (`provider_name`)
- Eigen tijdzone (`timezone`, standaard `Europe/Amsterdam`)

Alle queries zijn gefilterd op `tenant_id` — tenant A kan nooit data van tenant B zien.

---

## Projectstructuur

```
BeunMRS/
├── docker-compose.yml                  # Volledige stack (inclusief OpenMRS)
├── docker-compose.noopenmrs.yml        # Alleen notificatieservice
├── .env.example                        # Omgevingsvariabelen template
├── frontend/                           # React registratieportaal (Vite + Nginx TLS)
│   ├── src/components/RegisterForm.jsx
│   └── nginx.conf
├── notification-service/               # Spring Boot notificatieservice
│   ├── src/main/java/com/openmrs/notification/
│   │   ├── adapter/        # SwiftSend, SecurePost, LegacyLink, AsyncFlow providers
│   │   ├── consumer/       # RabbitMQ event consumer
│   │   ├── poller/         # OpenMRS REST v1 polling (elke 2 min)
│   │   ├── scheduler/      # Reminder planning + dispatch + retry + retentie
│   │   ├── security/       # AES-256-GCM versleuteling
│   │   ├── service/        # NotificationDispatcher, PersonContactService
│   │   ├── tenant/         # Multi-tenant registratie, API key filter, TenantContext
│   │   └── util/           # MessageHelper (tijd, locatie, instructies formatting)
│   └── src/test/           # 87 unit tests (Mockito)
├── infra/
│   ├── nginx/              # TLS 1.3 reverse proxy (self-signed cert voor dev)
│   ├── postgres/init/      # 00_schema.sql — 8 tabellen
│   ├── rabbitmq/           # Exchange + queue topologie + DLX
│   ├── loki/               # Log aggregatie configuratie
│   ├── promtail/           # Log collector configuratie
│   └── grafana/            # Dashboards + datasource provisioning
└── docs/
    ├── ADR 1 - 4/          # Architectuurbeslissingen (ADR-001 t/m ADR-010)
    ├── C4-diagrammen.md    # C4 L1/L2/L3 diagrammen + procesvisualisatie
    ├── FMEA/               # Failure Mode Effect Analysis (11 modes + risicomatrix)
    ├── Traceerbaarheid/    # Traceerbaarheidsmatrix: req → ADR → code → test
    ├── Realisatielogboek/  # D4a/b/c: tools, AI-gebruik, commits per teamlid
    ├── PerformanceRapport/ # Belastingstests (166 notif/sec)
    ├── Security/           # Security audit rapport
    ├── Tests/              # Testrapport (109 tests)
    └── Info/               # Opdrachtomschrijving, Postman requests
```

---

## Bekende valkuilen

| Probleem | Oplossing |
|----------|-----------|
| OpenMRS start traag | Wacht 5–10 min op `Server startup` in de backend-logs |
| Schema niet bijgewerkt | `docker compose down -v && docker compose up -d` |
| Browser TLS-waarschuwing | Self-signed cert: klik "Geavanceerd → Toch doorgaan" |
| `comments` altijd `null` | Bekend — poller doet extra `GET /appointment?uuid=` om dit op te halen |
| FHIR2 Appointment werkt niet | HAPI-0302 fout — we gebruiken REST v1 (zie ADR-003) |
| Port 80 al in gebruik | Stop lokale webserver of pas `OPENMRS_PORT` aan in `.env` |

---

## Documentatie & Traceerbaarheid

| Document | Inhoud |
|---|---|
| [Traceerbaarheidsmatrix](docs/Traceerbaarheid/traceerbaarheidsmatrix.md) | Koppeling: elke requirement → ADR → implementatieklasse → test |
| [C4-diagrammen](docs/C4-diagrammen.md) | Systeemcontext (L1), Containers (L2), Componenten notification-svc (L3) |
| [ADR-001 t/m ADR-010](docs/ADR%201%20-%204/) | Alle architectuurbeslissingen met alternatieven en onderbouwing |
| [FMEA](docs/FMEA/FMEA_Documentatie.md) | 11 failure modes met risicomatrix, ADR-koppeling, code en tests |
| [Testrapport](docs/Tests/testrapport.md) | 109 tests: unit/security/contract/integratie + requirement-mapping |
| [Beheerdershandleiding](docs/README-beheerder.md) | Installatie, tenant-configuratie, productie-TLS, monitoring |
| [Realisatielogboek](docs/Realisatielogboek/realisatielogboek.md) | Ontwikkeltools, AI-gebruik, commits per teamlid |
| [Security Audit](docs/Security/SECURITY-AUDIT.md) | OWASP top 10 verificatie, productie-checklist |
| [Performance Rapport](docs/PerformanceRapport/PERFORMANCE-RAPPORT.md) | Belastingstest: 166 notif/sec, 100% outbox success |

---

## Licentie

Intern educatief project — Avans Hogeschool, 2025/2026.
