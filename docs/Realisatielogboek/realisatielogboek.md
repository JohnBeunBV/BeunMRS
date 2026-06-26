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
| FMEA-structuur opzetten | Het team had de failure modes en mitigaties al bepaald vanuit domeinkennis. Prompt: "Zet de volgende door ons geïdentificeerde failure modes om naar de FMEA-tabelopmaak met kolommen failure mode / effect / oorzaak / maatregel / ADR-koppeling" | Alle inhoud (scenario's, mitigaties, koppeling aan code) was al door het team bepaald — Claude verzorgde alleen de tabelopmaak en consistente naamgeving |
| ADR-opmaak en taalcheck (ADR-005 t/m ADR-011) | Het team nam de architectuurbeslissingen zelf (multi-tenancy-strategie, outbox-keuze, etc.). Prompt: "Controleer of deze door ons geschreven ADR dezelfde opmaak volgt als ADR-001 t/m ADR-004 en verbeter de formulering waar nodig" | Alle inhoud — probleemstelling, afgevallen alternatieven, beslissing, consequenties — is door het team geschreven; Claude corrigeerde alleen taal en opmaak |

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

Gegenereerd op 2026-06-25 via `git log --format="%an" | Sort-Object | Group-Object | Select-Object Count, Name | Sort-Object Count -Descending`.

| Teamlid | Git-gebruikersnaam | Commits | Hoofdbijdragen |
|---|---|---|---|
| Wassim Balouda | Wasssiimm | 45 | C4-diagrammen, README, PR-beheer, FMEA-documentatie, RabbitMQ topologie |
| Thijs van de Veen | Dice-cmd | 21 | OpenMRS poller (`OpenMrsAppointmentPoller`, `AppointmentReconciler`), `ReminderScheduler`, `ReminderDispatchJob`, `DataRetentionJob`, testrapport, herkansingswerk (FMEA-claims, ADR-002, ADR-011, repo-hygiëne) |
| Nick de Rooij | NickdeRooij | 13 | Provider adapters (SwiftSend, LegacyLink, SecurePost, AsyncFlow), `NotificationDispatcher`, `NotificationProviderContractTest` |
| Storm Kroonen | S.k2004 | 11 | Architectuurontwerp, multi-tenant SaaS kern (`TenantContext`, `TenantService`, `TenantApiKeyFilter`), security hardening (`GlobalExceptionHandler`, AES-256), ADRs, Docker Compose |
| **Totaal** | | **90** | |

---

## Relatie tot deliverables

| Deliverable | Status | Bestand |
|---|---|---|
| D4a — Ontwikkeltools | ✅ | Dit document, sectie "Gebruikte ontwikkeltools" |
| D4b — AI-tools + voorbeelden | ✅ | Dit document, sectie "Gebruikte AI-tools" |
| D4c — Commits per teamlid | ✅ (echte git-cijfers; opnieuw draaien zodra alles naar main is gemerged) | Dit document, sectie "Commits per teamlid" |
