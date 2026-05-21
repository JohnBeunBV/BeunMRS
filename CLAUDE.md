# CLAUDE.md — BeunMRS / OpenMRS Communicatiemodule

Projectcontext voor Claude Code. Dit bestand beschrijft wat er staat, wat er nog moet, en welke keuzes al gemaakt zijn. Lees dit aan het begin van elke sessie.

---

## Wat is dit project?

Een **multi-tenant SaaS notificatiemodule** die naast OpenMRS draait en patiënten automatisch herinnert aan hun afspraken via externe messaging providers. Elke tenant (ziekenhuis) heeft een eigen OpenMRS-instantie, eigen API-sleutels en eigen messaging provider. De module integreert met OpenMRS via de **REST v1 API** (FHIR2 Appointment niet ondersteund in deze installatie) en verstuurt berichten via vier mock-providers (FakeComWorld).

**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL · RabbitMQ · Docker Compose · Grafana/Loki · React (Vite)

---

## Projectstructuur

```
BeunMRS/
├── docker-compose.yml                  # Volledige stack (13 containers)
├── .env                                # Lokale secrets (nooit committen)
├── frontend/                           # React registratiescherm (Vite + Nginx)
│   ├── src/components/RegisterForm.jsx # Self-service tenant onboarding UI
│   └── nginx.conf                      # Proxy /api/* → notification-svc:8080
├── notification-service/               # De Spring Boot service
│   ├── src/main/java/com/openmrs/notification/
│   │   ├── adapter/                    # Provider adapters (SwiftSend, SecurePost, LegacyLink, AsyncFlow, Mock)
│   │   ├── config/                     # AppConfig (RabbitMQ converter), RestTemplateFactory
│   │   ├── consumer/                   # RabbitMQ listeners (AppointmentEventConsumer)
│   │   ├── model/                      # AppointmentEvent, NotificationResult, ProviderCredentials
│   │   ├── outbox/                     # OutboxService, OutboxRelayJob
│   │   ├── poller/                     # OpenMrsAppointmentPoller (REST v1, per-tenant)
│   │   ├── reconciler/                 # AppointmentReconciler (backup poller)
│   │   ├── scheduler/                  # ReminderScheduler, ReminderDispatchJob
│   │   ├── security/                   # AesEncryptionService (AES-256-GCM)
│   │   ├── service/                    # NotificationDispatcher, PersonContactService
│   │   ├── tenant/                     # Tenant, TenantService, TenantContext, TenantApiKeyFilter,
│   │   │                               # TenantRegistrationController, TenantAdminController
│   │   └── util/                       # MessageHelper (formatTime, mask, commentsSuffix, locationSuffix)
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── logback-spring.xml
│   └── src/test/                       # LEEG — tests nog te schrijven (Fase 6)
├── infra/
│   ├── postgres/init/00_schema.sql     # DB-schema (8 tabellen + pgcrypto)
│   ├── rabbitmq/definitions/topology.json
│   ├── loki/loki-config.yml
│   ├── promtail/promtail-config.yml
│   └── grafana/provisioning/
└── docs/
    ├── OpdrachtOpenMRS.md
    ├── ADR-003-integratiemethode-NL.md
    └── openmrs-appointment-flow-v2.md
```

---

## Architectuurkeuzes (vastgesteld)

### Integratiemethode — ADR-003 ✅

**Gekozen: REST v1 appointment/search + RabbitMQ (event-driven polling)**

- **Primaire poller** (`OpenMrsAppointmentPoller`): elke 2 min via `POST /ws/rest/v1/appointment/search`, 30-daags sliding window, itereert over alle actieve tenants
- **Backup reconciliator** (`AppointmentReconciler`): elke 5 min — let op: heeft een bekende 500-bug (zie Bekende valkuilen)
- AtomFeed afgevallen: vereist volledige Bahmni-distributie
- Webhook afgevallen: events gaan verloren bij downtime
- **FHIR2 Appointment afgevallen**: `GET /ws/fhir2/R4/Appointment` → `HAPI-0302: Unknown resource type 'Appointment'`

### Multi-tenant architectuur ✅

