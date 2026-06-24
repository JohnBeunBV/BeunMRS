# CLAUDE.md — BeunMRS / OpenMRS Communicatiemodule

Projectcontext voor Claude Code. Lees dit aan het begin van elke sessie.

---

## 📌 Wat is dit project?

Een **multi-tenant SaaS notificatiemodule** die naast OpenMRS draait en patiënten automatisch herinnert aan hun afspraken via externe messaging providers (SMS). Elke tenant (ziekenhuisorganisatie) heeft een eigen OpenMRS-instantie, eigen API-sleutels en één gekozen messaging provider.

**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL · RabbitMQ · Docker Compose · Grafana/Loki · React (Vite)

---

## 🎯 COMPLIANCE MATRIX — Eisen uit opdracht vs Status

> **Dit is de leidende lijst.** Elke regel uit `docs/OpdrachtOpenMRS.md` is hier gemapped. Werk dit bij na elke wijziging.

Zie @AGENTS.md voor rubric en sprints

### Functionele eisen (FR)

| ID | Eis | Implementatie | Status |
|----|-----|---------------|--------|
| **FR-1a** | Notificatie 24u vóór afspraak | `ReminderScheduler` + `ReminderDispatchJob` | ✅ |
| **FR-1b** | Notificatie 1u vóór afspraak | `ReminderScheduler` + `ReminderDispatchJob` | ✅ |
| **FR-1c** | Bevat datum + tijd | `MessageHelper.formatTime()` met tenant-timezone | ✅ |
| **FR-1d** | Bevat locatie (polikliniek/kamer) | `MessageHelper.locationSuffix()` — `locationName` uit OpenMRS | ✅ |
| **FR-1e** | Bevat instructies (nuchter etc.) | `MessageHelper.commentsSuffix()` — `comments` uit OpenMRS | ✅ |
| **FR-1f** | Skip als afspraak al gestart | `ReminderDispatchJob.processReminder()` → `status='skipped'` | ✅ |
| **FR-1g** | Bij annulering: stop reminders | `AppointmentEventConsumer` CANCELLED → `cancelReminders()` | ✅ |
| **FR-1h** | Bij wijziging: pas reminders aan | `AppointmentEventConsumer` UPDATED → cancel + reschedule | ✅ |
| **FR-2** | Logging voor factuurcontrole | `notification_log` + `notification_audit_log` (1jr) | ✅ |
| **FR-3** | Eén provider per organisatie | `NotificationDispatcher` filtert op `tenant.providerName` | ✅ |

### Niet-functionele eisen (NFR)

| ID | Eis | Implementatie | Status |
|----|-----|---------------|--------|
| **NFR-1** | Multi-tenant SaaS | `tenants` tabel + `TenantContext` ThreadLocal + scoped queries | ✅ |
| **NFR-2a** | Integratie passend bij doel | REST v1 polling (ADR-003) | ✅ |
| **NFR-2b** | Gedocumenteerd voor beheerders | `docs/README-beheerder.md` | ❌ **TODO** |
| **NFR-2c** | Beveiligd volgens best practices | API key auth + AES-256 + masking | ✅ |
| **NFR-3** | 4 providers (SwiftSend, LegacyLink, AsyncFlow, SecurePost) | Alle 4 geïmplementeerd | ✅ |
| **NFR-4** | OpenMRS 2.7.x compatibiliteit | Gebruikt REST v1 API (stabiel sinds 2.x) | ⚠️ **TE VERIFIËREN** |
| **NFR-5a** | AES-256 voor opslag | `AesEncryptionService` (GCM mode) | ✅ |
| **NFR-5b** | **TLS 1.3 voor transport** | `notification-nginx` container (NGINX + TLS 1.3 only, self-signed cert) | ✅ |
| **NFR-5c** | Credentials niet in code/config | `.env` + `AesEncryptionService` | ✅ |
| **NFR-5d** | Logs niet onbeveiligd | PII gemaskeerd, Loki intern netwerk | ⚠️ **Loki TLS doc** |
| **NFR-6a** | HL7 berichtontvangst + validatie | JSON schema via Jackson, RabbitMQ converter | ⚠️ **Aantonen** |
| **NFR-6b** | ACK voor ontvangstbevestiging | RabbitMQ ack + `notification_log.status` | ⚠️ **Doc nodig** |
| **NFR-6c** | Logging en tracking | `notification_log` met provider, status, retry_count | ✅ |
| **NFR-6d** | Berichttransformatie | Provider adapters mappen event → provider-formaat | ✅ |
| **NFR-6e** | Queueing en retry | RabbitMQ + outbox + `FailedNotificationRetryJob` | ✅ |
| **NFR-7** | Zelfstandig + fallback | Outbox + circuit breaker + retry | ✅ |
| **NFR-8** | Karaktersets (UTF-8) | DB UTF-8 + Spring UTF-8 + JSON UTF-8 | ⚠️ **Aantonen met testbericht** |
| **NFR-9a** | Monitoring + dashboard | Grafana + Loki + Prometheus | ⚠️ **Dashboard config nodig** |
| **NFR-9b** | OpenTelemetry | Niet geïmplementeerd — alleen Micrometer/Prometheus | ❌ **TODO of motiveren** |
| **NFR-10** | 14-dagen verwijdering | `DataRetentionJob` cron 02:00 daily | ✅ |
| **NFR-11** | 1-jaar meta-info retentie | `notification_audit_log` (PII-vrij) + `purgeOldAuditLog()` | ✅ |
| **NFR-12** | Uitbreidbaar naar andere modules | Event-driven via RabbitMQ — andere routing keys mogelijk | ⚠️ **Aantonen in doc** |
| **NFR-13** | Tijdzones | `tenants.timezone` + `MessageHelper.formatTime(zone)` | ✅ |

