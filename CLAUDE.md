# CLAUDE.md — BeunMRS / OpenMRS Communicatiemodule

Projectcontext voor Claude Code. Dit bestand beschrijft wat er staat, wat er nog moet, en welke keuzes al gemaakt zijn. Lees dit aan het begin van elke sessie.

---

## Wat is dit project?

Een **SaaS notificatiemodule** die naast OpenMRS draait en patiënten automatisch herinnert aan hun afspraken via externe messaging providers. De module integreert met OpenMRS via de **REST v1 API** (FHIR2 Appointment niet ondersteund in deze installatie) en verstuurt berichten via vier mock-providers (FakeComWorld).

**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL · RabbitMQ · Docker Compose · Grafana/Loki

---

## Projectstructuur

```
BeunMRS/
├── docker-compose.yml                  # Volledige stack (12 containers)
├── .env                                # Lokale secrets (nooit committen)
├── notification-service/               # De Spring Boot service
│   ├── src/main/java/com/openmrs/notification/
│   │   ├── adapter/                    # Provider adapters (SwiftSend, SecurePost, LegacyLink, AsyncFlow, Mock)
│   │   ├── consumer/                   # RabbitMQ listeners
│   │   ├── model/                      # AppointmentEvent, NotificationResult, NotificationChannel
│   │   ├── outbox/                     # OutboxService (write-before-send)
│   │   ├── poller/                     # OpenMrsAppointmentPoller (FHIR2, primair)
│   │   ├── reconciler/                 # AppointmentReconciler (REST v1, backup)
│   │   ├── service/                    # NotificationDispatcher
│   │   └── config/                     # AppConfig (RestTemplate, Jackson)
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── logback-spring.xml
│   └── src/test/                       # LEEG — tests moeten nog geschreven worden
├── infra/
│   ├── postgres/init/00_schema.sql     # DB-schema (5 tabellen)
│   ├── rabbitmq/definitions/topology.json
│   ├── loki/loki-config.yml
│   ├── promtail/promtail-config.yml
│   └── grafana/provisioning/
└── docs/
    ├── OpdrachtOpenMRS.md              # Opdrachtomschrijving
    ├── ADR-003-integratiemethode-NL.md # Architectuurbeslissing integratiemethode
    └── openmrs-appointment-flow-v2.md  # Technische flow documentatie
```

---

## Architectuurkeuzes (al vastgesteld)

### Integratiemethode — ADR-003 ✅ (bijgewerkt na test)

**Gekozen: REST v1 appointment/search + RabbitMQ (event-driven polling)**

- **Primaire poller** (`OpenMrsAppointmentPoller`): elke 2 min via `POST /ws/rest/v1/appointment/search` met 48u sliding window
- **Backup reconciliator** (`AppointmentReconciler`): elke 5 min via `GET /ws/rest/v1/appointment?lastUpdated={watermark}`
- AtomFeed afgevallen: vereist volledige Bahmni-distributie, werkt niet standalone
- Webhook (push) afgevallen: events gaan verloren bij downtime module
- **FHIR2 Appointment afgevallen** ⚠️: getest op 2026-05-20 — de FHIR2 module in deze OpenMRS installatie ondersteunt het `Appointment` resource type niet. Ondersteunde resources: Patient, Condition, Observation, Encounter, etc. — geen Appointment. De Poller is omgeschreven naar REST v1.

### Veerkrachtmechanismen ✅

| Mechanisme             | Implementatie                                                            |
| ---------------------- | ------------------------------------------------------------------------ |
| Watermark cursor       | `sync_watermarks` tabel in Postgres                                      |
| Nooit-vooruit-bij-fout | Watermark alleen opschuiven als alle afspraken verwerkt zijn             |
| Circuit breaker        | Na 5 fouten → 2 min pauze, auto-reset (in-memory)                        |
| Persist-before-publish | `outbox_events` tabel vóór RabbitMQ-publicatie                           |
| Duplicate guard        | `seen_appointments` tabel in Poller; `notification_log` in Reconciliator |

### Provider pattern ✅

`NotificationProvider` interface → alle 4 providers zijn `@Component`. `NotificationDispatcher` injecteert de lijst automatisch. Nieuwe provider toevoegen = nieuwe klasse, nul andere wijzigingen.

