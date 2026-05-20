# CLAUDE.md — BeunMRS / OpenMRS Communicatiemodule

Projectcontext voor Claude Code. Dit bestand beschrijft wat er staat, wat er nog moet, en welke keuzes al gemaakt zijn. Lees dit aan het begin van elke sessie.

---

## Wat is dit project?

Een **SaaS notificatiemodule** die naast OpenMRS draait en patiënten automatisch herinnert aan hun afspraken via externe messaging providers. De module integreert met OpenMRS via de FHIR2 API en verstuurt berichten via vier mock-providers (FakeComWorld).

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
| Mechanisme | Implementatie |
|---|---|
| Watermark cursor | `sync_watermarks` tabel in Postgres |
| Nooit-vooruit-bij-fout | Watermark alleen opschuiven als alle afspraken verwerkt zijn |
| Circuit breaker | Na 5 fouten → 2 min pauze, auto-reset (in-memory) |
| Persist-before-publish | `outbox_events` tabel vóór RabbitMQ-publicatie |
| Duplicate guard | `seen_appointments` tabel in Poller; `notification_log` in Reconciliator |

### Provider pattern ✅
`NotificationProvider` interface → alle 4 providers zijn `@Component`. `NotificationDispatcher` injecteert de lijst automatisch. Nieuwe provider toevoegen = nieuwe klasse, nul andere wijzigingen.

### Database ✅
| Tabel | Doel |
|---|---|
| `outbox_events` | At-least-once delivery relay |
| `sync_watermarks` | Poller/reconciliator cursor |
| `seen_appointments` | Duplicate guard poller |
| `notification_log` | Audit trail alle verzendpogingen |
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

`patientPhone` en `patientEmail` zijn altijd `null`. De FHIR Appointment resource bevat alleen een `Patient/{uuid}` referentie. Er mist een extra call:

```
GET /ws/fhir2/R4/Patient/{patientUuid}
→ lees telecom[] array
→ system="phone" → patientPhone
→ system="email" → patientEmail
```

Zonder dit sturen alle providers naar `"unknown"` / `"unknown@example.com"`.

Hetzelfde probleem zit in `AppointmentReconciler.java` → `mapToEvent()`.

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

#### 4. `MockMessagingProvider` is enabled by default maar container bestaat niet
`mock.messaging.enabled` staat nergens in `application.yml` → default is `true` → elke dispatch probeert `http://mock-messaging:8025` te bereiken → connection error.

**Fix:** Voeg toe aan `application.yml`:
```yaml
mock:
  messaging:
    enabled: false
```

#### 5. `AppointmentReconciler` zet altijd `EventType.SCHEDULED`
`AppointmentReconciler.java:136` — ook gecancellede afspraken krijgen `SCHEDULED`. Status moet uit het REST-response worden gelezen en gemapt naar `EventType`.

#### 6. Duplicate import in `SwiftSendProvider.java`
Regels 6 en 7 importeren allebei `NotificationChannel`. Compileert wel maar veroorzaakt een waarschuwing.

#### 7. `RestTemplate` stuurt OpenMRS Basic Auth naar FakeComWorld
`AppConfig` zet `Authorization: Basic <openmrs>` als default header op de gedeelde `RestTemplate`. Dit header gaat mee naar alle FakeComWorld-calls. Overweeg een aparte `RestTemplate` bean zonder auth voor providers (bijv. `@Qualifier("providerRestTemplate")`).

---

### ⚪ TESTS — volledig afwezig

`src/test/` is leeg. `pom.xml` bevat `spring-boot-starter-test` en `spring-rabbit-test`. Schrijf minimaal:

| Test | Type |
|---|---|
| `NotificationDispatcherTest` | Unit — mock providers, verifieer fan-out en logging |
| `SwiftSendProviderTest` | Unit — mock RestTemplate, verifieer headers en payload |
| `SecurePostProviderTest` | Unit — token caching, retry op 401 |
| `AppointmentEventConsumerTest` | Unit — verifieer dat dispatcher aangeroepen wordt |
| `OutboxServiceTest` | Unit — verifieer SQL INSERT/UPDATE |
| `AppointmentPollerIntegrationTest` | Integration — WireMock voor FHIR endpoint |