### Doelstelling-specifiek

| Onderwerp | Status | Toelichting |
|-----------|--------|-------------|
| **FHIR-specificatie** | ⚠️ | Opdracht zegt FHIR; we gebruiken REST v1 omdat FHIR2-module geen Appointment ondersteunt (`HAPI-0302`). Uitgelegd in ADR-003. |
| **Uitbreidbaarheid (nieuwe provider)** | ✅ | `NotificationProvider` interface — nieuwe klasse implementeren, klaar. Aan te tonen via demo. |

### Deliverables

| ID | Deliverable | Status |
|----|-------------|--------|
| **D1** | Technische documentatie (`docs/README-beheerder.md`) | ✅ Aanwezig |
| **D2** | Codebase + opstartinstructies (root `README.md`) | ✅ Bijgewerkt |
| **D3a** | ADR-logboek | ✅ ADR-001 t/m ADR-010 |
| **D3b** | C4 diagrammen (L1/L2/L3) + procesvisualisatie | ✅ `docs/C4-diagrammen.md` |
| **D4a** | Realisatielogboek: ontwikkeltools | ✅ `docs/Realisatielogboek/realisatielogboek.md` |
| **D4b** | Realisatielogboek: AI-tools + voorbeelden | ✅ `docs/Realisatielogboek/realisatielogboek.md` |
| **D4c** | Realisatielogboek: commits per teamlid | ⚠️ Tabel aanwezig — bijwerken met `git log` vóór inlevering |
| **D5** | Testrapportage | ✅ `docs/Tests/testrapport.md` (109 tests) |
| **Traceerbaarheid** | Requirements → ADR → code → test | ✅ `docs/Traceerbaarheid/traceerbaarheidsmatrix.md` |

---

## 🔴 KRITIEKE OPENSTAANDE PUNTEN

> Aanpakken in deze volgorde. Geen item overslaan zonder expliciete motivatie.

### Code/configuratie (TIER 1)

- [ ] **NFR-4 — OpenMRS 2.7.x verificatie**
  Test of de poller werkt tegen OpenMRS 2.7.x specifiek (huidige Docker draait reference-app 3.x). Documenteer welke endpoints we gebruiken en sinds welke OpenMRS-versie die bestaan.

- [x] ~~**NFR-9b — OpenTelemetry of motivatie**~~ ✅ Gemotiveerd in ADR-010: Micrometer + Prometheus + Loki dekt NFR-9a volledig; OTLP-overhead niet gerechtvaardigd voor single-service.

### Documentatie (TIER 2 — verplichte deliverables)

- [x] ~~**NFR-5b — TLS 1.3 HTTPS**~~ ✅ Geïmplementeerd: `infra/nginx/` (NGINX + `ssl_protocols TLSv1.3`) — extern bereikbaar via `https://localhost:4000`

- [x] ~~**D1 — `docs/README-beheerder.md`**~~ ✅ Aanwezig (~700 regels, secties 1–14)