### Database ✅

| Tabel                 | Doel                                        |
| --------------------- | ------------------------------------------- |
| `outbox_events`       | At-least-once delivery relay                |
| `sync_watermarks`     | Poller/reconciliator cursor                 |
| `seen_appointments`   | Duplicate guard poller                      |
| `notification_log`    | Audit trail alle verzendpogingen            |
| `async_flow_commands` | Pending AsyncFlow commands (async protocol) |

### RabbitMQ topology ✅

- Exchange: `openmrs.events` (topic, durable)
- Queues: `appointments`, `appointment.cancelled` (beide met DLX)
- DLX exchange: `openmrs.events.dlx` → dead queues voor inspectie
- Routing keys: `appointment.scheduled`, `appointment.updated`, `appointment.cancelled`

---

## Wat er nog gedaan moet worden

### 🔴 KRITIEK — functioneel kapot zonder dit

#### 1. Reminder scheduling (24h + 1u)

**Bestand:** `AppointmentEventConsumer.java` moet uitgebreid worden

De opdracht vereist:

- 24 uur voor de afspraak een herinnering sturen
- 1 uur voor de afspraak een herinnering sturen
- Bij annulering: geplande reminders annuleren

**Wat er nu staat:** `dispatcher.dispatch(event)` — stuurt direct, plant niets in.

**Wat er moet komen:**

- Een `scheduled_notifications` tabel in de database (`appointment_uuid`, `type` 24h/1h, `send_at`, `status`)
- Een `ReminderScheduler` service die:
  - Bij `SCHEDULED` → twee rijen insert (`send_at = startTime - 24h` en `send_at = startTime - 1h`)
  - Bij `CANCELLED`/`UPDATED` → bestaande rijen markeren als geannuleerd
- Een `@Scheduled` job die elke minuut pollt op `send_at <= now() AND status = 'pending'` en dan verstuurt

**Keuze die nog gemaakt moet worden:** RabbitMQ delayed messages (vereist plugin) of database-based scheduler (eenvoudiger, geen plugin nodig). **Aanbeveling: database-based** — consistent met de rest van het outbox-patroon.

---

#### 2. Patiënt contactgegevens ophalen

**Bestand:** `OpenMrsAppointmentPoller.java` → methode `toEvent()`

`patientPhone` en `patientEmail` zijn altijd `null`. De REST v1 appointment response bevat alleen een `patient.uuid`. Er mist een extra call:

```
GET /ws/rest/v1/person/{patientUuid}?v=full
→ lees attributes[] → zoek attributeType.display = "Phone Number" → patientPhone
→ lees attributes[] → zoek attributeType.display = "Email" → patientEmail
```

Eerst verifiëren welke typen beschikbaar zijn:

```
GET /ws/rest/v1/personattributetype?v=default
```

Zonder dit sturen alle providers naar `"unknown"` / `null`.

Hetzelfde probleem zit in `AppointmentReconciler.java` → `mapToEvent()`. (Reconciler vult nu wél `patientName`/`appointmentTime`/`locationName`, maar nog geen phone/email.)

---

#### 3. Outbox relay loop

**Bestand:** nieuw, bijv. `outbox/OutboxRelayJob.java`

`OutboxService.writePending()` schrijft naar `outbox_events`, maar er is **geen** job die unpublished entries oppakt en opnieuw naar RabbitMQ stuurt. De at-least-once garantie is hierdoor papier.

```java
// Moet er komen:
@Scheduled(fixedDelay = 30_000)
public void relay() {
    // SELECT id, aggregate_id, event_type, payload FROM outbox_events
    // WHERE published_at IS NULL AND failed_at IS NULL
    // ORDER BY created_at LIMIT 20
    // → rabbitTemplate.convertAndSend("openmrs.events", routingKey, event)
    // → outboxService.markPublished(id)
}
```

---

### 🟡 BUGS — compileren maar gedragen zich fout

#### ~~4. `MockMessagingProvider` is enabled by default maar container bestaat niet~~ ✅ opgelost

`mock.messaging.enabled: false` toegevoegd aan `application.yml`.

