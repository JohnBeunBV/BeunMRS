# ADR-009 — Teststrategie: testpyramide unit → security → contract → integratie

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De service bevat complexe business-logica (reminder-planning, multi-tenant isolatie, provider-routering, encryptie, data-retentie) en meerdere externe afhankelijkheden (PostgreSQL, RabbitMQ, vier providers). De code moet betrouwbaar, onderhoudbaar en uitbreidbaar zijn. Tests moeten aantonen dat alle requirements daadwerkelijk zijn behaald.

---

## Probleem

Welke teststrategie biedt de beste balans tussen dekking, snelheid en onderhoudbaarheid? En hoe tonen we aan dat requirements als multi-tenant isolatie, provideruitbreidbaarheid en encryptie daadwerkelijk werken?

---

## Overwogen opties

### Optie 1 — Alleen unit tests

Alle logica getest met Mockito-mocks; geen integratietests.

**Voordelen**
- Snel (seconden)

**Nadelen**
- SQL-queries en databaseconstraints worden nooit getest
- Tenant-isolatie (row-level filtering) niet aantoonbaar zonder echte database
- Provider-contract niet afdwingbaar bij compile-time

---

### Optie 2 — Alleen end-to-end integratietests

Elke test start de volledige stack (Spring Boot + PostgreSQL + RabbitMQ).

**Voordelen**
- Realistische testomgeving

**Nadelen**
- Trage feedback (minuten per run)
- Moeilijk te isoleren: een fout in de database-setup breekt alle tests
- Niet geschikt voor unit-level regressietests van helpers en encryptie

---

### Optie 3 — Testpyramide: unit + security + contract + integratie *(gekozen)*

Vier testlagen met duidelijk eigen verantwoordelijkheden:
1. **Unit tests (87)** — losse componenten met mocks (Mockito), snelle feedback
2. **Security tests (9)** — HTTP-grenscontrole (filterbehavior, 401/403-scenario's)
3. **Contract tests (10)** — build-time garantie dat alle providers aan het interface voldoen
4. **Integratietests (3)** — end-to-end met echte PostgreSQL (Testcontainers), bewijst multi-tenant isolatie

**Voordelen**
- Snelle feedback op unit-niveau
- Grenscontrole op authenticatie- en autorisatielaag
- Uitbreidbaarheidsgarantie via contract tests (bewijs NFR-12)
- Echte database bewijst SQL-correctheid en tenant-isolatie

**Nadelen**
- Integratietests vereisen Docker (Testcontainers) — CI-afhankelijkheid

---

## Besluit

**Gekozen: Optie 3 — Testpyramide met vier lagen.**

---

## Onderbouwing

De vier lagen vullen elkaar aan. Unit tests geven snelle regressiefeedback. Security tests bewaken de authenticatiegrens. Contract tests zijn de technische bewijsvoering voor NFR-12 (uitbreidbaarheid): ze falen op build-time als een provider vergeten wordt te registreren of niet aan het contract voldoet. Integratietests bewijzen multi-tenant isolatie op databaseniveau — iets dat met mocks niet aantoonbaar is.

---

## Implementatiedetails

| Testlaag | Klassen | Wat wordt getest |
|---|---|---|
| Unit | `MessageHelperTest`, `ReminderSchedulerTest`, `NotificationDispatcherTest`, `OutboxServiceTest`, `AesEncryptionServiceTest`, `AppointmentEventConsumerTest`, `SwiftSendProviderTest`, `AsyncFlowProviderTest`, `LegacyLinkProviderTest`, `SecurePostProviderTest`, `TenantRegistrationControllerTest` | Business-logica, formattering, encryptie, provider-adapter-gedrag |
| Security | `TenantApiKeyFilterTest` | Ontbrekende/ongeldige API-keys (401), cross-tenant toegang (403), ThreadLocal-cleanup |
| Contract | `NotificationProviderContractTest` | Alle vier providers aanwezig in Spring-context, unieke namen, naam matcht DB CHECK-constraint, `@Component` aanwezig |
| Integratie | `EndToEndNotificationFlowTest` | Tenant-registratie → dispatch → `notification_log` INSERT met PII-masking; multi-tenant isolatie; DB CHECK-constraint validatie via Testcontainers |

Operationele testscripts (niet geautomatiseerd in JUnit):
- `loadtest.ps1` — 3 scenario's: baseline (1), load (20), stress (50 afspraken)
- `circuitbreaker-test.ps1` — chaos: OpenMRS-uitval simulatie, circuit-breaker validatie

---

## Consequenties

**Positief**
- 109 tests, alle groen bij elke build
- Contract tests falen vroeg als een nieuwe provider niet aan het contract voldoet
- Integratiescenario's bewijzen NFR-1 (multi-tenant) met echte PostgreSQL

**Negatief**
- Testcontainers vereist Docker tijdens test-run — op CI-omgevingen zonder Docker is dit een blokkade
- Operationele scripts (`loadtest.ps1`, `circuitbreaker-test.ps1`) zijn niet geautomatiseerd in de CI-pipeline

---

## Relatie tot requirements

- **NFR-1** — Multi-tenant isolatie: bewezen door `EndToEndNotificationFlowTest`
- **NFR-3** — Vier providers: bewezen door `NotificationProviderContractTest`
- **NFR-5a** — AES-256: bewezen door `AesEncryptionServiceTest`
- **NFR-12** — Uitbreidbaarheid: bewezen door `NotificationProviderContractTest` (contract-afdwinging)
- **NFR-2c** — Beveiligd: bewezen door `TenantApiKeyFilterTest`