Elke tenant (ziekenhuis) heeft:
- Eigen OpenMRS-instantie (`openmrs_host` per tenant in DB)
- Eigen credentials (AES-256-GCM encrypted in DB)
- Één vaste messaging provider (SwiftSend, SecurePost, LegacyLink of AsyncFlow)
- Eigen API-key voor de SaaS-laag (`X-API-Key` header)

Poller itereert over alle actieve tenants en bouwt per tenant een `RestTemplate` via `RestTemplateFactory`. `TenantContext` (ThreadLocal) draagt de tenant door de hele request/job-lifecycle.

### Provider pattern ✅

`NotificationProvider` interface met `send(event, credentials)`. Per tenant wordt **één** provider gekozen op basis van `tenant.providerName`. `ProviderCredentials` bevat de runtime-gedecrypteerde sleutels — geen hardcoded config meer. Nieuwe provider = nieuwe klasse, nul andere wijzigingen.

### Veerkrachtmechanismen ✅

| Mechanisme             | Implementatie                                                                 |
| ---------------------- | ----------------------------------------------------------------------------- |
| Circuit breaker        | Na 5 fouten → 2 min pauze, per tenant-slug, in-memory                        |
| Persist-before-publish | `outbox_events` tabel vóór RabbitMQ-publicatie                                |
| Outbox relay           | `OutboxRelayJob` herprobeert elke 30s, max 5 pogingen, dan `failed_at`        |
| Duplicate guard        | `seen_appointments` tabel, scoped op `(appointment_uuid, tenant_id)`          |
| Reminder scheduling    | `scheduled_notifications` tabel, `ReminderDispatchJob` pollt elke 60s        |
| Provider retry ✅       | `FailedNotificationRetryJob` — elke 60s, max 3 pogingen, exponential backoff: 5 → 15 → 45 min, dan `permanently_failed` |

### Database ✅ (8 tabellen)

| Tabel                   | Doel                                                    |
| ----------------------- | ------------------------------------------------------- |
| `tenants`                  | SaaS-registry: credentials, provider-keuze, timezone, API-sleutel |
| `outbox_events`            | At-least-once delivery relay                                        |
| `sync_watermarks`          | Poller/reconciliator cursor per tenant                              |
| `seen_appointments`        | Duplicate guard poller                                              |
| `notification_log`         | Audit trail alle verzendpogingen (PII gemaskeerd)                   |
| `scheduled_notifications`  | Geplande 24h + 1h reminders met JSONB payload                       |
| `async_flow_commands`      | Pending AsyncFlow commands (async protocol)                         |
| `notification_audit_log`   | PII-vrij logboek voor factuurcontrole (1 jaar retentie, NFR-11)     |

### RabbitMQ topology ✅

- Exchange: `openmrs.events` (topic, durable)
- Queues: `appointments`, `appointment.cancelled` (beide met DLX)
- DLX: `openmrs.events.dlx` → dead queues voor inspectie
- Routing keys: `appointment.scheduled`, `appointment.updated`, `appointment.cancelled`

---

## Ports overzicht

| Service                    | Host port | Container port |
| -------------------------- | --------- | -------------- |
| OpenMRS gateway            | 80        | 80             |
| RabbitMQ management        | 15672     | 15672          |
| Grafana                    | 3000      | 3000           |
| Loki                       | 3100      | 3100           |
| notification-svc           | 4000      | 8080           |
| FakeComWorld               | 1337      | 8080           |
| notification-frontend (React) | 3001   | 80             |

---

## Bekende valkuilen