#### ~~5. `AppointmentReconciler` zet altijd `EventType.SCHEDULED`~~ ✅ opgelost

`mapToEvent()` leest nu de werkelijke `status` uit het REST-response en roept `statusToEventType()` aan. Tevens worden nu ook `patientName`, `appointmentTime` en `locationName` gevuld.

#### ~~6. Duplicate import in `SwiftSendProvider.java`~~ ✅ opgelost

Eerste `NotificationChannel`-import verwijderd.

#### 7. `RestTemplate` stuurt OpenMRS Basic Auth naar FakeComWorld

`AppConfig` zet `Authorization: Basic <openmrs>` als default header op de gedeelde `RestTemplate`. Dit header gaat mee naar alle FakeComWorld-calls. Overweeg een aparte `RestTemplate` bean zonder auth voor providers (bijv. `@Qualifier("providerRestTemplate")`).

---

### ⚪ TESTS — volledig afwezig

`src/test/` is leeg. `pom.xml` bevat `spring-boot-starter-test` en `spring-rabbit-test`. Schrijf minimaal:

| Test                               | Type                                                   |
| ---------------------------------- | ------------------------------------------------------ |
| `NotificationDispatcherTest`       | Unit — mock providers, verifieer fan-out en logging    |
| `SwiftSendProviderTest`            | Unit — mock RestTemplate, verifieer headers en payload |
| `SecurePostProviderTest`           | Unit — token caching, retry op 401                     |
| `AppointmentEventConsumerTest`     | Unit — verifieer dat dispatcher aangeroepen wordt      |
| `OutboxServiceTest`                | Unit — verifieer SQL INSERT/UPDATE                     |
| `AppointmentPollerIntegrationTest` | Integration — WireMock voor FHIR endpoint              |

---

## Ports overzicht (na fixes)

| Service             | Host port | Container port |
| ------------------- | --------- | -------------- |
| OpenMRS gateway     | 80        | 80             |
| RabbitMQ management | 15672     | 15672          |
| Grafana             | 3000      | 3000           |
| Loki                | 3100      | 3100           |
| notification-svc    | 4000      | 8080           |
| FakeComWorld        | 1337      | 8080           |

---

## Bekende valkuilen

- **`comments`-veld niet beschikbaar via search API** — `POST /ws/rest/v1/appointment/search` retourneert altijd `"comments": null`, ook als de afspraak aangemaakt is met een opmerking. De code mapt het veld correct, maar deze versie van de Bahmni module levert het niet mee in de zoekrespons. Workaround: niet beschikbaar zonder een aparte `GET` per afspraak (die ook niet werkt in deze installatie). Gedocumenteerd als bekende beperking — code is klaar voor als een toekomstige versie het wél levert.
- **FHIR2 Appointment niet ondersteund** — `GET /ws/fhir2/R4/Appointment` geeft `HAPI-0302: Unknown resource type 'Appointment'`. De FHIR2 module in deze OpenMRS installatie heeft geen Appointment-mapping. De Poller gebruikt daarom `POST /ws/rest/v1/appointment/search`. Gebruik voor patiënten wél FHIR2 (`/ws/fhir2/R4/Patient/{uuid}`).
- **OpenMRS start traag** — eerste opstart duurt 5-10 minuten (Liquibase + module loading). Wacht op `Server startup in [XXXX] milliseconds` in de backend logs voordat je de UI test.
- **Container naam `openmrs-backend`** — gewijzigd van `backend` zodat Promtail de juiste `service` label geeft in Grafana (`{service="openmrs-backend"}`).
- **`OPENMRS_TAG` vs `OPENMRS_VERSION`** — `docker-compose.yml` gebruikt `${OPENMRS_TAG:-qa}`, maar `.env` had `OPENMRS_VERSION`. Gebruik `OPENMRS_TAG` in `.env` als je een specifieke versie wilt pinnen.
- **Promtail pipeline** — `output: source: message` is verwijderd. Plain-text logs (OpenMRS/Tomcat) werden anders overschreven met een lege string.
- ~~**SwiftSendProvider.java** duplicate import~~ — opgelost.

---

## Hoe starten