---

## Ports overzicht (na fixes)

| Service | Host port | Container port |
|---|---|---|
| OpenMRS gateway | 80 | 80 |
| RabbitMQ management | 15672 | 15672 |
| Grafana | 3000 | 3000 |
| Loki | 3100 | 3100 |
| notification-svc | 4000 | 8080 |
| FakeComWorld | 1337 | 8080 |

---

## Bekende valkuilen

- **FHIR2 Appointment niet ondersteund** — `GET /ws/fhir2/R4/Appointment` geeft `HAPI-0302: Unknown resource type 'Appointment'`. De FHIR2 module in deze OpenMRS installatie heeft geen Appointment-mapping. De Poller gebruikt daarom `POST /ws/rest/v1/appointment/search`. Gebruik voor patiënten wél FHIR2 (`/ws/fhir2/R4/Patient/{uuid}`).
- **OpenMRS start traag** — eerste opstart duurt 5-10 minuten (Liquibase + module loading). Wacht op `Server startup in [XXXX] milliseconds` in de backend logs voordat je de UI test.
- **Container naam `openmrs-backend`** — gewijzigd van `backend` zodat Promtail de juiste `service` label geeft in Grafana (`{service="openmrs-backend"}`).
- **`OPENMRS_TAG` vs `OPENMRS_VERSION`** — `docker-compose.yml` gebruikt `${OPENMRS_TAG:-qa}`, maar `.env` had `OPENMRS_VERSION`. Gebruik `OPENMRS_TAG` in `.env` als je een specifieke versie wilt pinnen.
- **Promtail pipeline** — `output: source: message` is verwijderd. Plain-text logs (OpenMRS/Tomcat) werden anders overschreven met een lege string.
- **SwiftSendProvider.java** heeft een duplicate import op regel 6/7 (`NotificationChannel` twee keer geïmporteerd).

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

### 🔴 Fase 1 — Snelle bugfixes *(< 1 uur)*

- [ ] **1a.** `MockMessagingProvider` uitschakelen — voeg toe aan `application.yml`:
  ```yaml
  mock:
    messaging:
      enabled: false
  ```
- [ ] **1b.** Duplicate import verwijderen in `SwiftSendProvider.java` (regel 6 of 7 — `NotificationChannel` staat twee keer)
- [ ] **1c.** `AppointmentReconciler.java:136` repareren — lees `status` uit het REST-response en map naar `EventType` i.p.v. altijd `SCHEDULED`

---

### 🔴 Fase 2 — Patiënt contactgegevens ophalen *(2-3 uur)*

- [ ] **2a.** Extra FHIR call toevoegen in `OpenMrsAppointmentPoller.toEvent()`:
  ```
  GET /ws/fhir2/R4/Patient/{patientUuid}
  → lees telecom[] → system="phone" → patientPhone
  → lees telecom[] → system="email" → patientEmail
  ```
- [ ] **2b.** Zelfde ophaallogica toevoegen in `AppointmentReconciler.mapToEvent()`
- [ ] **2c.** Verifiëren: afspraak aanmaken in OpenMRS → controleer in `notification_log` dat phone/email ingevuld zijn

---

### 🔴 Fase 3 — Outbox relay loop *(1-2 uur)*

- [ ] **3a.** Nieuwe klasse `outbox/OutboxRelayJob.java` aanmaken met `@Scheduled(fixedDelay = 30_000)`
- [ ] **3b.** Query: `SELECT * FROM outbox_events WHERE published_at IS NULL AND failed_at IS NULL ORDER BY created_at LIMIT 20`
- [ ] **3c.** Per rij: `rabbitTemplate.convertAndSend(exchange, routingKey, event)` → daarna `outboxService.markPublished(id)`
- [ ] **3d.** Fout-afhandeling: `retry_count` ophogen, na 5 pogingen `failed_at` zetten
- [ ] **3e.** Verifiëren: RabbitMQ tijdelijk stoppen → afspraak aanmaken → RabbitMQ herstart → event alsnog verwerkt

---

### 🔴 Fase 4 — Reminder scheduling 24h + 1u *(4-6 uur)*