- **`comments` via search API altijd null** — `POST /ws/rest/v1/appointment/search` retourneert altijd `"comments": null`. Opgelost: `enrichComments()` doet een extra `GET /ws/rest/v1/appointment?uuid={uuid}` voor nieuwe/gewijzigde afspraken. Dit werkt correct — comments worden meegegeven in alle berichten en reminder-payloads.
- **AppointmentReconciler 500-bug** — ✅ Opgelost. Was: `GET ?lastUpdated=...` → 500. Nu: `POST /ws/rest/v1/appointment/search` (zelfde als primaire poller).
- **FHIR2 Appointment niet ondersteund** — `GET /ws/fhir2/R4/Appointment` → `HAPI-0302`. De FHIR2 module heeft geen Appointment-mapping. Poller gebruikt REST v1.
- **OpenMRS start traag** — eerste opstart 5-10 minuten (Liquibase + module loading). Wacht op `Server startup in [XXXX] milliseconds`.
- **Schone herstart na schema-wijziging** — bij wijzigingen aan `00_schema.sql` altijd `docker compose down -v && docker compose up -d` uitvoeren. Volumes worden niet automatisch bijgewerkt.
- **AES-256 dev-sleutel** — `AesEncryptionService` gebruikt een hardcoded fallback dev-sleutel als `DB_ENCRYPTION_KEY` niet gezet is. Nooit in productie zonder deze env var.
- **`saas.admin-key` default** — `TenantAdminController` heeft default `admin-secret` als `SAAS_ADMIN_KEY` niet gezet is in `.env`. In productie altijd overschrijven.

---

## Hoe starten

```powershell
# Eerste keer (of na schema-wijziging)
docker compose down -v
docker compose up -d

# Rebuild notification-svc na code wijziging
docker compose build notification-svc
docker compose up -d notification-svc

# Rebuild frontend na UI wijziging
docker compose build notification-frontend
docker compose up -d notification-frontend

# Logs bekijken
docker compose logs -f notification-svc
docker compose logs -f openmrs-backend

# Alles opnieuw
docker compose down -v && docker compose up -d
```

**URLs:**
- OpenMRS: http://localhost/openmrs (admin / Admin1234)
- Tenant registratie UI: **http://localhost:3001**
- RabbitMQ UI: http://localhost:15672 (rabbit / rabbit_secret)
- Grafana: http://localhost:3000 (admin / grafana_secret)
- FakeComWorld: http://localhost:1337
- Notification service health: http://localhost:4000/actuator/health

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

**Afspraak aanmaken (Postman):**
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

## Voortgang — fasen & stappen

---

### 🎯 Prioriteiten — Nog te doen (in volgorde van aanpak)

> Werk dit van boven naar beneden af. Tier 1 eerst — zonder deze stappen mist het systeem verplichte NFRs.

#### 🔴 TIER 1 — Kritieke code (eerst aanpakken)

- [x] **8m** — Provider-level retry bij 429/503 ✅ **(NFR-6 + NFR-7)**
  - `FailedNotificationRetryJob` in `scheduler/` — `@Scheduled(fixedDelay=60s)`
  - Exponential backoff: poging 1 → +5 min, poging 2 → +15 min, poging 3 → `permanently_failed`
  - `notification_log`: `retry_count INT DEFAULT 0` + `next_retry_at TIMESTAMPTZ` + partial index
  - `OutboxService.buildPayloadJson()` gebruikt nu ObjectMapper + slaat non-PII velden op voor retry-reconstructie
  - Phone/email worden bij retry opnieuw opgehaald via `PersonContactService` (waren gemaskeerd — NFR-5)
- [x] **8j** — AppointmentReconciler 500-bug gefixed ✅
  - Was: `GET ?lastUpdated=...` → HTTP 500 (endpoint vereist `?uuid`)
  - Nu: `POST /ws/rest/v1/appointment/search` met window `watermark → now+30 dagen` (zelfde als primaire poller)

#### 🟠 TIER 2 — Verplichte deliverables (opdracht)