- [ ] **D1 — `docs/README-beheerder.md`** (Deliverable 1) *(reeds aanwezig — onderstaande punten zijn verwerkt)*
  - Koppeling met OpenMRS (REST v1, poller-interval, credentials)
  - Stap-voor-stap tenant registreren + eerste afspraak testen
  - **NFR-5b — TLS 1.3 productie-setup**: self-signed vervangen door Let's Encrypt cert (volume mount in docker-compose)
  - **NFR-5d — Loki TLS-config** voor productie
  - **NFR-6b — HL7 ACK-equivalent** uitleg (RabbitMQ ack + status-trail)
  - **NFR-8 — UTF-8 end-to-end** + testbericht met niet-Latijnse karakters
  - **NFR-9a — Grafana dashboard URLs** + alerts setup
  - **NFR-12 — Uitbreidbaarheid** uitleggen (nieuwe RabbitMQ routing keys)
  - **NFR-2c — Beveiliging best practices** samenvatting

- [x] ~~**D2 — Root `README.md`**~~ ✅ Bijgewerkt met documentatietabel, traceerbaarheidslinks, projectstructuur

- [x] ~~**D3a — ADR-logboek**~~ ✅ ADR-001 t/m ADR-010 aanwezig in `docs/ADR 1 - 4/`

- [x] ~~**D3b — C4 diagrammen**~~ ✅ `docs/C4-diagrammen.md` met L1/L2/L3 en procesvisualisatie

- [x] ~~**D4 — Realisatielogboek**~~ ✅ `docs/Realisatielogboek/realisatielogboek.md` (D4a/b/c)
  - ⚠️ D4c commits-tabel: bijwerken met `git log`-output vóór inlevering

- [x] ~~**D5 — Testrapport**~~ ✅ `docs/Tests/testrapport.md` (109 tests, requirement-mapping aanwezig)

- [x] ~~**Traceerbaarheidsmatrix**~~ ✅ `docs/Traceerbaarheid/traceerbaarheidsmatrix.md` — alle 23 requirements → ADR → code → test

### Aantoonbaarheid (TIER 3 — bewijs in documentatie)

- [ ] **NFR-6a** HL7 berichtvalidatie expliciet aantonen (Jackson strict mode of equivalent)
- [ ] **NFR-8** UTF-8 testbericht (Arabisch/Chinees) door hele stack
- [ ] **NFR-9a** Grafana dashboard met: messages/min, errors, retry counts, per-provider latency

### End-to-end verificatie (TIER 4 — laatste stap)

> ⚠️ De URLs hieronder zijn **lokale development-URLs** (http is ok voor localhost). In productie: alles via HTTPS/TLS 1.3. Zie NFR-5b hierboven.

- [ ] Schone start: `docker compose down -v && docker compose up -d`
- [ ] Tenant registreren via https://localhost:3001
- [ ] Afspraak aanmaken → reminders in `scheduled_notifications`
- [ ] Afspraak annuleren → reminders krijgen `status='cancelled'`
- [ ] Twee tenants (SwiftSend + SecurePost) — juiste provider per tenant in logs
- [ ] Tenant-isolatie: tenant A API key → alleen tenant A data zichtbaar

---

## 🏗️ Architectuur — quick reference

### Integratiemethode (ADR-003)

REST v1 `POST /ws/rest/v1/appointment/search` polling — elke 2 min, 30-daags window, per tenant.
- AtomFeed afgevallen (vereist Bahmni)
- Webhook afgevallen (events verloren bij downtime)
- FHIR2 afgevallen (`HAPI-0302: Unknown resource type 'Appointment'`)

### Multi-tenant

| Per tenant | Implementatie |
|------------|---------------|
| Eigen OpenMRS-host | `tenants.openmrs_host` |
| Eigen credentials | AES-256-GCM encrypted in DB |
| Eén messaging provider | `tenants.provider_name` (CHECK constraint) |
| Eigen API-key | `X-API-Key` header, SHA-256 hash lookup |
| Eigen tijdzone | `tenants.timezone` (IANA, default Europe/Amsterdam) |

`TenantContext` (ThreadLocal) draagt tenant door request/job lifecycle.

### Provider pattern

`NotificationProvider` interface — `send(event, credentials)`. Per tenant **één** provider via `tenant.providerName`. Nieuwe provider = nieuwe klasse, nul andere wijzigingen.

