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
| Monitoring & observability | OpenTelemetry en Grafana |
| Containerization | Docker |

---

## Overwogen keuzes en onderbouwing

### Back-end: Java met Spring Boot

**Alternatieven:** Node.js, Python

**Onderbouwing**

Java sluit goed aan op het ecosysteem van OpenMRS en wordt veel gebruikt binnen enterprise-omgevingen. Spring Boot biedt uitgebreide ondersteuning voor RESTful API's, dependency injection, security en integratie met databases en messaging-systemen.

Daarnaast ondersteunt Spring Boot het ontwikkelen van schaalbare en onderhoudbare microservices, wat goed aansluit bij de gekozen SaaS-architectuur.

---

### Message Queue: RabbitMQ

**Alternatieven:** Apache Kafka, Redis

**Onderbouwing**

RabbitMQ ondersteunt betrouwbare asynchrone communicatie via queues, inclusief retry-mechanismen en foutafhandeling. Dit is belangrijk omdat externe messaging providers tijdelijk onbeschikbaar kunnen zijn.

In vergelijking met Apache Kafka is RabbitMQ eenvoudiger te implementeren en beter passend bij de omvang en complexiteit van dit project.

---

### Database: PostgreSQL

**Alternatieven:** MySQL, MongoDB

**Onderbouwing**

PostgreSQL biedt sterke ondersteuning voor transacties en data-integriteit (ACID). Dit is essentieel voor het betrouwbaar opslaan van notificaties, verzendstatussen en logginggegevens.

De gegevensstructuur van het systeem bestaat voornamelijk uit relationele data, waardoor een relationele database beter geschikt is dan een NoSQL-oplossing.

---

### Monitoring en Observability: OpenTelemetry en Grafana

**Alternatieven:** Prometheus-only oplossingen

**Onderbouwing**

OpenTelemetry maakt het mogelijk om metrics, logs en traces centraal te verzamelen. Hierdoor ontstaat inzicht in prestaties, foutmeldingen en systeemgedrag.

Grafana biedt dashboards voor real-time monitoring en ondersteunt analyse van throughput, responstijden en foutpercentages. Dit helpt beheerders om problemen snel te detecteren en op te lossen.

---

### Containerization: Docker

**Onderbouwing**

Docker maakt consistente deployment mogelijk tussen ontwikkel-, test- en productieomgevingen. Daarnaast ondersteunt Docker schaalbaarheid en vereenvoudigt het beheer van de SaaS-oplossing.

---

## Consequenties

**Positieve gevolgen**
- Goede schaalbaarheid en onderhoudbaarheid
- Betrouwbare verwerking van notificaties
- Ondersteuning voor monitoring en observability
- Eenvoudige deployment via containers
- Technologieën sluiten aan op enterprise-standaarden

**Negatieve gevolgen**
- Hogere complexiteit door meerdere infrastructuurcomponenten
- Team moet kennis hebben van messaging en containerization
- Monitoring en observability vereisen extra configuratie en beheer

---

## Samenvatting

De gekozen technologie stack ondersteunt de eisen rondom schaalbaarheid, betrouwbaarheid, monitoring en uitbreidbaarheid van de communicatiemodule. Daarnaast sluiten de gekozen technologieën aan bij gangbare standaarden binnen backend-ontwikkeling en bij de capaciteiten van het ontwikkelteam.