- [ ] **7h** — `docs/README-beheerder.md` schrijven **(Deliverable 1 — technische documentatie)**
  - Koppeling met OpenMRS (REST v1, poller-interval, credentials)
  - Stap-voor-stap tenant registreren + eerste afspraak testen
  - Beveiliging: API key rotatie, AES-256, TLS 1.3 productie-setup (NGINX + Let's Encrypt)
  - Monitoring: Grafana URLs, dashboards, alerts
  - Charactersets (UTF-8), HL7 ACK-equivalent uitleg
- [ ] **7i** — Root `README.md` bijwerken **(Deliverable 2 — opstartinstructies)**
  - Vereisten (Docker, poorten), `docker compose up -d`, voorbeeld-request, productie-env vars
- ~~**ADR-005/006/007**~~ — ✅ **Niet nodig als aparte bestanden** — ADR-001 t/m ADR-004 dekken alle architectuurkeuzes af. De logica van multi-tenant, provider routing en encryptie staat gedocumenteerd in de bestaande ADRs en CLAUDE.md zelf.
- ~~**7m–7p**~~ — ✅ **C4-diagrammen bestaan al** — L1, L2, L3 en procesvisualisatie zijn aanwezig.
- [ ] **7q** — Realisatielogboek: gebruikte ontwikkeltools **(Deliverable 4)**
- [ ] **7r** — Realisatielogboek: AI-tools + representatieve voorbeelden **(Deliverable 4)**
- [ ] **7s** — Realisatielogboek: commits per teamlid **(Deliverable 4)**

#### 🟡 TIER 3 — Tests + compliance-documentatie

- [ ] **6a–6j** — Unit tests schrijven **(Deliverable 5 — testrapportage)**
  - Zie uitgewerkte lijst in Fase 6 hieronder
- [ ] **8i** — TLS 1.3 / HTTPS documentatie (NFR-5) — in `README-beheerder.md` verwerken
- [ ] **8k** — Karaktersets aantonen (NFR-8) — UTF-8 end-to-end bewijzen + testbericht niet-Latijnse tekens
- [ ] **8l** — HL7 ACK-mechanisme documenteren (NFR-6) — uitleggen als HL7-ACK-equivalent
- [ ] **7t** — Testrapport schrijven (scenario's, resultaten, dekking)
- [ ] **7u** — Uitbreidbaarheid aantonen (nieuwe provider = alleen nieuwe klasse)

#### 🟢 TIER 4 — End-to-end verificatie (laatste stap)

- [ ] **7a** — `docker compose down -v && docker compose up -d` — schone start met nieuw schema
- [ ] **7b** — Tenant registreren via http://localhost:3001 of Postman
- [ ] **7c** — Afspraak aanmaken → logs → `scheduled_notifications` controleren
- [ ] **7d** — Afspraak annuleren → reminders krijgen `status = 'cancelled'`
- [ ] **7e** — Twee tenants registreren (SwiftSend + SecurePost)
- [ ] **7f** — Per tenant afspraak → correcte provider in logs + `tenant_id` in `notification_log`
- [ ] **7g** — Tenant A API key → alleen tenant A data zichtbaar

---

### ✅ Afgerond — Fase 1 — Snelle bugfixes

- [x] **1a.** `MockMessagingProvider` uitschakelen — `mock.messaging.enabled: false`
- [x] **1b.** Duplicate import verwijderd uit `SwiftSendProvider.java`
- [x] **1c.** `AppointmentReconciler.mapToEvent()` — leest werkelijke `status`, vult `patientName`, `appointmentTime`, `locationName`
- [x] **1d.** Poller omgeschreven van FHIR2 naar `POST /ws/rest/v1/appointment/search`

---

### ✅ Afgerond — Fase 2 — Patiënt contactgegevens ophalen

**Attribuutnamen geverifieerd via `GET /ws/rest/v1/personattributetype`:**
- Telefoon → `"Telephone Number"`
- Email → `"email"` (lowercase)

- [x] **2a.** `PersonContactService.java` — `GET /ws/rest/v1/person/{uuid}?v=full`, leest `attributes[]`, in-memory cache (max 500 entries)
- [x] **2b.** Poller `toEvent()` → `personContactService.enrichEvent(event)`
- [x] **2c.** Reconciler `mapToEvent()` → `personContactService.enrichEvent(event)`
- [x] **2d.** `OutboxService.buildPayloadJson()` bijgewerkt met `patientPhone` en `patientEmail`
- [x] **2e.** Geverifieerd: Betty Williams — `notification_log` bevat phone + email voor alle providers

---

### ✅ Afgerond — Fase 3 — Outbox relay loop

- [x] **3a.** `OutboxRelayJob.java` — `@Scheduled(fixedDelay = 30_000)`, batch 20, per tenant
- [x] **3b.** Query scoped op `tenant_id`, `published_at IS NULL AND failed_at IS NULL`
- [x] **3c.** Per rij: TenantContext zetten, `rabbitTemplate.convertAndSend()`, `published_at = now()`
- [x] **3d.** Fout-afhandeling: `retry_count` ophogen, na 5 pogingen `failed_at` zetten
- [x] **3e.** Geverifieerd: relay werkt correct

---

### ✅ Afgerond — Fase 4 — Reminder scheduling 24h + 1u

- [x] **4a.** `scheduled_notifications` tabel — JSONB payload zodat dispatch job geen extra OpenMRS-call nodig heeft
- [x] **4b.** `ReminderScheduler.java` — `scheduleReminders()` (2 rijen), `cancelReminders()` (per tenant_id)
- [x] **4c.** `ReminderDispatchJob.java` — elke 60s, TenantContext per rij, skip als afspraak al voorbij (`status = 'skipped'`)
- [x] **4d.** `AppointmentEventConsumer.java` — SCHEDULED: dispatch + schedule; UPDATED: cancel + reschedule; CANCELLED: cancel
- [x] **4e.** `REMINDER_24H` / `REMINDER_1H` EventTypes — alle providers bijgewerkt met reminder berichttekst
- [x] **4f.** Geverifieerd: reminders aangemaakt, verstuurd, `status = 'sent'` in DB

---

### ✅ Afgerond — Fase 5 — Per-tenant RestTemplate + provider isolatie

- [x] **5a.** `RestTemplateFactory.java` — bouwt per poll-cyclus een `RestTemplate` met de credentials van die tenant
- [x] **5b.** `AppConfig.java` — `openmrsRestTemplate` singleton verwijderd; `jsonMessageConverter(ObjectMapper)` gebruikt Spring-managed ObjectMapper voor consistente serialisatie
- [x] **5c.** `@Qualifier("providerRestTemplate")` op alle providers — OpenMRS-credentials lekken nooit naar FakeComWorld
- [x] **5d.** Geverifieerd: service start correct, providers werken

---

### 🟡 TIER 3 — Fase 6 — Tests schrijven _(4-8 uur)_ — zie prioriteiten hierboven

- [ ] **6a.** `NotificationDispatcherTest` — mock providers, verifieer routing naar één provider per tenant
- [ ] **6b.** `SwiftSendProviderTest` — mock RestTemplate, verifieer `X-API-KEY` header en berichtinhoud
- [ ] **6c.** `SecurePostProviderTest` — token caching per clientId, retry op 401
- [ ] **6d.** `LegacyLinkProviderTest` — SOAP envelope bevat correcte velden en XML-escaping
- [ ] **6e.** `AsyncFlowProviderTest` — command submit + status polling cyclus
- [ ] **6f.** `AppointmentEventConsumerTest` — consumer zet TenantContext, roept dispatcher + reminderScheduler aan
- [ ] **6g.** `OutboxServiceTest` — INSERT en markPublished scoped op tenant_id
- [ ] **6h.** `ReminderSchedulerTest` — `send_at` berekening klopt, annulering scoped op tenant_id
- [ ] **6i.** `TenantRegistrationControllerTest` — validaties, encryptie, response
- [ ] **6j.** `AesEncryptionServiceTest` — encrypt/decrypt round-trip, null-handling

---

### ✅ Afgerond — Fase 8 — Opdrachtgaps oplossen (openstaande items in Tier 1 hierboven)

#### ✅ Snel opgelost

- [x] **8a.** **Locatienaam + opmerkingen in berichttekst**
  - `comments` veld in `AppointmentEvent`, gemapped in poller `toEvent()`
  - `enrichComments()` in poller haalt comments op via `GET /ws/rest/v1/appointment?uuid={uuid}`
  - `MessageHelper.locationSuffix()` + `commentsSuffix()` — alle 5 providers bijgewerkt

- [x] **8b.** **Reminder overslaan als afspraak al voorbij is**
  - `ReminderDispatchJob.processReminder()`: check `appointmentTime.isBefore(Instant.now())` → `status = 'skipped'`

- [x] **8c.** **PII maskeren in logs**
  - `MessageHelper.mask()`: `"+31612345678"` → `"+316****678"`, `"betty@example.com"` → `"b****@example.com"`
  - Toegepast in `NotificationDispatcher` + alle provider debug-logs

- [x] **8d.** **Tijdzone-weergave voor patiënten (NFR-13)** ✅
  - `MessageHelper.formatTime(Instant, String timezone)` — per-tenant IANA timezone, fallback Europe/Amsterdam
  - `tenants.timezone` kolom toegevoegd (DEFAULT 'Europe/Amsterdam'); validatie in `TenantRegistrationController`
  - `AppointmentEvent.timezone` veld; `NotificationDispatcher` propageert tenant-timezone naar event vóór dispatch
  - Alle 5 providers bijgewerkt: `formatTime(event.getAppointmentTime(), event.getTimezone())`
  - Geverifieerd: tenant met `Asia/Singapore` → tijden in SGT; invalide timezone → HTTP 400

- [x] **8e.** **Eén provider per organisatie** → opgelost via Fase 9 (tenant-model)
  - `NotificationDispatcher` stuurt naar ÉÉN provider op basis van `tenant.providerName`; geen fan-out meer

- [x] **8f.** **14-dagenretentie (NFR-10)** ✅
  - `scheduler/DataRetentionJob.java` — `@Scheduled(cron = "0 0 2 * * *")` (elke nacht 02:00)
  - Verwijdert rijen ouder dan 14 dagen uit: `notification_log`, `outbox_events`, `seen_appointments`, `scheduled_notifications`
  - `async_flow_commands` en `sync_watermarks` worden NIET verwijderd (geen PII)

- [x] **8g.** **1-jaarsretentie audit log (NFR-11)** ✅
  - Nieuwe tabel `notification_audit_log` — alleen: tenant_id, appointment_uuid, event_type, provider, status, sent_at (geen phone/email/naam)
  - `DataRetentionJob.archiveToAuditLog()` vult de audit log vóór de 14-daagse DELETE (idempotent via NOT EXISTS check)
  - `DataRetentionJob.purgeOldAuditLog()` verwijdert audit-rijen ouder dan 1 jaar

- [x] **8h.** **PII masking in notification_log (NFR-5)** ✅
  - `OutboxService.buildPayloadJson()` gebruikt nu `MessageHelper.mask()` voor `patientPhone` en `patientEmail`
  - Opgeslagen in DB: `"061****678"` en `"b****@example.com"` — nooit plaintext contactgegevens in logs
  - Werkelijke contactgegevens alleen in geheugen tijdens verzending

- [ ] **8i.** **TLS 1.3 / HTTPS documentatie (NFR-5)** — intern Docker-netwerk acceptabel voor dev; extern: NGINX reverse proxy + Let's Encrypt voor productie. Minimaal beschrijven in technische documentatie hoe dit ingericht wordt.

- [ ] **8j.** **AppointmentReconciler 500-bug fixen** — reconciler roept `GET /ws/rest/v1/appointment?lastUpdated=...` aan maar die endpoint vereist `?uuid`. Geeft 500. Oplossing: reconciler omschrijven zodat hij per watermark een `POST /ws/rest/v1/appointment/search` doet (zelfde als primaire poller), of uitschakelen via config.

- [ ] **8k.** **Karaktersets aantonen (NFR-8)** — database is UTF-8 (`LC_COLLATE`, `LC_CTYPE`), Spring Boot gebruikt UTF-8, RabbitMQ messages zijn JSON met UTF-8. Dit toevoegen als opmerking in technische documentatie + aantonen via een testbericht met niet-Latijnse tekens (bijv. Arabisch/Chinees).

- [ ] **8l.** **HL7 ACK-mechanisme documenteren (NFR-6)** — de opdracht noemt expliciet acknowledgements. RabbitMQ `auto-ack` + de `notification_log`-status dekt dit functioneel. Dit moet uitgelegd worden in de technische documentatie als de HL7-ACK-equivalent binnen onze architectuur.

- [ ] **8m.** **Provider-level retry bij 429/503 (NFR-6 + NFR-7)** — vereist door:
  - NFR-6: *"Queueing en retry-mechanismen bij netwerkproblemen"*
  - NFR-7: *"Downtime bij communicatieproviders... dient te worden opgevangen door een fallback- of retrymechanisme"*
  - Huidige situatie: `notification_log` entries met `status = 'failed'` worden nooit herproeeerd. Een 503 van FakeComWorld/echte provider = permanent verloren bericht.
  - Oplossing: nieuwe `FailedNotificationRetryJob` (`@Scheduled`) die `notification_log` rijen met `status = 'failed'` en `retry_count < 3` herprobeert, met exponential backoff (5 min → 15 min → 45 min). Na 3× → permanent `failed`.
  - Vereist: `retry_count INT DEFAULT 0` + `next_retry_at TIMESTAMPTZ` kolommen op `notification_log`.

---

### ✅ Afgerond — Fase 9 — Multi-tenant SaaS registratie & configuratie

#### 9a. Tenant datamodel ✅
- `tenants` tabel: id, slug, display_name, api_key_hash (SHA-256), api_key_enc (AES-256-GCM), openmrs_host, openmrs_user, openmrs_password_enc, provider_name (CHECK constraint), provider_api_key_enc, provider_extra_enc, **timezone** (IANA, DEFAULT 'Europe/Amsterdam'), active, created_at
- Alle 6 overige tabellen hebben `tenant_id UUID NOT NULL REFERENCES tenants(id)`
- Indices scoped op `tenant_id`

#### 9b. Registratie-endpoint ✅
- `POST /api/register` — geen auth vereist
- Validaties: slug (alphanumeriek), displayName, openmrsHost, openmrsUser, openmrsPassword, providerName (één van vier), providerApiKey
- Alle secrets encrypted vóór opslag; response bevat `{ tenantId, slug, displayName, apiKey }`
- UI via React op http://localhost:3001

#### 9c. API key authenticatie ✅
- `TenantApiKeyFilter` — `OncePerRequestFilter` op `/api/**`
- SHA-256 hash lookup (O(1) zonder decryptie)
- `TenantContext.set(tenant)` voor rest van lifecycle; `clear()` in finally
- Uitgezonderd: `/api/register`, `/api/admin/**`

#### 9d. TenantContext doortrekken ✅
- Poller: itereert actieve tenants, `RestTemplateFactory.buildForTenant()` per cyclus, circuit breaker per slug
- Reconciler: zelfde patroon
- Consumer: TenantContext gezet vanuit `event.getTenantId()`
- ReminderDispatchJob: TenantContext per reminder-rij via `tenantService.findById(tenantId)`
- OutboxRelayJob: TenantContext per outbox-rij

#### 9e. Provider registry per tenant ✅
- `ProviderCredentials(apiKey, extra)` record — runtime gedecrypteerd
- Dispatcher filtert `List<NotificationProvider>` op `tenant.getProviderName()`; fallback naar SwiftSend
- SecurePost JWT-cache keyed op `clientId` — meerdere tenants met SecurePost zijn geïsoleerd

#### 9f. Admin endpoints ✅
- `GET /api/admin/tenants` — beveiligd met `X-Admin-Key: ${saas.admin-key}`
- `DELETE /api/admin/tenants/{id}` — soft delete via `active = false`

#### 9g. Verificatie ✅ (met schone DB)
- Tenant registreren → `{ tenantId, slug, displayName, apiKey }` ✓
- Ongeldige API key → `401 Unauthorized` ✓
- Admin endpoint met `X-Admin-Key` ✓
- ReminderDispatchJob + OutboxRelayJob verwerken per tenant ✓
- [ ] Volledige end-to-end flow met twee tenants (onderdeel van Fase 7)

---

### ℹ️ Fase 7 — Eindcontrole & oplevering (verwerkt in Tier 2/4 prioriteiten hierboven)

> Alle Fase 7 taken staan uitgesplitst in de prioriteiten-tabel bovenaan.
> - **ADR-logboek (Deliverable 3)**: ✅ compleet — ADR-001 t/m ADR-004 zijn aanwezig en dekken alle keuzes.
> - **C4-diagrammen + procesvisualisatie (Deliverable 3)**: ✅ al aanwezig.
