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

De communicatiemodule poll de OpenMRS **REST v1 appointment/search API** periodiek en plaatst nieuwe of gewijzigde afspraken in RabbitMQ. Later (zodra de OpenMRS Event Module of een custom OMOD beschikbaar is) kan de push-kant worden toegevoegd zonder de rest van de architectuur te wijzigen.

> **⚠️ Bevinding na test (2026-05-20):** De FHIR2 module in de gebruikte OpenMRS O3 installatie ondersteunt het `Appointment` resource type **niet**. De aanroep `GET /ws/fhir2/R4/Appointment` geeft `HAPI-0302: Unknown resource type 'Appointment'`. De primaire poller is daarom omgeschreven naar `POST /ws/rest/v1/appointment/search`. FHIR2 wordt wél gebruikt voor patiëntgegevens (`/ws/fhir2/R4/Patient/{uuid}`).

```
Pad 1 — Polling (actief):
  [Scheduler] → POST /ws/rest/v1/appointment/search
                  { startDate: now, endDate: now+48h }
              → vergelijk status met seen_appointments
              → publish AppointmentEvent → RabbitMQ

Pad 1b — Reconciliatie (backup, elke 5 min):
  [Scheduler] → GET /ws/rest/v1/appointment?lastUpdated={watermark}
              → check notification_log op dubbelen
              → dispatch indien gemist

Pad 2 — Push (toekomstige sprint):
  OpenMRS Event Module / custom OMOD
              → publish AppointmentEvent → RabbitMQ (zelfde exchange)
```

Beide paden vullen hetzelfde RabbitMQ exchange. De rest van de module (consumers, providers, outbox) hoeft niet te worden aangepast.

**Voordelen:**
- Betrouwbare verwerking via queueing en retries.
- Werkt met de standaard OpenMRS O3 installatie zonder extra modules.
- Goed schaalbaar: meerdere OpenMRS-instanties publiceren naar dezelfde queue-infrastructuur.
- Lage belasting op OpenMRS (poll-interval configureerbaar, standaard 2 minuten).
- Sliding window aanpak vangt zowel nieuwe als gewijzigde afspraken op.

**Nadelen:**
- Hogere latentie dan directe push (maximaal gelijk aan het poll-interval).
- Niet FHIR-gebaseerd voor appointments — wel voor patiëntdata.
- Debugging is lastiger doordat communicatie asynchroon verloopt.

---

### Optie 4 — AtomFeed Module

De AtomFeed module genereert RSS-achtige feeds van wijzigingen. De communicatiemodule poll de feed.

**Voordelen:** Ingebouwd in OpenMRS; geen extra module nodig.  
**Nadelen:** Niet op FHIR gebaseerd; minder breed ondersteund; vereist ook een watermark-mechanisme; lagere adoptie in de community vergeleken met FHIR2.

---

## Beslissing

**Gekozen: Optie 3 — Event-driven via OpenMRS REST v1 API + RabbitMQ**

> **Bijgesteld na test (2026-05-20):** Oorspronkelijk was FHIR2 gepland als primaire API. Na praktijktest bleek dat de FHIR2-module in de gebruikte OpenMRS O3-distributie het `Appointment` resource type niet ondersteunt (`HAPI-0302: Unknown resource type 'Appointment'`). De FHIR2 Patient resource werkt wél, maar geeft geen extra voordeel boven REST v1 — contactgegevens (`telecom[]`) zijn leeg als ze niet in OpenMRS zijn opgeslagen, ongeacht welke API je gebruikt. De volledige integratie gebruikt daarom consistent **REST v1**.

### Implementatie

De `OpenMrsAppointmentPoller` bevraagt de REST v1 appointment/search endpoint:

```
POST /ws/rest/v1/appointment/search
{ "startDate": "<now>", "endDate": "<now + 48h>" }
Authorization: Basic {admin credentials}
```

De `AppointmentReconciler` (backup) bevraagt:
```
GET /ws/rest/v1/appointment?lastUpdated={watermark}&v=full
```

Veerkrachtmechanismen in de poller:
| Mechanisme | Implementatie |
|---|---|
| **Sliding window** | Poll de komende 48 uur — vangt zowel nieuwe als gewijzigde afspraken op. |
| **Status-change detectie** | Vergelijkt huidige status met `seen_appointments` tabel. Alleen bij wijziging wordt een event gepubliceerd. |
| **Circuit breaker** | Na 5 opeenvolgende fouten pauzeert de poller 2 minuten. Herstelt automatisch. |
| **Duplicate guard** | `seen_appointments` tabel voorkomt dubbele notificaties bij overlappende poll-vensters. |
| **Persist-before-publish** | Afspraken worden eerst opgeslagen in `outbox_events`, daarna pas gepubliceerd naar RabbitMQ. |

### FHIR — bewuste keuze om te skippen

| Resource | FHIR2 | REST v1 | Gekozen |
|---|---|---|---|
| Appointment ophalen | ❌ niet ondersteund | ✅ werkt | REST v1 |
| Patiënt contactgegevens | ✅ werkt (telecom[]) | ✅ werkt (person/attributes) | REST v1 |

FHIR2 wordt volledig vermeden omdat:
1. Appointment (kern van het systeem) wordt niet ondersteund.
2. Patient via REST v1 geeft dezelfde data — consistent één API is eenvoudiger.
3. De FHIR-module-installatiestatus verschilt per OpenMRS-instantie; REST v1 is altijd aanwezig.

Het interne `AppointmentEvent` model is bewust provider-agnostisch — als een toekomstige installatie wél FHIR2 Appointment ondersteunt, kan de Poller worden uitgewisseld zonder wijzigingen aan consumers, providers of outbox.

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