- [ ] **4a.** DB-migratie toevoegen aan `00_schema.sql`:
  ```sql
  CREATE TABLE scheduled_notifications (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_uuid TEXT NOT NULL,
    type             TEXT NOT NULL,   -- '24h' | '1h'
    send_at          TIMESTAMPTZ NOT NULL,
    status           TEXT NOT NULL DEFAULT 'pending',  -- pending | sent | cancelled
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE INDEX ON scheduled_notifications (send_at) WHERE status = 'pending';
  ```
- [ ] **4b.** Nieuwe klasse `scheduler/ReminderScheduler.java`:
  - `scheduleReminders(AppointmentEvent)` → insert 2 rijen (`startTime - 24h` en `startTime - 1h`)
  - `cancelReminders(String appointmentUuid)` → UPDATE status = 'cancelled'
- [ ] **4c.** Nieuwe klasse `scheduler/ReminderDispatchJob.java` met `@Scheduled(fixedDelay = 60_000)`:
  - Poll `WHERE send_at <= now() AND status = 'pending'`
  - Haal volledige afspraakdata op uit OpenMRS (statuscheck: nog Scheduled?)
  - Dispatch via `NotificationDispatcher` → UPDATE status = 'sent'
- [ ] **4d.** `AppointmentEventConsumer.java` aanpassen:
  - Bij `SCHEDULED`: `reminderScheduler.scheduleReminders(event)` + direct bevestigingsbericht
  - Bij `CANCELLED` / `UPDATED`: `reminderScheduler.cancelReminders(event.getAppointmentUuid())`
- [ ] **4e.** Verifiëren: afspraak aanmaken → 2 rijen in `scheduled_notifications` → wacht op `send_at` → rij op 'sent' + log aanwezig

---

### 🟡 Fase 5 — Aparte RestTemplate voor providers *(1 uur)*

- [ ] **5a.** In `AppConfig.java`: tweede `@Bean @Qualifier("providerRestTemplate")` aanmaken zónder OpenMRS Basic Auth header
- [ ] **5b.** Bestaande bean hernoemen naar `@Qualifier("openmrsRestTemplate")`
- [ ] **5c.** `@Qualifier("openmrsRestTemplate")` toevoegen aan constructor van `OpenMrsAppointmentPoller` en `AppointmentReconciler`
- [ ] **5d.** `@Qualifier("providerRestTemplate")` toevoegen aan constructors van alle 4 providers en `MockMessagingProvider`
- [ ] **5e.** Verifiëren: FakeComWorld request logs bevatten geen `Authorization: Basic` header meer

---

### ⚪ Fase 6 — Tests schrijven *(4-8 uur)*

- [ ] **6a.** `NotificationDispatcherTest` — mock providers, verifieer fan-out en logging in outbox
- [ ] **6b.** `SwiftSendProviderTest` — mock RestTemplate, verifieer `X-API-KEY` header en berichtinhoud
- [ ] **6c.** `SecurePostProviderTest` — token caching werkt, retry op 401 haalt nieuw token op
- [ ] **6d.** `LegacyLinkProviderTest` — SOAP envelope bevat correcte velden en XML-escaping
- [ ] **6e.** `AsyncFlowProviderTest` — command submit + status polling cyclus
- [ ] **6f.** `AppointmentEventConsumerTest` — consumer roept dispatcher + reminderScheduler aan
- [ ] **6g.** `OutboxServiceTest` — INSERT en markPublished correct
- [ ] **6h.** `ReminderSchedulerTest` — `send_at` berekening klopt, annulering zet status op 'cancelled'

---

### ✅ Fase 7 — Eindcontrole & oplevering *(1 uur)*

- [ ] **7a.** `docker compose down -v && docker compose up -d` — volledige stack van nul starten
- [ ] **7b.** Afspraak aanmaken in OpenMRS → volledige flow volgen in Grafana logs
- [ ] **7c.** Afspraak annuleren → verifiëren dat geplande reminders status 'cancelled' krijgen
- [ ] **7d.** `CLAUDE.md` bijwerken — voltooide fasen markeren
- [ ] **7e.** `README.md` controleren op actualiteit