```powershell
# Eerste keer
docker compose up -d

# Rebuild notification-svc na code wijziging
docker compose up -d --build notification-svc

# Logs bekijken
docker compose logs -f notification-svc
docker compose logs -f backend

# Alles opnieuw
docker compose down -v
docker compose up -d
```

**URLs:**

- OpenMRS: http://localhost/openmrs (admin / Admin1234)
- RabbitMQ UI: http://localhost:15672 (rabbit / rabbit_secret)
- Grafana: http://localhost:3000 (admin / grafana_secret)
- FakeComWorld: http://localhost:1337
- Notification service health: http://localhost:4000/actuator/health

---

## Voortgang — fasen & stappen

> Vink af met `[x]` zodra een stap klaar is. Begin altijd bij de eerste onafgevinkte stap.

---

### ✅ Fase 1 — Snelle bugfixes _(< 1 uur)_

- [x] **1a.** `MockMessagingProvider` uitschakelen — `mock.messaging.enabled: false` toegevoegd aan `application.yml`
- [x] **1b.** Duplicate import verwijderd uit `SwiftSendProvider.java`
- [x] **1c.** `AppointmentReconciler.mapToEvent()` repareert — leest nu werkelijke `status`, roept `statusToEventType()` aan; vult ook `patientName`, `appointmentTime`, `locationName`
- [x] **1d.** Poller omgeschreven van FHIR2 naar `POST /ws/rest/v1/appointment/search` (vorige sessie)

---

### ✅ Fase 2 — Patiënt contactgegevens ophalen _(2-3 uur)_

> FHIR2 volledig geskipt (zie ADR-003). Alle integratie via REST v1.

**Attribuutnamen geverifieerd via `GET /ws/rest/v1/personattributetype`:**

- Telefoon → `"Telephone Number"`
- Email → `"email"` (lowercase)

- [x] **2a.** `service/PersonContactService.java` aangemaakt — `GET /ws/rest/v1/person/{uuid}?v=full`, leest `attributes[]`, zoekt op `attributeType.display`. Bevat in-memory cache (max 500 entries) om herhaalde calls binnen één poll-cyclus te vermijden.
- [x] **2b.** `OpenMrsAppointmentPoller.toEvent()` → `personContactService.enrichEvent(event)`
- [x] **2c.** `AppointmentReconciler.mapToEvent()` → `personContactService.enrichEvent(event)`. Ook `openmrsUser`/`openmrsPassword` velden verwijderd (zaten al in RestTemplate via AppConfig).
- [x] **2d.** `OutboxService.buildPayloadJson()` bijgewerkt met `patientPhone` en `patientEmail`.
- [x] **2e.** Geverifieerd: Betty Williams (uuid=4df50238) — attributen via API toegevoegd → `notification_log` bevat `phone=+31612345678` en `email=betty.williams@example.com` voor alle 4 providers.

---

### ✅ Fase 3 — Outbox relay loop _(1-2 uur)_

- [x] **3a.** Nieuwe klasse `outbox/OutboxRelayJob.java` aangemaakt met `@Scheduled(fixedDelay = 30_000)`
- [x] **3b.** Query: `SELECT * FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL ORDER BY created_at LIMIT 20`
- [x] **3c.** Per rij: `rabbitTemplate.convertAndSend(exchange, routingKey, event)` → daarna `UPDATE published_at = now()`
- [x] **3d.** Fout-afhandeling: `retry_count` ophogen, na 5 pogingen `failed_at` zetten
- [x] **3e.** Geverifieerd: relay job logt correct `geen openstaande events` bij lege queue; bij nieuwe events worden ze gepubliceerd en gemarkeerd als `published_at = now()`

---

### ✅ Fase 4 — Reminder scheduling 24h + 1u _(4-6 uur)_

