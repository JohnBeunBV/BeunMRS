# ADR-002 — Technologie stack

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De communicatiemodule moet notificaties versturen voor meerdere organisaties die gebruikmaken van OpenMRS. De oplossing moet schaalbaar, uitbreidbaar, betrouwbaar en onafhankelijk inzetbaar zijn binnen een SaaS-architectuur.

## Probleem

Welke technologieën worden gebruikt voor het ontwikkelen van de communicatiemodule, en waarom passen deze technologieën bij de eisen van het systeem en de capaciteiten van het team?

---

## Besluit

Voor de ontwikkeling van de communicatiemodule wordt de volgende technologie stack gebruikt:

| Laag | Keuze |
|------|-------|
| Back-end | Java 21 met Spring Boot 3.2 |
| Message Queue | RabbitMQ |
| Database | PostgreSQL |
| Monitoring & observability | Micrometer + Prometheus + Loki + Grafana (zie ADR-010) |
| Containerization | Docker |

---

## Overwogen keuzes en onderbouwing

### Back-end: Java met Spring Boot

#### Optie 1 — Java 21 met Spring Boot 3.2 *(gekozen)*

**Voordelen**
- Sluit aan op het ecosysteem van OpenMRS (zelf ook Java/Spring)
- Spring Boot biedt native ondersteuning voor REST, RabbitMQ (Spring AMQP), PostgreSQL (JDBC/HikariCP), security en scheduling
- Sterke typering voorkomt runtime-fouten in complexe business-logica (multi-tenant filtering, encryptie, retry-logica)
- Uitgebreid testecosysteem (JUnit 5, Mockito, Testcontainers, Spring MockMvc)
- Teamkennis aanwezig

**Nadelen**
- Meer boilerplate dan dynamisch getypeerde alternatieven
- Hogere opstarttijd dan Node.js (gemitigeerd: Spring Boot start in ~2 seconden)

#### Optie 2 — Node.js

**Voordelen**
- Lage opstarttijd, lichtgewicht runtime
- Geschikt voor I/O-intensieve taken

**Nadelen**
- Geen native Spring AMQP-equivalent — RabbitMQ-integratie vereist derde-partij library met minder volwassen retry/DLX-ondersteuning
- Dynamische typering vergroot kans op runtime-fouten in complexe domeinen (multi-tenant isolatie, encryptie)
- Geen aansluiting op het OpenMRS-ecosysteem
- Minder teamkennis

**Afgewezen omdat:** de Spring Boot-integraties (AMQP, JDBC, Security, Scheduling) de ontwikkeltijd significant verkorten en beter aansluiten op de complexiteit van het project.

#### Optie 3 — Python

**Voordelen**
- Snelle prototyping, leesbare syntax
- Goede data-verwerkingsondersteuning

**Nadelen**
- Geen volwassen equivalent van Spring Boot voor enterprise-integraties (AMQP, security, scheduling)
- GIL-beperking bij multi-threaded verwerking (relevant voor parallel verwerken van meerdere tenants)
- Dynamische typering — zelfde risico als Node.js bij complexe domeinen
- Geen teamkennis

**Afgewezen omdat:** Python ontbeert een framework dat de Spring Boot-integraties (AMQP, JDBC, Security) kant-en-klaar biedt, en het team heeft geen Python-achtergrond.

---

### Message Queue: RabbitMQ

#### Optie 1 — RabbitMQ *(gekozen)*

**Voordelen**
- Native ondersteuning voor duurzame queues, DLX, TTL en topic exchanges — exact wat de topologie vereist
- Spring AMQP biedt eersteklas integratie (retry-interceptor, auto-ack, herverbinding)
- Eenvoudig beheer via management UI
- Lichtgewicht: past goed bij Docker Compose-opzet

**Nadelen**
- Extra infrastructuurcomponent
- At-least-once semantiek vereist idempotente consumers (afgehandeld via `seen_appointments`)

#### Optie 2 — Apache Kafka

**Voordelen**
- Hoge doorvoer, persistent log, replay-mogelijkheid

**Nadelen**
- Zwaarder operationeel beheer (ZooKeeper of KRaft, partities, consumer groups)
- TTL en DLX zijn eenvoudiger in RabbitMQ dan in Kafka (vereist aparte compactie-configuratie)
- Overkill voor het eventvolume van dit project (tientallen afspraken per dag per tenant)
- Complexere Docker Compose-configuratie

