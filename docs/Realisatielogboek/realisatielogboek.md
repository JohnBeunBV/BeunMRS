# Realisatielogboek — BeunMRS Notificatiemodule

**Versie:** 1.0  
**Datum:** 2026-06-24  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## D4a — Gebruikte ontwikkeltools

| Tool | Versie | Doel |
|---|---|---|
| IntelliJ IDEA Ultimate | 2024.x | Primaire IDE voor Java/Spring Boot ontwikkeling |
| Visual Studio Code | 1.89+ | Frontend (React/Vite) en configuratiebestanden |
| Docker Desktop | 4.x | Lokale uitvoering van de volledige stack (PostgreSQL, RabbitMQ, Grafana, etc.) |
| Postman | 11.x | Handmatig testen van OpenMRS REST API en de eigen notification-service API |
| DBeaver Community | 24.x | Directe inspectie van PostgreSQL-tabellen (`notification_log`, `outbox_events`, etc.) |
| Git + GitHub | — | Versiebeheer en samenwerking via pull requests |
| RabbitMQ Management UI | (meegeleverd) | Inspectie van queues, DLQ-diepte en consumer-status |
| Grafana | 10.x | Dashboard voor metrics (Prometheus) en logs (Loki) |

### Coderichtlijnen

- Java: Google Java Style Guide (inspringen 4 spaties, maximale regellengte 120 tekens)
- Bestandsnamen: PascalCase voor klassen, kebab-case voor configuratiebestanden
- SQL: uppercase sleutelwoorden, snake_case kolomnamen
- Alle documentatie in het Nederlands

---

## D4b — Gebruikte AI-tools

### Claude (Anthropic) — Claude Code CLI

**Hoe gebruikt:** Claude Code werd ingezet als co-developer via de CLI. De tool kon bestanden lezen, aanpassen en testsuites uitvoeren binnen de IDE.

**Representatieve taken waarbij AI-assistentie gebruikt is:**

| Taak | Aanpak | Menselijke correctie |
|---|---|---|
| Opzetten van de multi-tenant `TenantContext` + `TenantApiKeyFilter` | Prompt: "Implementeer een ThreadLocal-gebaseerde tenant-context met een servlet-filter die de X-API-Key header valideert en de tenant opzoekt via SHA-256 hash" | Filter miste initieel `finally`-blok voor `TenantContext.clear()` — handmatig gecorrigeerd om thread-pool-leaks te voorkomen |
| Schrijven van `NotificationProviderContractTest` | Prompt: "Schrijf een contract-test die op build-time verifieert dat alle vier providers aanwezig zijn in de Spring-context en unieke namen hebben die overeenkomen met de database CHECK-constraint" | Testcontainers-configuratie voor de database CHECK-validatie was initieel onjuist — gecorrigeerd naar embedded PostgreSQL |
| `OutboxRelayJob` implementatie | Prompt: "Implementeer een outbox relay job die elke 30 seconden ongepubliceerde events oppikt en naar RabbitMQ publiceert met maximaal 5 retry-pogingen" | Batch-size en foutafhandeling bij gedeeltelijk falen van de batch — handmatig bijgesteld |
| FMEA documentatie opstellen | Prompt: "Analyseer de broncode en documenteer 11 failure modes met mitigaties, gekoppeld aan ADRs, klassen en tests" | Risicoscores (waarschijnlijkheid × impact) zijn door het team ingevuld op basis van domeinkennis |
| ADRs schrijven (ADR-005 t/m ADR-010) | Prompt: "Schrijf ADRs in dezelfde opmaak als ADR-001 t/m ADR-004 voor de volgende architectuurbeslissingen: multi-tenancy, provider uitbreidbaarheid, outbox pattern, data retention, teststrategie, monitoring" | Onderbouwing-secties aangepast om teamspecifieke afwegingen te weerspiegelen |

**Wat AI goed deed:**
- Boilerplate code genereren (maprow, JDBC-queries, AMQP-configuratie)
- Consistentie bewaken over meerdere bestanden (alle providers volgen hetzelfde patroon)
- Testscenario's identificeren die het team over het hoofd zag

**Wat handmatig gecorrigeerd werd:**
- Domeinspecifieke beslissingen (tijdzone-strategie, retry-intervallen)
- Beveiligingsgrenzen (PII-masking regels, info-disclosure in foutberichten)
- Integratie met OpenMRS-specifieke edge cases (bijv. `comments` altijd null bij search API)

---

## D4c — Commits per teamlid

Onderstaande tabel is gegenereerd op basis van `git log --author="<naam>" --oneline`.

> **Let op:** voer `git log --format="%an" | sort | uniq -c | sort -rn` uit voor een actueel overzicht.

| Teamlid | Hoofdbijdragen |
|---|---|
| Storm Kroonen | Architectuurontwerp, multi-tenant SaaS kern (`TenantContext`, `TenantService`, `TenantApiKeyFilter`), security hardening (`GlobalExceptionHandler`, AES-256), ADRs |
| Wassim Balouda | RabbitMQ topologie en consumer (`AppointmentEventConsumer`), `OutboxRelayJob`, `FailedNotificationRetryJob`, FMEA-documentatie |
| Nick de Rooij | Provider adapters (SwiftSend, LegacyLink, SecurePost, AsyncFlow), `NotificationDispatcher`, `NotificationProviderContractTest` |
| Thijs van de Veen | OpenMRS poller (`OpenMrsAppointmentPoller`, `AppointmentReconciler`), `ReminderScheduler`, `ReminderDispatchJob`, `DataRetentionJob`, testrapport |

### Git-commando's voor verificatie

```powershell
# Commits per auteur
git log --format="%an" | Sort-Object | Group-Object | Select-Object Count, Name | Sort-Object Count -Descending

# Commits van specifiek teamlid
git log --author="Storm" --oneline | Measure-Object

# Gedetailleerd logboek per teamlid
git log --author="Wassim" --pretty=format:"%h %ad %s" --date=short
```

---

## Relatie tot deliverables

| Deliverable | Status | Bestand |
|---|---|---|
| D4a — Ontwikkeltools | ✅ | Dit document, sectie "Gebruikte ontwikkeltools" |
| D4b — AI-tools + voorbeelden | ✅ | Dit document, sectie "Gebruikte AI-tools" |
| D4c — Commits per teamlid | ✅ (handmatig bijwerken vóór inlevering) | Dit document, sectie "Commits per teamlid" |
