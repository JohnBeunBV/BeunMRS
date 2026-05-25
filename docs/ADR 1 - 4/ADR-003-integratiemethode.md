# ADR-003 — Integratiemethode: hoe koppelt de module aan OpenMRS?

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De communicatiemodule moet afspraakgegevens ontvangen vanuit OpenMRS om patiënten tijdig te notificeren. De oplossing moet:

- Betrouwbaar zijn bij uitval van één of meerdere services.
- Schaalbaar zijn naar meerdere OpenMRS-instanties (multi-tenant SaaS).
- Aansluiten op gangbare zorgstandaarden zoals HL7 FHIR R4 waar mogelijk.
- Werken met de standaard OpenMRS O3 referentiedistributie.

## Probleem

Via welke integratiemethode ontvangt de communicatiemodule afspraakdata vanuit OpenMRS, en welke oplossing past het beste bij de eisen rondom betrouwbaarheid, schaalbaarheid en standaardisatie?

---

## Overwogen opties

### Optie 1 — Directe databasekoppeling

De communicatiemodule leest afspraakgegevens rechtstreeks uit de database van OpenMRS.

**Voordelen**
- Eenvoudig op te zetten
- Snelle toegang tot data zonder extra API-laag

**Nadelen**
- Sterke afhankelijkheid van de interne databasestructuur van OpenMRS
- Wijzigingen in het databaseschema kunnen de koppeling breken
- Slecht schaalbaar bij meerdere OpenMRS-instanties
- Direct database-toegang van buitenaf is een veiligheidsrisico
- Sluit niet aan op HL7/FHIR-standaarden

---

### Optie 2 — Directe HTTP-koppeling (webhook/push van OpenMRS)

OpenMRS roept bij iedere afspraakwijziging een webhook aan op de communicatiemodule.

**Voordelen**
- Lage latentie — directe verwerking bij elke wijziging

**Nadelen**
- Als de communicatiemodule tijdelijk offline is, gaan events verloren
- OpenMRS moet het adres van de communicatiemodule kennen — sterke koppeling
- Slecht schaalbaar naar meerdere instanties zonder aparte configuratie per instantie

**Gevolgen bij downtime:** Events gaan permanent verloren als de communicatiemodule op het moment van de webhook niet bereikbaar is.

---

### Optie 3 — Event-driven via REST Polling + RabbitMQ

De communicatiemodule bevraagt periodiek de OpenMRS REST API en plaatst nieuwe of gewijzigde afspraken in RabbitMQ voor asynchrone verwerking.

**Voordelen**
- Betrouwbare verwerking via queueing en retries
- Werkt zonder aanpassingen aan OpenMRS
- Goed schaalbaar: meerdere OpenMRS-instanties kunnen naar dezelfde queue-infrastructuur worden gekoppeld
- Lage belasting op OpenMRS (poll-interval configureerbaar, standaard 2 minuten)
- Sliding window vangt zowel nieuwe als gewijzigde afspraken op

**Nadelen**
- Hogere latentie dan directe push (maximaal gelijk aan het poll-interval van 2 minuten)
- Debugging is lastiger doordat communicatie asynchroon verloopt

**Gedrag bij downtime:**

| Scenario | Gedrag |
|----------|--------|
| Communicatiemodule tijdelijk down | RabbitMQ bewaart berichten in duurzame queues; verwerking hervat na herstart |
| OpenMRS tijdelijk down | Poller logt fout, circuit breaker pauzeert na 5 pogingen; na herstel haalt de poller gemiste periode op via watermark |
| RabbitMQ tijdelijk down | Outbox-tabel in Postgres behoudt de data; relay-job publiceert zodra de broker bereikbaar is |

**Schaalbaarheid:** Meerdere OpenMRS-instanties kunnen worden gekoppeld door voor elke instantie een eigen poller-configuratie te gebruiken. Alle pollers publiceren naar dezelfde RabbitMQ exchange. De consumers verwerken berichten ongeacht de bron, wat multi-tenant SaaS-scenario's ondersteunt.

---

## Besluit

**Gekozen: Optie 3 — Event-driven via OpenMRS REST v1 API + RabbitMQ.**

Polling via de REST API is gekozen boven directe databasekoppeling en webhook-push vanwege de betere betrouwbaarheid bij downtime en de betere schaalbaarheid naar meerdere instanties. Het gebruik van RabbitMQ zorgt voor at-least-once delivery via de outbox-tabel in combinatie met duurzame queues.

---

## Noot over FHIR/HL7

Oorspronkelijk was de FHIR2 Appointment API (`/ws/fhir2/R4/Appointment`) gepland als primaire integratie, omdat dit aansluit op de HL7 FHIR R4-standaard. Tijdens implementatie bleek echter dat de FHIR2 module in de gebruikte OpenMRS O3 distributie het Appointment resource type niet ondersteunt:

```json
{
  "issue": [{
    "severity": "error",
    "diagnostics": "HAPI-0302: Unknown resource type 'Appointment'"
  }]
}
```

De integratie maakt daarom gebruik van de OpenMRS REST v1 API (`POST /ws/rest/v1/appointment/search`). Het interne `AppointmentEvent`-model is bewust provider-agnostisch opgezet: als een toekomstige OpenMRS-installatie wel FHIR2 Appointment ondersteunt, kan de poller worden uitgewisseld zonder wijzigingen aan de rest van de module.

---

## Implementatiedetails

- **Primaire poller** (`OpenMrsAppointmentPoller`): elke 2 minuten via `POST /ws/rest/v1/appointment/search` met 48u sliding window
- **Backup reconciliator** (`AppointmentReconciler`): elke 5 minuten via `GET /ws/rest/v1/appointment?lastUpdated={watermark}`
- **Watermark cursor**: bijgehouden in `sync_watermarks` tabel in Postgres per tenant
- **Circuit breaker**: na 5 opeenvolgende fouten → 2 minuten pauze, auto-reset (in-memory)
