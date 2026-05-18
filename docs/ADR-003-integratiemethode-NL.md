# ADR-003: Integratiemethode — Hoe koppelt de module aan OpenMRS?

| Veld        | Waarde                   |
|-------------|--------------------------|
| Status      | Geaccepteerd             |
| Datum       | 2025-05-13               |
| Beslissers  | Platformteam             |
| Sprint      | 2–3                      |

---

## Context

De communicatiemodule moet afspraakgegevens ontvangen vanuit OpenMRS om patiënten tijdig te notificeren. De oplossing moet:

- Betrouwbaar zijn bij uitval van één of meerdere services.
- Schaalbaar zijn naar meerdere OpenMRS-instanties (multi-tenant SaaS).
- Aansluiten op gangbare zorgstandaarden zoals **HL7 FHIR R4**.
- Werken met de OpenMRS 3 (O3) referentiedistributie, die de **FHIR2-module** en de **webservices.rest-module** bevat.

### Beschikbare integratiemechanismen in OpenMRS 3

| Mechanisme | Beschrijving | Standaard |
|---|---|---|
| **OpenMRS REST API** | `/ws/rest/v1/appointment` — eigen OpenMRS-formaat | Nee |
| **OpenMRS FHIR2 API** | `/ws/fhir2/R4/Appointment` — FHIR R4 resources | HL7 FHIR R4 |
| **OpenMRS Event Module** | Publiceert intern domeingebeurtenissen via embedded ActiveMQ | Intern |
| **AtomFeed Module** | RSS-achtige feed van wijzigingen — polling via HTTP | Nee |
| **Custom OMOD** | Zelf een OpenMRS-module bouwen die events naar RabbitMQ stuurt | Nee |

---

## Probleem

Via welke integratiemethode ontvangt de communicatiemodule afspraakdata vanuit OpenMRS, en welke oplossing past het beste bij de eisen rondom betrouwbaarheid, schaalbaarheid en standaardisatie?

---

## Overwogen opties

### Optie 1 — Directe REST-koppeling (OpenMRS REST API)

De communicatiemodule bevraagt periodiek `/ws/rest/v1/appointment` met een datum-filter.

**Voordelen:** Eenvoudig te implementeren; geen aanpassingen aan OpenMRS.  
**Nadelen:** Eigen OpenMRS-formaat (niet FHIR); hogere belasting op OpenMRS; mist wijzigingen tijdens downtime tenzij aangevuld met watermark-logica.

---

### Optie 2 — Directe HTTP-koppeling (push van OpenMRS naar module)

OpenMRS roept een webhook aan op de communicatiemodule bij iedere afspraakwijziging.

**Voordelen:** Lage latentie; eenvoudig.  
**Nadelen:** Creëert sterke temporele en gedragsmatige koppeling — als de communicatiemodule uitvalt, gaan events verloren. OpenMRS moet het endpoint van de module kennen. Niet schaalbaar naar meerdere instanties.

---

### Optie 3 — Event-driven via REST + Message Queue (RabbitMQ) ✅ Gekozen

De communicatiemodule poll de OpenMRS **FHIR2 REST API** (`/ws/fhir2/R4/Appointment`) periodiek en plaatst nieuwe of gewijzigde afspraken in RabbitMQ. Later (zodra de OpenMRS Event Module of een custom OMOD beschikbaar is) kan de push-kant worden toegevoegd zonder de rest van de architectuur te wijzigen.

```
Pad 1 — Polling (sprint 3, nu actief):
  [Scheduler] → GET /ws/fhir2/R4/Appointment?date=ge{watermark}
              → filter op nieuw/gewijzigd
              → publish AppointmentEvent → RabbitMQ

Pad 2 — Push (toekomstige sprint):
  OpenMRS Event Module / custom OMOD
              → publish AppointmentEvent → RabbitMQ (zelfde exchange)
```

Beide paden vullen hetzelfde RabbitMQ exchange. De rest van de module (consumers, providers, outbox) hoeft niet te worden aangepast.