**Afgewezen omdat:** de operationele overhead van Kafka niet in verhouding staat tot het berichtvolume en de vereiste functionaliteit (TTL, DLX) natiever beschikbaar is in RabbitMQ.

#### Optie 3 — Redis (Streams of Pub/Sub)

**Voordelen**
- Laag geheugengebruik, eenvoudige setup

**Nadelen**
- Redis Streams mist native DLX-ondersteuning
- Pub/Sub is niet persistent: berichten gaan verloren als de consumer offline is
- Minder volwassen Spring-integratie dan RabbitMQ
- At-least-once delivery vereist extra maatwerk

**Afgewezen omdat:** Redis biedt geen native dead-letter afhandeling en de persistentiegaranties zijn onvoldoende voor at-least-once delivery zonder aanzienlijk extra maatwerk.

---

### Database: PostgreSQL

#### Optie 1 — PostgreSQL *(gekozen)*

**Voordelen**
- ACID-transacties: essentieel voor outbox-patroon (atomisch schrijven van event + businessdata)
- Sterke JSON(B)-ondersteuning voor payload-opslag in `outbox_events` en `notification_log`
- Partial unique indexes (gebruikt voor `seen_appointments` deduplicatie)
- CHECK-constraints (gebruikt voor `tenants.provider_name` validatie)
- Uitstekende Testcontainers-ondersteuning voor integratietests

**Nadelen**
- Zwaarder dan embedded alternatieven
- Vereist Docker-container in development

#### Optie 2 — MySQL

**Voordelen**
- Wijdverbreid, goede tooling

**Nadelen**
- Beperkte JSONB-ondersteuning (JSON-kolommen minder krachtig dan PostgreSQL JSONB)
- Partial indexes niet ondersteund — `seen_appointments`-deduplicatie zou anders geïmplementeerd moeten worden
- Minder expressieve CHECK-constraints
- Minder strikte standaard SQL-naleving

**Afgewezen omdat:** de JSONB-payloads en partial indexes centraal staan in het databaseontwerp; MySQL ondersteunt deze niet op hetzelfde niveau als PostgreSQL.

#### Optie 3 — MongoDB

**Voordelen**
- Flexibel schema, goede JSON-opslag

**Nadelen**
- Geen ACID-transacties over meerdere documenten in de vereiste vorm (outbox-patroon vereist atomische multi-table schrijfoperaties)
- Geen CHECK-constraints of partial indexes op native wijze
- Relationele multi-tenant isolatie (foreign keys, cascade) complexer te implementeren
- Minder geschikt voor het primair relationele datamodel van dit project

**Afgewezen omdat:** het outbox-patroon en de multi-tenant isolatie steunen op ACID-transacties en relationele constraints die MongoDB niet natively biedt.

---

### Monitoring & Observability

Zie **ADR-010** voor de volledige afweging tussen OpenTelemetry en Micrometer + Prometheus + Loki. De keuze voor Micrometer + Grafana-stack is daar gemotiveerd.

---

### Containerization: Docker

**Onderbouwing**

Docker maakt consistente deployment mogelijk tussen ontwikkel-, test- en productieomgevingen. Docker Compose orkestreert de volledige stack (PostgreSQL, RabbitMQ, Grafana, Loki, Promtail, NGINX, FakeComWorld) met één commando. Geen volwaardig alternatief overwogen: containerization is een harde eis van de opdracht (SaaS-doelstelling) en Docker is de standaard voor de schaal van dit project.

---

## Consequenties

**Positieve gevolgen**
- Goede schaalbaarheid en onderhoudbaarheid
- Betrouwbare verwerking van notificaties via ACID + outbox
- Ondersteuning voor monitoring en observability
- Eenvoudige deployment via containers
- Technologieën sluiten aan op enterprise-standaarden en teamkennis

**Negatieve gevolgen**
- Hogere complexiteit door meerdere infrastructuurcomponenten
- Team moet kennis hebben van messaging en containerization
- Monitoring vereist extra configuratie en beheer

---

## Samenvatting

De gekozen technologie stack ondersteunt de eisen rondom schaalbaarheid, betrouwbaarheid, monitoring en uitbreidbaarheid van de communicatiemodule. Per laag zijn minimaal twee alternatieven overwogen en afgewezen op basis van concrete criteria: operationele overhead, ontbrekende features, of mismatch met de vereisten van het project.
