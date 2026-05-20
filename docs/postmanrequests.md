# Postman Requests — OpenMRS Communicatiemodule

Alle geteste API-calls met de werkelijke UUIDs van deze installatie.  
Bijgewerkt na test op 2026-05-20.

---

## Basisinstellingen (Collection-level)

| Instelling | Waarde |
|---|---|
| Base URL | `http://localhost/openmrs` |
| Auth type | Basic Auth |
| Username | `admin` |
| Password | `Admin1234` |
| Content-Type header | `application/json` |

> **Tip:** Maak een Postman Collection aan met bovenstaande auth als Collection-default.  
> Sla `patientUuid` en `appointmentUuid` op als **Collection Variables** (`{{patientUuid}}`, `{{appointmentUuid}}`).

---

## Bekende UUIDs van deze installatie

| Resource | Naam | UUID |
|---|---|---|
| Location | Community Outreach | `1ce1b7d4-c865-4178-82b0-5932e51503d6` |
| Location | Inpatient Ward | `ba685651-ed3b-4e63-9b35-78893060758a` |
| Identifier type | OpenMRS ID (primair, Luhn Mod-30) | `05a29f94-c0ed-11e2-94be-8c13b969e334` |
| Identifier type | Old Identification Number | `8d79403a-c2cc-11de-8d13-0010c6dffd0f` |
| Appointment service | General Medicine service | `7ba3aa21-cc56-47ca-bb4d-a60549f666c0` |
| Service type | Short follow-up (10 min) | `fbec4378-2d0d-4509-a56e-be0a53700709` |
| Appointment service | Outpatient Department | `1ef43565-9c96-4f58-bfd2-c864c7cedac1` |
| Appointment service | Rehabilitation service | `4ec5c4fe-cfe0-48ff-9e4d-2f201078feae` |

> ⚠️ Outpatient Department en Rehabilitation hebben geen `serviceTypes` — gebruik bij appointments altijd **General Medicine + Short follow-up**.

---

## ⚠️ FHIR2 Appointment — NIET ondersteund

`GET /ws/fhir2/R4/Appointment` geeft:
```json
{
  "issue": [{
    "severity": "error",
    "diagnostics": "HAPI-0302: Unknown resource type 'Appointment'"
  }]
}
```
**De FHIR2 module in deze installatie ondersteunt geen Appointment resource.**  
Gebruik in plaats daarvan `POST /ws/rest/v1/appointment/search`.  
FHIR2 werkt wél voor Patient (`/ws/fhir2/R4/Patient/{uuid}`).

---

## STAP 0 — Discovery (UUIDs ophalen)

### 0a. Locaties ophalen

```
GET http://localhost/openmrs/ws/rest/v1/location?v=default
```

---

### 0b. Identifier types ophalen

```
GET http://localhost/openmrs/ws/rest/v1/patientidentifiertype?v=default
```

---

### 0c. Geldig OpenMRS ID genereren (Luhn Mod-30)

```
GET http://localhost/openmrs/ws/rest/v1/idgen/nextIdentifier?source=1
```

**Response:**
```json
{
  "results": [
    { "identifierValue": "10003A6" }
  ]
}
```

> Roep dit aan vóór elke nieuwe patiënt. Elke aanroep geeft een uniek geldig ID.

---

### 0d. Appointment services + serviceTypes ophalen

```
GET http://localhost/openmrs/ws/rest/v1/appointmentService/all/full
```

---

## STAP 1 — Patiënt aanmaken

```
POST http://localhost/openmrs/ws/rest/v1/patient
```

**Body:**
```json
{
  "person": {
    "names": [
      {
        "givenName": "Jan",
        "familyName": "Jansen"
      }
    ],
    "gender": "M",
    "birthdate": "1990-01-01"
  },
  "identifiers": [
    {
      "identifier": "10003A6",
      "identifierType": "05a29f94-c0ed-11e2-94be-8c13b969e334",
      "location": "1ce1b7d4-c865-4178-82b0-5932e51503d6",
      "preferred": true
    },
    {
      "identifier": "TEST-001",
      "identifierType": "8d79403a-c2cc-11de-8d13-0010c6dffd0f",
      "location": "1ce1b7d4-c865-4178-82b0-5932e51503d6",
      "preferred": false
    }
  ]
}
```

**Response `201 Created` (voorbeeld):**
```json
{
  "uuid": "f45e200d-c6f7-4486-89b9-ea217eab54a2",
  "display": "10003A6 - Jan Jansen",
  "person": {
    "uuid": "f45e200d-c6f7-4486-89b9-ea217eab54a2",
    "display": "Jan Jansen",
    "gender": "M",
    "age": 36
  }
}
```

> **Kopieer `uuid`** → sla op als Collection Variable `{{patientUuid}}`.

---

## STAP 2 — Patiënt ophalen (REST v1)

```
GET http://localhost/openmrs/ws/rest/v1/patient/{{patientUuid}}?v=full
```

