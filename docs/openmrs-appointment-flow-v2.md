# OpenMRS Communicatiemodule — Appointment Flow

## Bevindingen & technische keuzes

### AtomFeed vs Polling

Tijdens onderzoek zijn twee integratiemethoden onderzocht om nieuwe en gewijzigde afspraken te detecteren:

#### AtomFeed (event-driven)
De Bahmni Appointment Scheduling module (`org.bahmni.module.appointments`) ondersteunt de **ICT4H AtomFeed** module (`org.ict4h.openmrs.openmrs-atomfeed`). Deze publiceert events wanneer een afspraak wordt aangemaakt, gewijzigd of geannuleerd.

Getest en bevonden:
- De **SolDevelo AtomFeed** (versie 1.0.13, voor Sync2.0) is **niet** compatible — de appointments module verwacht specifiek de ICT4H versie
- Na installatie van de **ICT4H AtomFeed** (versie 2.7.0-SNAPSHOT) is de feed bereikbaar op:
  ```
  GET /openmrs/ws/atomfeed/appointment/recent
  ```
- Alle global properties staan correct op `true`:
  ```
  Atomfeed - Publish Events For Appointments = true
  Atomfeed - Publish Events For Appointment Service = true
  Atomfeed - Started = true
  ```
- De feed blijft leeg vanwege een Bahmni-specifieke database afhankelijkheid (`event_records` tabel) die buiten een volledige Bahmni-distributie niet automatisch gevuld wordt

**Conclusie:** AtomFeed vereist een volledige Bahmni-omgeving en is daarmee minder geschikt voor een standalone SaaS module die met willekeurige OpenMRS-instanties werkt. AtomFeed wordt aanbevolen als productie-verbetering wanneer een volledige Bahmni-distributie beschikbaar is.

---

#### Gekozen aanpak: FHIR2 polling (primair) + REST v1 reconciliatie (backup)

De communicatiemodule gebruikt **twee complementaire polling-lagen** die samen garanderen dat geen enkele afspraak permanent gemist wordt.

##### Laag 1 — Primaire poller (FHIR2, elke 2 minuten)

De `OpenMrsAppointmentPoller` bevraagt de **OpenMRS FHIR2 R4 API** conform de keuze in ADR-003. Dit endpoint volgt de HL7 FHIR R4-standaard en is beschikbaar in elke standaard OpenMRS 3 (O3) installatie zonder extra modules.

```
GET /ws/fhir2/R4/Appointment?date=ge{watermark}&_sort=date&_count=200
Authorization: Basic {credentials}
```

Respons (FHIR Bundle):
```json
{
  "resourceType": "Bundle",
  "entry": [
    {
      "resource": {
        "resourceType": "Appointment",
        "id": "6df8cc30-a376-4026-b660-6289f649e01e",
        "status": "booked",
        "start": "2025-06-15T09:00:00+00:00",
        "participant": [
          {
            "actor": {
              "reference": "Patient/620704f9-3e28-423c-b537-c835649390f8",
              "display": "Jan Jansen"
            }
          }
        ]
      }
    }
  ]
}
```

FHIR-status mapping naar interne `EventType`:

| FHIR status | Interne EventType | RabbitMQ routing key |
|---|---|---|
| `booked` | `SCHEDULED` | `appointment.scheduled` |
| `cancelled` / `noshow` | `CANCELLED` | `appointment.cancelled` |
| overige | `UPDATED` | `appointment.updated` |

##### Laag 2 — Reconciliatie-poller (REST v1, elke 5 minuten)

De `AppointmentReconciler` bevraagt de **OpenMRS REST v1 API** als vangnet. Dit is een bewust ander endpoint — zo worden ook afspraken opgepikt die de FHIR-laag eventueel gemist heeft (bijv. tijdens RabbitMQ-downtime of herstart van de service).

```
GET /ws/rest/v1/appointment?lastUpdated={watermark}&v=full
Authorization: Basic {credentials}
```

De reconciliator controleert of een afspraak al verwerkt is via de `notification_log` tabel voordat hij opnieuw dispatcht — zo worden dubbele notificaties voorkomen.

##### Overzicht beschikbare REST v1 endpoints (getest en werkend)

| Methode | Endpoint | Gebruik |
|---|---|---|
| POST | `/ws/rest/v1/appointment/search` | Afspraken filteren op tijdvenster + status |
| GET | `/ws/rest/v1/appointment?uuid={uuid}` | Één afspraak ophalen voor statuscheck |
| GET | `/ws/rest/v1/appointmentService/all/full` | Services en serviceTypes ophalen |
| POST | `/ws/rest/v1/appointment` | Afspraak aanmaken (getest) |
| POST | `/ws/rest/v1/appointment/{uuid}/changeStatus` | Status wijzigen |

---

### Geteste API calls