- [x] **4a.** `scheduled_notifications` tabel toegevoegd aan `00_schema.sql` — slaat payload op als JSONB zodat dispatch job geen extra OpenMRS call nodig heeft
- [x] **4b.** `scheduler/ReminderScheduler.java` aangemaakt — `scheduleReminders()` insert 2 rijen (24h + 1h voor afspaaak), `cancelReminders()` zet status op 'cancelled'
- [x] **4c.** `scheduler/ReminderDispatchJob.java` aangemaakt — `@Scheduled(fixedDelay=60s)`, poll pending reminders, override eventType naar `REMINDER_24H`/`REMINDER_1H`, dispatch via NotificationDispatcher
- [x] **4d.** `AppointmentEventConsumer.java` bijgewerkt — injecteert ReminderScheduler; SCHEDULED: dispatch + schedule; UPDATED: dispatch + cancel + reschedule; CANCELLED: dispatch + cancel
- [x] **4e.** Twee nieuwe `EventType` waarden (`REMINDER_24H`, `REMINDER_1H`) toegevoegd; alle 4 providers + MockMessagingProvider bijgewerkt met reminder berichttekst
- [x] **4f.** Geverifieerd: reminders werden aangemaakt, dispatched naar alle 4 providers, `status = 'sent'` in DB, 8 entries in `notification_log`

---

### ✅ Fase 5 — Aparte RestTemplate voor providers _(1 uur)_

- [x] **5a.** `AppConfig.java` bijgewerkt: twee `@Bean`s — `openmrsRestTemplate` (met Basic Auth) en `providerRestTemplate` (zonder headers)
- [x] **5b.** `@Qualifier("openmrsRestTemplate")` toegevoegd aan `OpenMrsAppointmentPoller`, `AppointmentReconciler` en `PersonContactService`
- [x] **5c.** `@Qualifier("providerRestTemplate")` toegevoegd aan `SwiftSendProvider`, `SecurePostProvider`, `LegacyLinkProvider`, `AsyncFlowProvider` en `MockMessagingProvider`
- [x] **5d.** Geverifieerd: service start zonder bean-wiring fouten; providers werken nog correct

---

### ⚪ Fase 6 — Tests schrijven _(4-8 uur)_

- [ ] **6a.** `NotificationDispatcherTest` — mock providers, verifieer fan-out en logging in outbox
- [ ] **6b.** `SwiftSendProviderTest` — mock RestTemplate, verifieer `X-API-KEY` header en berichtinhoud
- [ ] **6c.** `SecurePostProviderTest` — token caching werkt, retry op 401 haalt nieuw token op
- [ ] **6d.** `LegacyLinkProviderTest` — SOAP envelope bevat correcte velden en XML-escaping
- [ ] **6e.** `AsyncFlowProviderTest` — command submit + status polling cyclus
- [ ] **6f.** `AppointmentEventConsumerTest` — consumer roept dispatcher + reminderScheduler aan
- [ ] **6g.** `OutboxServiceTest` — INSERT en markPublished correct
- [ ] **6h.** `ReminderSchedulerTest` — `send_at` berekening klopt, annulering zet status op 'cancelled'

---

### ⚪ Fase 8 — Opdrachtgaps oplossen _(6-10 uur)_

> Gebaseerd op gap-analyse van de volledige opdrachtomschrijving (2026-05-20).
> Onderdelen zijn gegroepeerd van snel → architectureel → security.

#### 🟡 Snel te fixen (< 30 min per stap)

- [x] **8a.** **Locatienaam + opmerkingen in berichttekst** ✅
  - `comments` veld toegevoegd aan `AppointmentEvent` + gemapped in `OpenMrsAppointmentPoller.toEvent()`.
  - Nieuwe utility `util/MessageHelper.java` met `locationSuffix()` en `commentsSuffix()`.
  - Alle 5 providers bijgewerkt: `buildMessage()` gebruikt nu `locationName` én `comments`.

- [x] **8b.** **Reminder niet versturen als afspraak al voorbij is** ✅
  - In `ReminderDispatchJob.processReminder()`: check `appointmentTime.isBefore(Instant.now())`.
  - Als al voorbij → `status = 'skipped'`, reminder overgeslagen + gelogd.

- [x] **8c.** **Patiëntdata maskeren in logs** ✅
  - `MessageHelper.mask()` toegevoegd: `"0612345678" → "061****678"`, `"a@b.com" → "a@***.com"`.
  - Toegepast in `NotificationDispatcher` (info-log) en alle provider debug-logs (SwiftSend, LegacyLink, SecurePost).