**Concreet (geteste UUID):**
```
GET http://localhost/openmrs/ws/rest/v1/patient/f45e200d-c6f7-4486-89b9-ea217eab54a2?v=full
```

---

## STAP 3 — Patiënt ophalen (FHIR R4) ✅ werkt

```
GET http://localhost/openmrs/ws/fhir2/R4/Patient/{{patientUuid}}
```

**Concreet:**
```
GET http://localhost/openmrs/ws/fhir2/R4/Patient/f45e200d-c6f7-4486-89b9-ea217eab54a2
```

**Verwachte response:**
```json
{
  "resourceType": "Patient",
  "id": "f45e200d-c6f7-4486-89b9-ea217eab54a2",
  "name": [{ "family": "Jansen", "given": ["Jan"] }],
  "telecom": [],
  "gender": "male",
  "birthDate": "1990-01-01"
}
```

> `telecom[]` is leeg — er is geen telefoon/email opgeslagen voor deze patiënt.  
> Dit is het probleem dat **Fase 2** van CLAUDE.md oplost.

---

## STAP 4 — Afspraak aanmaken

```
POST http://localhost/openmrs/ws/rest/v1/appointment
```

**Body:**
```json
{
  "patientUuid": "f45e200d-c6f7-4486-89b9-ea217eab54a2",
  "serviceUuid": "7ba3aa21-cc56-47ca-bb4d-a60549f666c0",
  "serviceTypeUuid": "fbec4378-2d0d-4509-a56e-be0a53700709",
  "startDateTime": "2026-05-22T09:00:00.000Z",
  "endDateTime": "2026-05-22T09:10:00.000Z",
  "appointmentKind": "Scheduled",
  "locationUuid": "1ce1b7d4-c865-4178-82b0-5932e51503d6",
  "comments": "Nuchter komen, medicijnen meenemen",
  "providers": []
}
```

**Response `200 OK` (voorbeeld):**
```json
{
  "uuid": "b56e4276-9843-4a36-a804-de5751e0522e",
  "appointmentNumber": "0000",
  "patient": {
    "name": "Jan Jansen",
    "uuid": "f45e200d-c6f7-4486-89b9-ea217eab54a2"
  },
  "service": { "name": "General Medicine service" },
  "serviceType": { "name": "Short follow-up" },
  "location": { "name": "Community Outreach" },
  "startDateTime": 1779440400000,
  "status": "Scheduled",
  "comments": "Nuchter komen, medicijnen meenemen"
}
```

> **Kopieer `uuid`** → sla op als Collection Variable `{{appointmentUuid}}`.  
> `startDateTime` is Unix timestamp in milliseconden.

---

## STAP 5 — Afspraak ophalen (REST v1)

```
GET http://localhost/openmrs/ws/rest/v1/appointment?uuid={{appointmentUuid}}
```

**Concreet:**
```
GET http://localhost/openmrs/ws/rest/v1/appointment?uuid=b56e4276-9843-4a36-a804-de5751e0522e
```

---

## STAP 6 — Afspraken zoeken op tijdvenster (REST v1) ← wat de Poller gebruikt

```
POST http://localhost/openmrs/ws/rest/v1/appointment/search
```

**Body:**
```json
{
  "startDate": "2026-05-22T00:00:00.000Z",
  "endDate": "2026-05-23T00:00:00.000Z"
}
```

> Dit is exact het endpoint dat `OpenMrsAppointmentPoller` elke 2 minuten aanroept  
> met een sliding window van `now` tot `now + 48 uur`.

---

## STAP 7 — Afspraak ophalen via FHIR R4 ❌ niet ondersteund

```
GET http://localhost/openmrs/ws/fhir2/R4/Appointment/{{appointmentUuid}}
```

**Geeft fout:**
```json
{
  "issue": [{
    "severity": "error",
    "diagnostics": "HAPI-0302: Unknown resource type 'Appointment'"
  }]
}
```

> Gebruik stap 5 of stap 6 voor appointment-queries.

---

## STAP 8 — Afspraak annuleren

```
POST http://localhost/openmrs/ws/rest/v1/appointment/{{appointmentUuid}}/changeStatus
```

**Concreet:**
```
POST http://localhost/openmrs/ws/rest/v1/appointment/b56e4276-9843-4a36-a804-de5751e0522e/changeStatus
```

**Body:**
```json
{
  "toStatus": "Cancelled",
  "onDate": "2026-05-20T10:00:00.000Z"
}
```

---

## STAP 9 — Annulering verifiëren

```
GET http://localhost/openmrs/ws/rest/v1/appointment?uuid={{appointmentUuid}}
```

Controleer in de response: `"status": "Cancelled"`.

---

## Andere handige calls

### Alle afspraken van een patiënt ophalen

```
GET http://localhost/openmrs/ws/rest/v1/appointment?patient={{patientUuid}}
```