**Patiënt aanmaken:**
```json
POST /ws/rest/v1/patient
{
  "person": {
    "names": [{ "givenName": "Jan", "familyName": "Jansen" }],
    "gender": "M",
    "birthdate": "1990-01-01"
  },
  "identifiers": [
    {
      "identifier": "100GEJ",
      "identifierType": "05a29f94-c0ed-11e2-94be-8c13b969e334",
      "location": "44c3efb0-2583-4c80-a79e-1f756a03c0a1",
      "preferred": true
    },
    {
      "identifier": "TEST-001",
      "identifierType": "8d79403a-c2cc-11de-8d13-0010c6dffd0f",
      "location": "44c3efb0-2583-4c80-a79e-1f756a03c0a1",
      "preferred": false
    }
  ]
}
```

> **Let op:** OpenMRS vereist altijd een `OpenMRS ID` als primair identifier. De `Old Identification Number` kan als secundair identifier worden meegegeven. Het `OpenMRS ID` moet voldoen aan de Luhn Mod-30 validator — gebruik de idgen module om een geldig ID te genereren: `GET /ws/rest/v1/idgen/nextIdentifier?source=1`

**Appointment aanmaken (getest en werkend):**
```json
POST /ws/rest/v1/appointment
{
  "patientUuid": "620704f9-3e28-423c-b537-c835649390f8",
  "serviceUuid": "7ba3aa21-cc56-47ca-bb4d-a60549f666c0",
  "serviceTypeUuid": "fbec4378-2d0d-4509-a56e-be0a53700709",
  "startDateTime": "2025-06-15T09:00:00.000Z",
  "endDateTime": "2025-06-15T09:30:00.000Z",
  "appointmentKind": "Scheduled",
  "locationUuid": "44c3efb0-2583-4c80-a79e-1f756a03c0a1",
  "comments": "Nuchter komen, medicijnen meenemen",
  "providers": []
}
```

> **Let op:** `serviceUuid` en `serviceTypeUuid` moeten echte UUIDs zijn die bestaan in OpenMRS. Haal deze op via `GET /ws/rest/v1/appointmentService/all/full`. Een ongeldige UUID geeft een `NullPointerException` op `AppointmentMapper.java:99`.

**Response (relevant voor notificatie):**
```json
{
  "uuid": "6df8cc30-a376-4026-b660-6289f649e01e",
  "status": "Scheduled",
  "startDateTime": 1749978000000,
  "location": { "name": "Outpatient Clinic" },
  "patient": { "name": "Jan Jansen", "uuid": "620704f9-..." },
  "comments": "Nuchter komen, medicijnen meenemen"
}
```

---

## Flow: afspraak aangemaakt in OpenMRS

---

### 1. OpenMRS slaat de appointment op

De afspraak wordt aangemaakt via de Bahmni Appointment Scheduling module. De communicatiemodule detecteert dit via de FHIR2 poller.

---

### 2. FHIR2 Poller detecteert de nieuwe appointment

Elke 2 minuten bevraagt de poller de FHIR2 API en vergelijkt de resultaten met de eigen database:

```
GET /ws/fhir2/R4/Appointment?date=ge{watermark}&_sort=date&_count=200

→ UUID niet in seen_appointments?    → nieuw → verwerk
→ UUID wel in DB, status veranderd   → gewijzigd/geannuleerd → herplan
→ UUID wel in DB, niets veranderd    → sla over
```

Veerkrachtmechanismen actief tijdens elke poll:

| Mechanisme | Werking |
|---|---|
| **Watermark cursor** | Opgeslagen in `sync_watermarks` (Postgres). Herstelt na iedere downtime automatisch. |
| **Nooit-vooruit-bij-fout** | Watermark schuift alleen op als álle afspraken succesvol in de wachtrij staan. |
| **Circuit breaker** | Na 5 opeenvolgende fouten pauzeert de poller 2 minuten. Herstelt automatisch. |
| **Persist-before-publish** | Afspraken worden eerst opgeslagen in `outbox_events`, daarna pas gepubliceerd naar RabbitMQ. |

---

### 3. Poller publiceert naar RabbitMQ

```json
{
  "action": "created",
  "appointmentUuid": "6df8cc30-...",
  "patientUuid": "620704f9-...",
  "patientName": "Jan Jansen",
  "startDateTime": 1749978000000,
  "location": "Outpatient Clinic",
  "comments": "Nuchter komen, medicijnen meenemen",
  "organizationId": "ziekenhuis-A"
}
```

Gepubliceerd op queue: `appointment.scheduled`

---

### 4. Worker pakt het bericht op

De worker luistert naar `appointment.scheduled` en doet drie dingen:

#### a) Bevestigingsbericht sturen (direct)

```
"Beste Jan Jansen, uw afspraak op 15 juni om 09:00
in Outpatient Clinic is bevestigd.
Instructies: Nuchter komen, medicijnen meenemen."
```