**Alle providers gebruiken phone (SMS) — email is verwijderd uit het systeem** (teacher confirmed: telefoonnummer is voldoende).

### Veerkrachtmechanismen

| Mechanisme | Implementatie |
|------------|---------------|
| Circuit breaker | 5 fouten → 2 min pauze, per tenant-slug, in-memory |
| Persist-before-publish | `outbox_events` tabel vóór RabbitMQ |
| Outbox relay | `OutboxRelayJob` elke 30s, max 5 pogingen → `failed_at` |
| Duplicate guard | `seen_appointments` scoped op `(appointment_uuid, tenant_id)` |
| Reminder scheduling | `scheduled_notifications` met JSONB payload, dispatch elke 60s |
| Provider retry | `FailedNotificationRetryJob` — 3 pogingen, backoff 5→15 min, dan `permanently_failed` |
| Data retentie | `DataRetentionJob` — daily 02:00, 14 dagen PII / 1 jaar audit |

### Database (8 tabellen)

| Tabel | Doel |
|-------|------|
| `tenants` | SaaS-registry: credentials, provider, timezone, API-sleutel |
| `outbox_events` | At-least-once delivery relay |
| `sync_watermarks` | Poller cursor per tenant |
| `seen_appointments` | Duplicate guard per tenant |
| `notification_log` | Audit trail (14 dagen, PII gemaskeerd) |
| `scheduled_notifications` | 24h + 1h reminders met JSONB payload |
| `async_flow_commands` | Pending AsyncFlow async commands |
| `notification_audit_log` | PII-vrij logboek (1 jaar — NFR-11) |

### RabbitMQ topology

- Exchange: `openmrs.events` (topic, durable)
- Queues: `appointments`, `appointment.cancelled` (beide met DLX)
- DLX: `openmrs.events.dlx`
- Routing keys: `appointment.scheduled`, `appointment.updated`, `appointment.cancelled`

---

## 📂 Projectstructuur

```
BeunMRS/
├── docker-compose.yml                  # Volledige stack
├── .env                                # Lokale secrets (nooit committen)
├── frontend/                           # React registratiescherm (Vite + Nginx)
│   ├── src/components/RegisterForm.jsx
│   └── nginx.conf
├── notification-service/               # Spring Boot service
│   ├── src/main/java/com/openmrs/notification/
│   │   ├── adapter/                    # SwiftSend, SecurePost, LegacyLink, AsyncFlow, Mock
│   │   ├── config/                     # AppConfig, RestTemplateFactory, GlobalExceptionHandler
│   │   ├── consumer/                   # AppointmentEventConsumer
│   │   ├── model/                      # AppointmentEvent, NotificationResult, ProviderCredentials
│   │   ├── outbox/                     # OutboxService, OutboxRelayJob
│   │   ├── poller/                     # OpenMrsAppointmentPoller
│   │   ├── reconciler/                 # AppointmentReconciler
│   │   ├── scheduler/                  # ReminderScheduler, ReminderDispatchJob,
│   │   │                               # FailedNotificationRetryJob, DataRetentionJob
│   │   ├── security/                   # AesEncryptionService
│   │   ├── service/                    # NotificationDispatcher, PersonContactService
│   │   ├── tenant/                     # Tenant, TenantService, TenantContext,
│   │   │                               # TenantApiKeyFilter, TenantRegistration/AdminController
│   │   └── util/                       # MessageHelper
│   └── src/test/                       # 87 unit tests (Mockito subclass mocker)
├── infra/
│   ├── postgres/init/00_schema.sql
│   ├── rabbitmq/definitions/topology.json
│   ├── loki/loki-config.yml
│   ├── promtail/promtail-config.yml
│   └── grafana/provisioning/
└── docs/
    ├── OpdrachtOpenMRS.md              # Bron der waarheid
    ├── ADR-001..004                    # ADRs
    ├── SECURITY-AUDIT.md
    └── openmrs-appointment-flow-v2.md
```

---

## 🚪 Ports

| Service | Host | Container |
|---------|------|-----------|
| OpenMRS gateway | 80 | 80 |
| RabbitMQ management | 15672 | 15672 |
| Grafana | 3000 | 3000 |
| Loki | 3100 | 3100 |
| notification-nginx (TLS 1.3) | 4000 | 443 |
| notification-svc (intern) | — | 8080 |
| FakeComWorld | 1337 | 8080 |
| notification-frontend (TLS 1.3) | 3001 | 443 |