### Afspraken filteren op service

```
POST http://localhost/openmrs/ws/rest/v1/appointment/search
```
```json
{
  "startDate": "2026-05-01T00:00:00.000Z",
  "endDate": "2026-06-01T00:00:00.000Z",
  "serviceUuid": "7ba3aa21-cc56-47ca-bb4d-a60549f666c0"
}
```

### Status wijzigen naar CheckedIn

```
POST http://localhost/openmrs/ws/rest/v1/appointment/{{appointmentUuid}}/changeStatus
```
```json
{
  "toStatus": "CheckedIn",
  "onDate": "2026-05-22T09:00:00.000Z"
}
```

### Mogelijke statuswaarden

| Status | Betekenis | Notificatie actie |
|---|---|---|
| `Scheduled` | Ingepland | Verstuur bevestiging + plan 24h/1h reminders |
| `CheckedIn` | Patiënt aangemeld | Geen actie |
| `Completed` | Afgerond | Geen actie |
| `Cancelled` | Geannuleerd | Annuleer geplande reminders |
| `Missed` | Niet verschenen | Geen actie |

---

## FakeComWorld provider endpoints

Base URL: `http://localhost:1337`

### SwiftSend — SMS (API key)

```
POST http://localhost:1337/api/swiftsend/messages
X-API-KEY: your-api-key-here
X-STUDENT-GROUP: group-1
Content-Type: application/json
```
```json
{
  "recipient": "+31612345678",
  "message": "Uw afspraak op 22 mei om 09:00 is bevestigd.",
  "reference": "b56e4276-9843-4a36-a804-de5751e0522e"
}
```

---

### SecurePost — Email (JWT)

**Stap 1: token ophalen**
```
POST http://localhost:1337/api/securepost/auth/token
X-STUDENT-GROUP: group-1
Content-Type: application/json
```
```json
{
  "clientId": "securepost-client-id",
  "clientSecret": "securepost-secret-key"
}
```
Response: `{ "token": "eyJ...", "expiresIn": 180 }`

**Stap 2: bericht sturen**
```
POST http://localhost:1337/api/securepost/messages
Authorization: Bearer {{token}}
X-STUDENT-GROUP: group-1
Content-Type: application/json
```
```json
{
  "to": "jan.jansen@example.com",
  "subject": "Afspraakbevestiging",
  "body": "Uw afspraak op 22 mei om 09:00 is bevestigd.",
  "reference": "b56e4276-9843-4a36-a804-de5751e0522e"
}
```

---

### LegacyLink — SOAP (Basic auth)

```
POST http://localhost:1337/api/legacylink/soap
Authorization: Basic bGVnYWN5bGluay11c2VyOmxlZ2FjeWxpbmstcGFzc3dvcmQ=
SOAPAction: SendMessage
X-STUDENT-GROUP: group-1
Content-Type: text/xml
```
```xml
<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope
    xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
    xmlns:ll="http://legacylink.fakecomworld/messaging">
  <soapenv:Header/>
  <soapenv:Body>
    <ll:SendMessageRequest>
      <ll:CorrelationId>test-correlation-123</ll:CorrelationId>
      <ll:Recipient>+31612345678</ll:Recipient>
      <ll:Message>Uw afspraak op 22 mei om 09:00 is bevestigd.</ll:Message>
      <ll:Reference>b56e4276-9843-4a36-a804-de5751e0522e</ll:Reference>
    </ll:SendMessageRequest>
  </soapenv:Body>
</soapenv:Envelope>
```

> Basic auth waarde: `legacylink-user:legacylink-password` → Base64 = `bGVnYWN5bGluay11c2VyOmxlZ2FjeWxpbmstcGFzc3dvcmQ=`

---

### AsyncFlow — Async (API key, 2 stappen)

**Stap 1: command indienen**
```
POST http://localhost:1337/api/asyncflow/commands
X-API-KEY: asyncflow-api-key
X-STUDENT-GROUP: group-1
Content-Type: application/json
```
```json
{
  "recipient": "f45e200d-c6f7-4486-89b9-ea217eab54a2",
  "message": "Uw afspraak op 22 mei om 09:00 is bevestigd.",
  "reference": "b56e4276-9843-4a36-a804-de5751e0522e"
}
```
Response: `{ "commandId": "cmd-abc123" }`

**Stap 2: status opvragen**
```
GET http://localhost:1337/api/asyncflow/commands/{{commandId}}
X-API-KEY: asyncflow-api-key
X-STUDENT-GROUP: group-1
```
Response: `{ "status": "pending" | "completed" | "failed" }`

---

## Volledige testvolgorde

```
0a → 0b → 0c → 0d      UUIDs verzamelen
↓
1 → 2 → 3              Patiënt: aanmaken → REST → FHIR
↓
4 → 5 → 6              Appointment: aanmaken → ophalen → zoeken
↓
8 → 9                  Annuleren → status verifiëren
```