#### b) 24u notificatie inplannen

```json
{
  "appointmentUuid": "6df8cc30-...",
  "type": "24h_reminder",
  "sendAt": "2025-06-14T09:05:00Z"
}
```

Gepubliceerd op `notification.24h` met een delay van 22u55m.

#### c) 1u notificatie inplannen

```json
{
  "appointmentUuid": "6df8cc30-...",
  "type": "1h_reminder",
  "sendAt": "2025-06-15T08:05:00Z"
}
```

Gepubliceerd op `notification.1h` met een delay van 23u55m.

---

### 5. Worker stuurt via de juiste provider

De worker kijkt in de database welke provider ziekenhuis-A heeft geconfigureerd:

```
organisatie: ziekenhuis-A → provider: SwiftSend
```

Stuurt het bericht via SwiftSend en logt het resultaat:

```
notificatie_log:
  appointmentUuid: 6df8cc30-...
  organisatie:     ziekenhuis-A
  provider:        SwiftSend
  type:            bevestiging
  status:          success
  timestamp:       2025-06-13T10:05:00Z
```

---

### 6. Op 14 juni om 09:05 (24u van tevoren)

RabbitMQ levert het delayed bericht af op `notification.24h`. Worker checkt eerst:

```
GET /openmrs/ws/rest/v1/appointment?uuid=6df8cc30-...
→ status nog steeds "Scheduled"? ✓
```

Ja → stuurt notificatie via SwiftSend + logt het.

---

### 7. Op 15 juni om 08:05 (1u van tevoren)

Zelfde verhaal voor `notification.1h`. Worker checkt status → nog Scheduled → stuurt laatste herinnering.

---

### Wat als de afspraak geannuleerd wordt?

Stel Jan belt op 14 juni om zijn afspraak te annuleren. OpenMRS zet de status op `Cancelled`. Bij de volgende FHIR2-poll detecteert de poller dat de FHIR-status van `6df8cc30-...` veranderd is van `booked` naar `cancelled`. De poller publiceert naar `appointment.cancelled` → de worker markeert de geplande 1u notificatie als geannuleerd → die wordt nooit verstuurd.

Mogelijke statuswaarden:

| OpenMRS status | FHIR status | Actie |
|---|---|---|
| Scheduled | `booked` | Notificaties versturen |
| CheckedIn | `arrived` | Geen notificatie |
| Completed | `fulfilled` | Geen notificatie |
| Cancelled | `cancelled` | Geplande notificaties annuleren |
| Missed | `noshow` | Geen notificatie |

---

### Wat als de FHIR2 poller een event mist?

De `AppointmentReconciler` draait elke 5 minuten als extra vangnet via de REST v1 API:

```
GET /ws/rest/v1/appointment?lastUpdated={watermark}&v=full

→ UUID al in notification_log? → overslaan (geen dubbele notificatie)
→ UUID nog niet verwerkt?       → direct dispatchen
```

Dit vangt situaties op waarbij de primaire FHIR2-poller iets gemist heeft, bijvoorbeeld tijdens RabbitMQ-downtime of een herstart van de service. De watermark wordt ook hier alleen vooruitgezet na een succesvolle verwerking.

---

### Compleet tijdlijn overzicht

```
13 juni 10:00  → Afspraak aangemaakt in OpenMRS
13 juni 10:02  → FHIR2 poller detecteert, worker stuurt bevestiging ✓
14 juni 09:05  → 24u notificatie verstuurd ✓
15 juni 08:05  → 1u notificatie verstuurd ✓
15 juni 09:00  → Afspraak vindt plaats
```

---

### Architectuur overzicht

```
OpenMRS (FHIR2 R4 API)
  │
  ▼
FHIR2 Poller (elke 2 min)        REST v1 Reconciliator (elke 5 min)
  │  /ws/fhir2/R4/Appointment       │  /ws/rest/v1/appointment
  │  watermark + circuit breaker    │  watermark + duplicate check
  │  persist-before-publish         │
  │                                 │
  └─────────────┬───────────────────┘
                │ (vult dezelfde exchange)
                ▼
           RabbitMQ
  ├── appointment.scheduled  ──► Worker
  │                                │ direct  → bevestigingsbericht
  │                                │ delay   → notification.24h
  │                                └── delay → notification.1h
  │
  ├── appointment.cancelled ──► Worker
  │                                └── annuleer geplande notificaties
  │
  ├── notification.24h ────────► Worker
  │                                └── check status → stuur bericht
  │
  └── notification.1h ─────────► Worker
                                   └── check status → stuur bericht
                                             │
                                             ▼
                                   SwiftSend / LegacyLink
                                   AsyncFlow / SecurePost
                                             │
                                             ▼
                                       Log in database
```