---

## 🚀 Hoe starten

```powershell
# Eerste keer (of na schema-wijziging)
docker compose down -v
docker compose up -d

# Rebuild notification-svc na code wijziging
docker compose build notification-svc && docker compose up -d notification-svc

# Rebuild frontend na UI wijziging
docker compose build notification-frontend && docker compose up -d notification-frontend

# Logs
docker compose logs -f notification-svc
```

**URLs:**
- OpenMRS: http://localhost/openmrs (admin / Admin1234)
- Tenant registratie UI: **https://localhost:3001** (self-signed cert → browser-waarschuwing accepteren)
- RabbitMQ UI: http://localhost:15672 (rabbit / rabbit_secret)
- Grafana: http://localhost:3000 (admin / grafana_secret)
- FakeComWorld: http://localhost:1337
- Notification health: https://localhost:4000/actuator/health (self-signed cert → browser-waarschuwing accepteren)

**Tenant registreren (Postman of curl):**
```
POST http://localhost:4000/api/register
Content-Type: application/json

{
  "slug": "amc",
  "displayName": "Amsterdam UMC",
  "openmrsHost": "http://gateway/openmrs",
  "openmrsUser": "admin",
  "openmrsPassword": "Admin1234",
  "providerName": "SwiftSend",
  "providerApiKey": "sk-swiftsend-demo"
}
```

**Afspraak aanmaken:**
```
POST http://localhost/openmrs/ws/rest/v1/appointment
Authorization: Basic admin:Admin1234
Content-Type: application/json

{
  "patientUuid":     "4df50238-84b9-45ec-9f43-b85701234506",
  "serviceUuid":     "7ba3aa21-cc56-47ca-bb4d-a60549f666c0",
  "locationUuid":    "ba685651-ed3b-4e63-9b35-78893060758a",
  "startDateTime":   "2026-05-22T10:00:00.000Z",
  "endDateTime":     "2026-05-22T10:30:00.000Z",
  "appointmentKind": "Scheduled",
  "comments":        "Nuchter komen. Paspoort meenemen."
}
```

---

## ⚠️ Bekende valkuilen

- **`comments` via search API altijd null** — `POST /ws/rest/v1/appointment/search` retourneert `"comments": null`. `enrichComments()` doet een extra `GET /ws/rest/v1/appointment?uuid={uuid}`.
- **FHIR2 Appointment niet ondersteund** — `GET /ws/fhir2/R4/Appointment` → `HAPI-0302`. Daarom REST v1.
- **OpenMRS start traag** — 5-10 minuten (Liquibase + module loading). Wacht op `Server startup`.
- **Schema-wijziging vereist clean restart** — `docker compose down -v && docker compose up -d`. Volumes worden niet auto-bijgewerkt.
- **AES-256 dev-sleutel** — `AesEncryptionService` heeft een hardcoded fallback. Productie: zet `DB_ENCRYPTION_KEY`.
- **`saas.admin-key` default** — Productie: zet `SAAS_ADMIN_KEY`.
- **Java 24 + Mockito** — Subclass mock maker config in `src/test/resources/mockito-extensions/`. Niet wijzigen.
- **Email is verwijderd** — Alle providers gebruiken alleen phone (SMS). `patientEmail` veld bestaat nog in `AppointmentEvent` maar wordt nooit gepopuleerd.

---

## 📜 Veranderingen geschiedenis (samengevat)

Voor volledige history zie git log. Hier alleen de architectuurmijlpalen:

- **Fase 1** — bugfixes (mock provider disabled, FHIR2 → REST v1)
- **Fase 2** — `PersonContactService` voor patiënt contactgegevens
- **Fase 3** — Outbox relay loop (`OutboxRelayJob`)
- **Fase 4** — Reminder scheduling (24h + 1h)
- **Fase 5** — Per-tenant `RestTemplate` + provider isolatie
- **Fase 6** — 87 unit tests
- **Fase 8** — NFR compliance (timezone, PII masking, retentie, retry)
- **Fase 9** — Multi-tenant SaaS (registratie, API key auth, TenantContext)
- **Fase 10** — Email logica verwijderd (phone-only)
- **Fase 11** — Security hardening (GlobalExceptionHandler, generic errors, ObjectMapper voor JSON)