- [x] **8d.** **Tijdzone-weergave voor patiënten** ✅
  - `MessageHelper.formatTime()` converteert `Instant` naar `Europe/Amsterdam` met Nederlandse dag-/maandnamen.
  - Voorbeeld: `"maandag 24 januari 2026 om 14:30"`.
  - Toegepast in alle 5 `buildMessage()` methoden — vervangt de ruwe UTC `Instant.toString()`.

---

#### 🔴 Architectureel (1-3 uur per stap)

- [ ] **8e.** **Eén provider per organisatie (provider routing)**
  - De opdracht vereist dat elke organisatie/locatie één vaste provider gebruikt, niet fan-out naar alle 4.
  - **Aanpak:**
    1. Nieuwe tabel `organisation_provider_config (location_uuid TEXT, provider_name TEXT)`
    2. `NotificationDispatcher` leest `locationUuid` uit het event en zoekt de bijbehorende provider op.
    3. Fallback: als geen config → gebruik SwiftSend als default.
  - `AppointmentEvent` moet `locationUuid` bevatten (momenteel alleen `locationName`); ophalen via REST v1.
  - Bestanden: `NotificationDispatcher.java`, `00_schema.sql`, `AppointmentEvent.java`, poller/reconciler.

- [ ] **8f.** **14-dagenretentie — automatisch verwijderen van patiëntberichten**
  - De opdracht vereist: berichten na 14 dagen verwijderen.
  - Nieuwe klasse `scheduler/DataRetentionJob.java` met `@Scheduled(cron = "0 0 2 * * *")` (elke nacht 02:00):
    ```sql
    DELETE FROM notification_log   WHERE sent_at     < now() - interval '14 days';
    DELETE FROM outbox_events      WHERE created_at  < now() - interval '14 days';
    DELETE FROM seen_appointments  WHERE queued_at   < now() - interval '14 days';
    DELETE FROM scheduled_notifications WHERE created_at < now() - interval '14 days';
    ```
  - **Let op:** `async_flow_commands` en `sync_watermarks` NIET verwijderen — hebben geen PII.

- [ ] **8g.** **1-jaarsretentie — meta-info zonder PII**
  - De opdracht vereist: verzendstatus + tijdstip bewaren voor 1 jaar (voor audits), maar zónder PII.
  - Nieuwe tabel `notification_audit_log (id UUID, appointment_uuid TEXT, event_type TEXT, provider TEXT, status TEXT, sent_at TIMESTAMPTZ)` — geen phone/email/naam.
  - `DataRetentionJob` schrijft samenvatting naar `notification_audit_log` vóórdat `notification_log` wordt opgeschoond.
  - Audit log zelf na 1 jaar opschonen: `DELETE FROM notification_audit_log WHERE sent_at < now() - interval '1 year'`.

---

#### 🔒 Security (documentatie + configuratie)

- [ ] **8h.** **Encryptie van opgeslagen patiëntdata (AES-256)**
  - De opdracht vereist AES-256 encryptie voor persoonsgegevens at-rest.
  - **Scope in dit project:**
    - PostgreSQL `pgcrypto` extensie inschakelen voor gevoelige kolommen (`patientPhone`, `patientEmail` in `notification_log`).
    - Alternatief: disk-level encryptie (PostgreSQL TDE of OS-level). Dit is buiten scope van de applicatiecode.
    - **Minimale aanpak:** voeg toe aan `00_schema.sql`: `CREATE EXTENSION IF NOT EXISTS pgcrypto;`; sla phone/email op als `pgp_sym_encrypt(value, key)` en lees terug met `pgp_sym_decrypt`.
    - Encryptiesleutel via environment variable `DB_ENCRYPTION_KEY` (nooit hardcoded).
  - Documenteer keuze in ADR.

- [ ] **8i.** **TLS 1.3 / HTTPS**
  - De opdracht vereist versleuteld transport.
  - Intern (Docker netwerk): containers communiceren al op een intern netwerk — acceptabel voor dev.
  - Extern (naar providers): `RestTemplate` gebruikt HTTPS als de provider-URL `https://` is — al correct zodra FakeComWorld/productie-providers HTTPS gebruiken.
  - Notification service zelf: configureer Spring Boot met SSL keystore voor `https://` op poort 4000, of zet een NGINX reverse proxy ervoor.
  - Documenteer als architectuurkeuze; voor productie: NGINX + Let's Encrypt.

