# ADR-003: Integratiemethode — Hoe koppelt de module aan OpenMRS?

| Veld        | Waarde                   |
|-------------|--------------------------|
| Status      | Geaccepteerd             |
| Datum       | 2025-05-13               |
| Bijgesteld  | 2026-05-20 (na implementatietest) |
| Beslissers  | Platformteam             |

---

## Context

De communicatiemodule moet afspraakgegevens ontvangen vanuit OpenMRS om patiënten tijdig te notificeren. De oplossing moet:

- Betrouwbaar zijn bij uitval van één of meerdere services.
- Schaalbaar zijn naar meerdere OpenMRS-instanties (multi-tenant SaaS).
- Aansluiten op gangbare zorgstandaarden zoals HL7 FHIR R4 waar mogelijk.
- Werken met de standaard OpenMRS O3 referentiedistributie.

---

## Probleem

Via welke integratiemethode ontvangt de communicatiemodule afspraakdata vanuit OpenMRS, en welke oplossing past het beste bij de eisen rondom betrouwbaarheid, schaalbaarheid en standaardisatie?

---

## Overwogen opties

### Optie 1 — Directe databasekoppeling

De communicatiemodule leest afspraakgegevens rechtstreeks uit de database van OpenMRS.

**Voordelen:**
- Eenvoudig op te zetten
- Snelle toegang tot data zonder extra API-laag

**Nadelen:**
- Sterke afhankelijkheid van de interne databasestructuur van OpenMRS
- Wijzigingen in het databaseschema kunnen de koppeling breken
- Slecht schaalbaar bij meerdere OpenMRS-instanties
- Direct database-toegang van buitenaf is een veiligheidsrisico
- Sluit niet aan op HL7/FHIR-standaarden

**Gevolgen bij downtime:**
Geen ingebouwde bescherming — als de communicatiemodule offline is, worden wijzigingen gemist tenzij een extra watermark-mechanisme wordt gebouwd.

---

### Optie 2 — Directe HTTP-koppeling (webhook/push van OpenMRS)

OpenMRS roept bij iedere afspraakwijziging een webhook aan op de communicatiemodule.

**Voordelen:**
- Lage latentie — directe verwerking bij elke wijziging

**Nadelen:**
- Als de communicatiemodule tijdelijk offline is, gaan events verloren
- OpenMRS moet het adres van de communicatiemodule kennen — sterke koppeling
- Slecht schaalbaar naar meerdere instanties zonder aparte configuratie per instantie

**Gevolgen bij downtime:**
Events gaan permanent verloren als de communicatiemodule op het moment van de webhook niet bereikbaar is.

---

### Optie 3 — Event-driven via REST polling + RabbitMQ ✅ Gekozen

De communicatiemodule bevraagt periodiek de OpenMRS REST API en plaatst nieuwe of gewijzigde afspraken in RabbitMQ voor asynchrone verwerking.

**Werking:**
```
[Scheduler] → POST /ws/rest/v1/appointment/search
               { startDate: now, endDate: now + 48h }
           → vergelijk status met seen_appointments tabel
           → nieuw of gewijzigd: sla op in outbox_events
           → publiceer AppointmentEvent naar RabbitMQ exchange
           → consumer verwerkt bericht en verstuurt notificatie
```

**Voordelen:**
- Betrouwbare verwerking via queueing en retries
- Werkt zonder aanpassingen aan OpenMRS
- Goed schaalbaar: meerdere OpenMRS-instanties kunnen naar dezelfde queue-infrastructuur worden gekoppeld
- Lage belasting op OpenMRS (poll-interval configureerbaar, standaard 2 minuten)
- Sliding window vangt zowel nieuwe als gewijzigde afspraken op

**Nadelen:**
- Hogere latentie dan directe push (maximaal gelijk aan het poll-interval van 2 minuten)
- Debugging is lastiger doordat communicatie asynchroon verloopt

**Gevolgen bij downtime:**

| Scenario | Gedrag |
|---|---|
| Communicatiemodule tijdelijk down | RabbitMQ bewaart berichten in duurzame queues; verwerking hervat na herstart |
| OpenMRS tijdelijk down | Poller logt fout, circuit breaker pauzeert na 5 pogingen; na herstel haalt de poller de gemiste periode op via de watermark |
| RabbitMQ tijdelijk down | Outbox-tabel in Postgres behoudt de data; relay-job publiceert zodra de broker bereikbaar is |

**Schaalbaarheid:**
Meerdere OpenMRS-instanties kunnen worden gekoppeld door voor elke instantie een eigen poller-configuratie te gebruiken. Alle pollers publiceren naar dezelfde RabbitMQ exchange. De consumers verwerken berichten ongeacht de bron, wat multi-tenant SaaS-scenario's ondersteunt.

---

## Definitieve keuze

**Gekozen: Optie 3 — Event-driven via OpenMRS REST v1 API + RabbitMQ**

Polling via de REST API is gekozen boven directe databasekoppeling en webhook-push vanwege de betere betrouwbaarheid bij downtime en de betere schaalbaarheid naar meerdere instanties. Het gebruik van RabbitMQ zorgt voor at-least-once delivery via de outbox-tabel in combinatie met duurzame queues.

### Noot over FHIR/HL7

Oorspronkelijk was de FHIR2 Appointment API (`/ws/fhir2/R4/Appointment`) gepland als primaire integratie, omdat dit aansluit op de HL7 FHIR R4-standaard. Tijdens implementatie bleek echter dat de FHIR2 module in de gebruikte OpenMRS O3 distributie het `Appointment` resource type niet ondersteunt:

```json
{
  "issue": [{
    "severity": "error",
    "diagnostics": "HAPI-0302: Unknown resource type 'Appointment'"
  }]
}
```

De integratie maakt daarom gebruik van de OpenMRS REST v1 API (`POST /ws/rest/v1/appointment/search`). Het interne `AppointmentEvent`-model is bewust provider-agnostisch opgezet: als een toekomstige OpenMRS-installatie wel FHIR2 Appointment ondersteunt, kan de poller worden uitgewisseld zonder wijzigingen aan de rest van de module.

### Veerkrachtmechanismen in de implementatie

| Mechanisme | Implementatie |
|---|---|
| Sliding window | Poll de komende 48 uur — vangt zowel nieuwe als gewijzigde afspraken op |
| Status-change detectie | Vergelijkt huidige status met `seen_appointments` tabel; event alleen bij wijziging |
| Circuit breaker | Na 5 opeenvolgende fouten pauzeert de poller 2 minuten; herstelt automatisch |
| Persist-before-publish | Afspraken eerst opgeslagen in `outbox_events`, dan pas gepubliceerd naar RabbitMQ |
| Backup reconciliator | Elke 5 minuten via `GET /ws/rest/v1/appointment?lastUpdated={watermark}` als extra vangnet |

---

## Referenties

- [OpenMRS REST v1 Appointment API](https://wiki.openmrs.org/display/docs/Appointment+Scheduling+Module)
- [OpenMRS FHIR2 Module](https://github.com/openmrs/openmrs-module-fhir2)
- [HL7 FHIR R4 Appointment Resource](https://www.hl7.org/fhir/appointment.html)
- `notification-service/.../poller/OpenMrsAppointmentPoller.java` — implementatie poller
- `notification-service/.../reconciler/AppointmentReconciler.java` — implementatie reconciliator
