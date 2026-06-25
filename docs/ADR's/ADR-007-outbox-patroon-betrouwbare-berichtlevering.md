# ADR-007 — Outbox patroon voor betrouwbare berichtlevering

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De notificatieservice moet afspraakgebeurtenissen betrouwbaar verwerken en doorsturen naar de juiste messaging provider. Als de service crasht tussen het ophalen van een afspraak en het publiceren naar RabbitMQ, of als RabbitMQ tijdelijk onbereikbaar is, mogen berichten niet verloren gaan (NFR-7).

Het klassieke probleem: een databaseschrijving en een netwerkverzending kunnen niet in één atomaire transactie plaatsvinden. Dit leidt tot het risico op "crash na DB-write, vóór publish" of omgekeerd.

---

## Probleem

Hoe garanderen we at-least-once delivery van afspraakgebeurtenissen naar RabbitMQ, ook bij crashes, netwerkstoringen en servicerestarts?

---

## Overwogen opties

### Optie 1 — Synchrone publicatie direct naar RabbitMQ

De poller of consumer publiceert direct naar RabbitMQ zonder tussenliggende persistentie.

**Voordelen**
- Eenvoudig te implementeren

**Nadelen**
- Geen garantie bij crash: event verloren als service uitvalt na ontvangst maar vóór publish
- Bij RabbitMQ-uitval gaan alle events verloren
- Geen herstelpad na storing

---

### Optie 2 — Transactionele outbox via Change Data Capture (CDC)

Debezium monitort de PostgreSQL write-ahead log (WAL) en publiceert databasewijzigingen automatisch naar Kafka/RabbitMQ.

**Voordelen**
- Echte atomaire transacties: DB-write en publish altijd consistent
- Industriestandaard voor event streaming

**Nadelen**
- Vereist Debezium (apart proces), Kafka of aparte connector
- Aanzienlijke operationele overhead voor de schaal van dit project
- Complexe setup en debugging
- Overkill: de service draait op Docker Compose, niet op een Kafka-cluster

---

### Optie 3 — Polling-gebaseerde outbox *(gekozen)*

Events worden eerst opgeslagen in een `outbox_events`-tabel (in dezelfde transactie als de businesslogica). Een aparte `OutboxRelayJob` pollt deze tabel elke 30 seconden en publiceert ongepubliceerde events naar RabbitMQ. Bij succes wordt het event gemarkeerd als gepubliceerd; bij falen wordt het na maximaal 5 pogingen op `failed` gezet.

**Voordelen**
- Geen extra infrastructuur (alleen PostgreSQL, al aanwezig)
- Herstelbaar na crash: `OutboxRelayJob` herprobeert automatisch bij de volgende run
- Eenvoudig te begrijpen en te debuggen
- Voldoende voor de berichthoeveelheid van dit project (gemeten: 100% success rate)

**Nadelen**
- Maximale vertraging van 30 seconden tussen event en publicatie
- Geen sub-seconde latency zoals bij CDC — acceptabel voor appointment-notificaties

---

## Besluit

**Gekozen: Optie 3 — Polling-gebaseerde outbox (`outbox_events` tabel + `OutboxRelayJob`).**

---

## Onderbouwing

De 30 seconden vertraging is verwaarloosbaar voor afspraakmeldingen die 24 uur of 1 uur vantevoren worden verstuurd. De eenvoud van de polling-aanpak weegt zwaarder dan de latencyverschillen. CDC zou disproportioneel veel complexiteit toevoegen.

Gemeten resultaat: 100% success rate (198/198 events gepubliceerd) in de performancetest.

---

## Implementatiedetails

```
outbox_events tabel:
  id, tenant_id, appointment_uuid, event_type, payload (JSONB),
  published (boolean), retry_count, failed_at, created_at

OutboxService.save():
  → INSERT INTO outbox_events (atomisch met businesslogica)

OutboxRelayJob (elke 30s):
  → SELECT * FROM outbox_events WHERE NOT published AND failed_at IS NULL
  → rabbitTemplate.convertAndSend(exchange, routingKey, event)
  → UPDATE outbox_events SET published = true (bij succes)
  → retry_count++ (bij falen); failed_at (na 5 pogingen)
```

Routingkeys per eventtype:
- `SCHEDULED` → `appointment.scheduled`
- `UPDATED` → `appointment.updated`
- `CANCELLED` → `appointment.cancelled`

---

## Consequenties

**Positief**
- At-least-once delivery gegarandeerd zonder extra infrastructuur
- Herstelbaar na crash of netwerkstoringen
- Zichtbaar: `outbox_events` tabel is direct inspecteerbaar voor debugging

**Negatief**
- Maximaal 30 seconden latency voor event-publicatie (geen probleem voor notificatiedomein)
- `outbox_events` tabel groeit als relay-job langdurig offline is — beheerder dient dit te monitoren

---

## Relatie tot requirements

- **NFR-7** — Zelfstandig + fallback: outbox garandeert herstel na storing
- **NFR-6b** — ACK-equivalent: INSERT in `outbox_events` + `notification_log.status` vormt de bevestigingstrail
- **NFR-6e** — Queueing en retry: outbox implementeert de retry-logica
- **FR-2** — Logging voor factuurcontrole: outbox-status is zichtbaar per notificatiepoging