---

### ✅ Fase 9 — Multi-tenant SaaS registratie & configuratie _(8–14 uur)_

> Maakt de module een echte SaaS: elke tenant (ziekenhuis) heeft een eigen OpenMRS-instantie,
> eigen messaging provider en eigen API-sleutels. Zonder dit draaien alle tenants op dezelfde config.

**Architectuurkeuze:** Per-organization provider config (niet per-location). Elke ziekenhuis-organisatie kiest
één provider (bijv. SwiftSend) met één set API-keys. Poller haalt ALLe appointments op (geen location-filter);
dispatcher stuurt naar de ene provider van die organisatie.

#### 9a. Tenant datamodel — `00_schema.sql` uitbreiden

- [x] Nieuwe tabel `tenants` met velden:
  - `id UUID PRIMARY KEY`
  - `slug TEXT UNIQUE` (bijv. "amc", "lumc" — voor URLs)
  - `display_name TEXT` (bijv. "Amsterdam UMC")
  - `api_key_hash TEXT UNIQUE` (SHA-256 hash voor efficiënte lookup)
  - `api_key_enc TEXT` (AES-256-GCM encrypted raw key)
  - `openmrs_host TEXT` (https://openmrs.ziekenhuis.nl)
  - `openmrs_user TEXT` + `openmrs_password_enc TEXT` (AES-256-GCM encrypted)
  - `provider_name TEXT CHECK IN ('SwiftSend','SecurePost','LegacyLink','AsyncFlow')`
  - `provider_api_key_enc TEXT` (AES-256-GCM encrypted)
  - `provider_extra_enc TEXT` (voor JWT-secrets, HMAC-sleutels, client-ID's — optioneel)
  - `active BOOLEAN DEFAULT true`
  - `created_at TIMESTAMPTZ`

- [x] Bestaande tabellen updaten: voeg `tenant_id UUID NOT NULL REFERENCES tenants(id)` toe aan:
  - `outbox_events`
  - `notification_log`
  - `sync_watermarks`
  - `seen_appointments`
  - `scheduled_notifications`
  - `async_flow_commands`

- [x] Alle bestaande indices aanpassen; voeg where-clause toe: `WHERE tenant_id = ...` (scoped queries)

---

#### 9b. Registratie-endpoint (self-service onboarding)

Nieuwe klasse `registration/TenantRegistrationController.java`:

- [x] `POST /api/register` accepteert JSON:
  ```json
  {
    "displayName": "Amsterdam UMC",
    "slug": "amc",
    "openmrsHost": "https://openmrs.amc.nl",
    "openmrsUser": "admin",
    "openmrsPassword": "...",
    "providerName": "SwiftSend",
    "providerApiKey": "sk-swiftsend-...",
    "providerExtra": ""
  }
  ```

- [x] Validaties:
  - `slug`: alfanumeriek + uniek
  - `providerName`: één van vier
  - Wachtwoorden/sleutels: AES-256-GCM encrypted vóór opslag (key via `DB_ENCRYPTION_KEY` env var)

- [x] Response: `{ tenantId: "uuid", slug, displayName, apiKey: "saas-key-xyz" }`

- [x] Logging: `TenantCreated` event naar logs (geen credentials)

---

#### 9c. SaaS-authenticatie — tenant resolven via API key

Nieuwe klasse `security/TenantApiKeyFilter.java` (extends `OncePerRequestFilter`):

- [x] Leest `X-API-Key` header uit request
- [x] Zoekt tenant op via SHA-256 hash: `SELECT * FROM tenants WHERE api_key_hash = ?`
- [x] Zet `TenantContext.set(tenant)` (ThreadLocal) voor rest van request-lifecycle
- [x] Geeft `401 Unauthorized` als sleutel ontbreekt of onbekend
- [x] `OncePerRequestFilter` voor `/api/**` (skip `/api/register` en `/api/admin/`)

---

#### 9d. TenantContext doortrekken

Update bestaande klassen om `TenantContext.get()` te gebruiken:

- [x] **OpenMrsAppointmentPoller:**
  - In `poll()`: iterate over alle actieve tenants via `tenantService.getActiveTenants()`
  - Per tenant: zet `TenantContext.set(tenant)`, fetch appointments via `RestTemplateFactory.buildForTenant()`
  - Watermark-query, seen_appointments, outbox scoped op `tenant_id`
  - Circuit breaker per tenant-slug

- [x] **AppointmentReconciler:** dezelfde aanpak; watermark PK is nu `(resource_type, tenant_id)`

- [x] **NotificationDispatcher:**
  - Leest `TenantContext.get()` → `tenant.providerName` + decrypt credentials via `TenantService`
  - Stuurt naar ÉÉN provider per tenant (niet fan-out); fallback naar SwiftSend
  - `outbox_events.tenant_id` + `notification_log.tenant_id` automatisch gezet

- [x] **AppConfig:** `openmrsRestTemplate` bean verwijderd; vervangen door `RestTemplateFactory` (per-tenant, dynamisch)

---

#### 9e. Provider registry per tenant

Update `NotificationDispatcher`:

- [x] Provider gevonden via `List<NotificationProvider>` gefilterd op `tenant.getProviderName()`.
  `ProviderCredentials` record doorgegeven aan `provider.send(event, credentials)`.
  Alle 5 providers (SwiftSend, SecurePost, LegacyLink, AsyncFlow, Mock) bijgewerkt.
  SecurePost JWT-cache keyed op `clientId` voor multi-tenant isolatie.

---

#### 9f. Tenant-admin endpoint (operationeel beheer)

Optioneel maar handig:

- [x] `GET /api/admin/tenants` → lijst alle tenants (beveiligd met `X-Admin-Key: ${SAAS_ADMIN_KEY}`)
- [x] `DELETE /api/admin/tenants/{id}` → soft delete (`active = false`)

Master-sleutel via `SAAS_ADMIN_KEY` env var (nooit in code).

---

#### 9g. Verificatie

- [x] Tenant registreren via `POST /api/register` → `{ tenantId, slug, displayName, apiKey }` ✓
- [x] Tenant zichtbaar in DB (`tenants` tabel) met encrypted credentials ✓
- [x] Ongeldige API key → `401 Unauthorized` ✓
- [x] Geldige API key → TenantContext gezet, request verwerkt ✓
- [x] Admin endpoint `GET /api/admin/tenants` met `X-Admin-Key` header ✓
- [x] Scheduler (ReminderDispatchJob) + OutboxRelayJob verwerken per tenant ✓
- [ ] Volledige end-to-end flow met twee tenants (Fase 7)

---

### ✅ Fase 7 — Eindcontrole & oplevering _(1.5 uur)_

#### Single-tenant verificatie (Fase 1-8)

- [ ] **7a.** `docker compose down -v && docker compose up -d` — volledige stack van nul starten
- [ ] **7b.** Afspraak aanmaken in OpenMRS → volledige flow volgen in Grafana logs
- [ ] **7c.** Afspraak annuleren → verifiëren dat geplande reminders status 'cancelled' krijgen

#### Multi-tenant verificatie (Fase 9)

- [ ] **7d.** Twee tenants registreren via `POST /api/register`:
  - Tenant 1: "Amsterdam UMC" → SwiftSend
  - Tenant 2: "Leiden UMC" → SecurePost
  
- [ ] **7e.** Per tenant: afspraak aanmaken in hun OpenMRS-instantie → verifiëren:
  - Amsterdam: notificatie via SwiftSend ✓
  - Leiden: notificatie via SecurePost ✓
  - `tenant_id` correct in `notification_log`

- [ ] **7f.** Tenants isolation: 
  - Tenant A met ongeldig API key → `401 Unauthorized`
  - Tenant A mag niet tenant B's data zien (watermarks, logs)

#### Documentatie & sluiting

- [ ] **7g.** `CLAUDE.md` bijwerken — voltooide fasen (1-9) markeren met checkmarks
- [ ] **7h.** `README.md` + `docs/` bijwerken:
  - Ziekenhuis-beheerders moeten kunnen: registreren, configureren provider, monitoren
  - DevOps instructies voor draai multi-tenant setup
  - ADRs voor architectuurkeuzes (tenant model, provider routing, encryption)

- [ ] **7i.** Finale logs/screenshots: multi-tenant flow in Grafana