**Voordelen:**
- Betrouwbare verwerking via queueing en retries.
- Mist bij downtime geen events dankzij watermark-cursor (persistente voortgang in Postgres).
- Goed schaalbaar: meerdere OpenMRS-instanties publiceren naar dezelfde queue-infrastructuur.
- FHIR R4 Appointment resource is de standaard — compatibel met andere zorgintegraties.
- Lage belasting op OpenMRS (poll-interval configureerbaar, standaard 2 minuten).

**Nadelen:**
- Hogere latentie dan directe push (maximaal gelijk aan het poll-interval).
- Aanpassen van OpenMRS (custom OMOD of Event Module configureren) maakt de push-kant complexer.
- Debugging is lastiger doordat communicatie asynchroon verloopt.

---

### Optie 4 — AtomFeed Module

De AtomFeed module genereert RSS-achtige feeds van wijzigingen. De communicatiemodule poll de feed.

**Voordelen:** Ingebouwd in OpenMRS; geen extra module nodig.  
**Nadelen:** Niet op FHIR gebaseerd; minder breed ondersteund; vereist ook een watermark-mechanisme; lagere adoptie in de community vergeleken met FHIR2.

---

## Beslissing

**Gekozen: Optie 3 — Event-driven via OpenMRS FHIR2 REST API + RabbitMQ**

### Implementatie in sprint 3

De `OpenMrsAppointmentPoller` bevraagt de FHIR R4 Appointment endpoint:

```
GET /ws/fhir2/R4/Appointment?date=ge{watermark}&_sort=date&_count=200
Authorization: Basic {admin credentials}
```

Veerkrachtmechanismen in de poller:
| Mechanisme | Implementatie |
|---|---|
| **Watermark cursor** | Opgeslagen in `sync_watermarks` tabel in Postgres. Herstelt na iedere downtime. |
| **Nooit-vervroeg-bij-fout** | Watermark wordt alleen vooruit gezet als àlle opgehaalde afspraken succesvol in de wachtrij staan. |
| **Circuit breaker** | Na 5 opeenvolgende fouten pauzeert de poller 2 minuten. Herstelt automatisch. |
| **Duplicate guard** | `seen_appointments` tabel voorkomt dubbele notificaties bij overlappende poll-vensters. |
| **Persist-before-publish** | Afspraken worden eerst opgeslagen in `outbox_events`, daarna pas gepubliceerd naar RabbitMQ. |

### Aansluiting op HL7/FHIR

De FHIR2 module in OpenMRS O3 exposeert `Appointment` resources conform **FHIR R4**. Ons interne `AppointmentEvent` model wordt gevuld vanuit de FHIR-velden `id`, `status`, `start`, en `participant[].actor` (patiëntverwijzing). Hierdoor is de integratie in de toekomst uitbreidbaar naar andere FHIR-compatibele systemen zonder codewijzigingen in de downstream verwerking.

### Schaalbaarheid

Meerdere OpenMRS-instanties kunnen naar hetzelfde RabbitMQ exchange publiceren (zowel via polling als via een toekomstige push-integratie). De communicatiemodule consumeert van de queues ongeacht de bron, wat multi-tenant SaaS-scenario's ondersteunt.

### Gevolgen bij downtime

| Scenario | Gedrag |
|---|---|
| **Communicatiemodule tijdelijk down** | RabbitMQ bewaart berichten in duurzame queues. Na herstart worden ze verwerkt. |
| **OpenMRS tijdelijk down** | Poller logt fout, activeert circuit breaker, watermark wordt niet vooruitgezet. Na herstel haalt de poller de gemiste periode op via de watermark. |
| **RabbitMQ tijdelijk down** | Outbox in Postgres behoudt de data. Relay-loop publiceert zodra de broker bereikbaar is. |

---

## Referenties

- [OpenMRS FHIR2 Module](https://github.com/openmrs/openmrs-module-fhir2)
- [HL7 FHIR R4 Appointment Resource](https://www.hl7.org/fhir/appointment.html)
- [OpenMRS Event Module (ActiveMQ)](https://wiki.openmrs.org/display/docs/Event+Module)
- [OpenMRS O3 Reference Application](https://github.com/openmrs/openmrs-distro-referenceapplication)
- `notification-service/.../poller/OpenMrsAppointmentPoller.java` — implementatie
- ADR-004 — RabbitMQ queue-infrastructuur
