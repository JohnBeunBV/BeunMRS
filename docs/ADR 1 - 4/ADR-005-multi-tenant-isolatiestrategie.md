# ADR-005 — Multi-tenant isolatiestrategie: ThreadLocal + row-level filtering

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De module draait als multi-tenant SaaS-dienst waarbij meerdere ziekenhuisorganisaties (tenants) van dezelfde service-instantie gebruikmaken. Elke tenant heeft eigen OpenMRS-credentials, eigen messaging provider en eigen patiëntdata. Volledige data-isolatie is een harde vereiste: tenant A mag nooit data van tenant B zien of beïnvloeden (NFR-1).

Elke HTTP-request en elke achtergrondtaak (scheduled job, RabbitMQ-consumer) moet weten welke tenant actief is, zodat alle databasevragen automatisch op de juiste tenant gefilterd worden.

---

## Probleem

Hoe zorgen we ervoor dat alle code — van HTTP-request tot achtergrondtaak — altijd weet welke tenant actief is, zonder dat elke methode expliciet een `tenantId`-parameter mee hoeft te krijgen?

---

## Overwogen opties

### Optie 1 — Schema-per-tenant

Elke tenant krijgt een eigen PostgreSQL-schema (`tenant_amc`, `tenant_umcu`, etc.). Queries worden gerouteerd naar het juiste schema via een Spring-datasource-wrapper.

**Voordelen**
- Sterke fysieke isolatie
- Geen risico op vergeten WHERE-clausule

**Nadelen**
- Complexe migrations (schema-wijziging moet voor elk tenant-schema worden herhaald)
- Moeilijk te schalen bij honderden tenants
- Vereist extra infrastructuur (dynamic datasource routing, Liquibase per schema)
- Overkill bij huidige schaal (tientallen tenants)

---

### Optie 2 — Aparte database per tenant

Elke tenant krijgt een volledig eigen PostgreSQL-instantie.

**Voordelen**
- Maximale isolatie (ook op infrastructuurniveau)

**Nadelen**
- Operationeel onhaalbaar: database-instanties, backups, updates per tenant
- Conflicteert met SaaS-doelstelling (gedeelde infrastructuur)
- Vereist dynamische datasource-configuratie

---

### Optie 3 — Row-level filtering met ThreadLocal TenantContext *(gekozen)*

Alle tabellen bevatten een `tenant_id UUID NOT NULL`-kolom. Elke query filtert altijd op `tenant_id`. De actieve tenant wordt bijgehouden in een `ThreadLocal<UUID>` (`TenantContext`), die per request of job gezet en na afloop verwijderd wordt.

**Voordelen**
- Eenvoudige implementatie: één extra kolom, geen infrastructuurwijzigingen
- Volledige Spring MVC- en Spring Scheduler-compatibiliteit
- Schaalbaar naar honderden tenants zonder operationele overhead
- Centraal afdwingbaar: `TenantApiKeyFilter` zet context voor elke HTTP-request

**Nadelen**
- Risico op vergeten `tenant_id` in een query (mitigatie: code review + contract tests)
- ThreadLocal vereist expliciete cleanup om memory-leaks in thread-pools te voorkomen

---

## Besluit

**Gekozen: Optie 3 — Row-level filtering met `TenantContext` (ThreadLocal).**

---

## Onderbouwing

De oplossing sluit aan bij de Spring Boot-architectuur van de service en is proportioneel aan de schaal van de opdracht. Schema-per-tenant of aparte databases voegen operationele complexiteit toe die niet gerechtvaardigd wordt door de vereisten.

De ThreadLocal-aanpak is een bewezen patroon in multi-tenant Java-applicaties (o.a. Hibernate multi-tenancy, Spring Security SecurityContextHolder).

---

## Implementatiedetails

| Component | Bestand | Rol |
|---|---|---|
| `TenantContext` | `tenant/TenantContext.java` | ThreadLocal-wrapper met `set()`, `get()`, `clear()` |
| `TenantApiKeyFilter` | `tenant/TenantApiKeyFilter.java` | Servlet-filter: valideert X-API-Key, zet `TenantContext`, cleant op in `finally`-blok |
| `TenantService.findByApiKey()` | `tenant/TenantService.java` | SHA-256 hash-lookup in `tenants`-tabel |
| Alle scheduled jobs | `scheduler/`, `outbox/` | Zetten `TenantContext` expliciet vóór elke tenant-specifieke verwerking |

Alle tabellen met tenant-data bevatten `tenant_id UUID NOT NULL REFERENCES tenants(id)`.

---

## Consequenties

**Positief**
- Minimale infrastructuurwijzigingen
- Transparant voor business-logica: geen `tenantId`-parameter door de gehele call-stack
- `TenantApiKeyFilter` garandeert dat elke API-call geauthentiseerd en geïsoleerd is

**Negatief**
- Elke nieuwe query moet `WHERE tenant_id = ?` bevatten — dit wordt bewaakt via code review en de contract-tests in `EndToEndNotificationFlowTest`
- `TenantContext.clear()` moet altijd in een `finally`-blok worden aangeroepen om thread-pool-leaks te voorkomen — dit is afgedwongen in `TenantApiKeyFilter`

---

## Relatie tot requirements

- **NFR-1** — Multi-tenant SaaS: primaire eis die deze beslissing motiveert
- **NFR-5d** — Logs niet onbeveiligd: `TenantContext` zorgt dat logs altijd tenant-scoped zijn
- **NFR-13** — Tijdzones: `tenant.timezone` opgehaald via `TenantContext` bij berichtopmaak
- **FR-3** — Één provider per organisatie: `tenant.providerName` opgehaald via `TenantContext`
