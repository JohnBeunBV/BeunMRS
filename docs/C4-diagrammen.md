# C4-diagrammen — BeunMRS Notificatiemodule

**Versie:** 1.0  
**Datum:** 2026-06-24  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

Gebaseerd op het C4-model (Simon Brown): vier abstractieniveaus — Context, Containers, Components, Code.  
Dit document bevat Level 1 (Context), Level 2 (Containers) en Level 3 (Components van de notification-service).

---

## Level 1 — Systeemcontextdiagram

**Vraag: Wat doet het systeem en wie gebruikt het?**

```
┌───────────────────────────────────────────────────────────────┐
│  [Extern systeem]                                             │
│  OpenMRS                                                      │
│  Patiëntendossier en afspraakbeheer                          │
└───────────────────┬───────────────────────────────────────────┘
                    │  REST v1 polling
                    │  (elke 2 min, POST /ws/rest/v1/appointment/search)
                    ▼
┌───────────────────────────────────────────────────────────────┐
│  [Ons systeem]                                                │
│  BeunMRS Notificatiemodule                                    │
│  Multi-tenant SaaS communicatiedienst                        │
│  Stuurt automatische afspraakmeldingen via SMS               │
└───────┬───────────────────────────────────┬───────────────────┘
        │                                   │
        │  SMS (REST/SOAP)                  │  Beheerderstoegang
        ▼                                   ▼
┌────────────────────┐             ┌────────────────────┐
│  [Extern systeem]  │             │  [Gebruiker]       │
│  FakeComWorld      │             │  Ziekenhuisbeheerder│
│  Simuleert vier    │             │  Registreert tenant,│
│  SMS-providers     │             │  configureert       │
│  (SwiftSend,       │             │  providers en API-  │
│  LegacyLink,       │             │  sleutels           │
│  AsyncFlow,        │             └────────────────────┘
│  SecurePost)       │
└────────────────────┘
```

**Actoren:**
- **Ziekenhuisbeheerder** — registreert de organisatie als tenant, kiest messaging provider
- **Patiënt** — ontvangt SMS-herinneringen (is geen directe gebruiker van het systeem)
- **OpenMRS** — extern bronsysteem voor afspraakgegevens
- **FakeComWorld** — externe simulator voor vier messaging providers

---

## Level 2 — Containerdiagram

**Vraag: Uit welke deploybare onderdelen bestaat het systeem?**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  BeunMRS Notificatiemodule (Docker Compose stack)                           │
│                                                                             │
│  ┌──────────────────┐     HTTPS      ┌──────────────────┐                  │
│  │  notification-   │◄──────────────►│  notification-   │                  │
│  │  frontend        │                │  nginx           │                  │
│  │  React/Vite      │                │  TLS 1.3 proxy   │                  │
│  │  :3001 (ext)     │                │  :4000 (ext)     │                  │
│  └──────────────────┘                └────────┬─────────┘                  │
│                                               │ HTTP intern                │
│                                               ▼                            │
│  ┌──────────────────────────────────────────────────────────┐               │
│  │  notification-svc                                        │               │
│  │  Spring Boot 3.2 / Java 21                              │               │
│  │  :8080 (intern)                                         │               │
│  │                                                          │               │
│  │  - REST API (tenant registratie, health)                │               │
│  │  - RabbitMQ consumers (afspraakevents)                  │               │
│  │  - Scheduled jobs (reminders, outbox, retry, retentie)  │               │
│  │  - OpenMRS REST poller                                  │               │
│  └───────┬───────────────────────┬──────────────────────────┘               │
│          │ JDBC                  │ AMQP                                     │
│          ▼                       ▼                                          │
│  ┌───────────────┐     ┌─────────────────┐                                  │
│  │  PostgreSQL   │     │  RabbitMQ       │                                  │
│  │  :5433 (ext)  │     │  :15672 (mgmt)  │                                  │
│  │  8 tabellen   │     │  topic exchange │                                  │
│  │               │     │  + DLX          │                                  │
│  └───────────────┘     └─────────────────┘                                  │
│                                                                             │
│  ┌──────────────────────────────────────┐                                   │
│  │  Observability stack                 │                                   │
│  │  Grafana :3000 · Loki :3100         │                                   │
│  │  Prometheus · Promtail              │                                   │
│  └──────────────────────────────────────┘                                   │
└─────────────────────────────────────────────────────────────────────────────┘

[Extern] OpenMRS :80 ◄── polling ── notification-svc
[Extern] FakeComWorld :1337 ◄── SMS-calls ── notification-svc
```

**Containers:**

| Container | Technologie | Verantwoordelijkheid |
|---|---|---|
| `notification-svc` | Spring Boot 3.2 / Java 21 | Kernlogica: polling, dispatching, scheduling, tenantbeheer |
| `notification-nginx` | NGINX + TLS 1.3 | Reverse proxy, TLS-terminatie voor notification-svc |
| `notification-frontend` | React/Vite + NGINX | Tenantregistratie-UI |
| `postgres` | PostgreSQL 16 | Persistente opslag (8 tabellen) |
| `rabbitmq` | RabbitMQ 3.13 | Asynchrone berichtenwachtrij met DLX |
| `grafana` | Grafana 10 | Monitoring dashboard |
| `loki` | Loki | Log-aggregatie |
| `promtail` | Promtail | Log-collector (Docker → Loki) |
| `openmrs` (extern) | OpenMRS + gateway | Patiënten- en afspraakbeheer (niet van ons) |
| `fakecomworld` (extern) | FakeComWorld | SMS-provider simulator |

---

## Level 3 — Componentendiagram (notification-svc)

**Vraag: Uit welke componenten bestaat de notification-service?**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  notification-svc (Spring Boot)                                             │
│                                                                             │
│  ┌────────────────────────────────────────────────────────────┐              │
│  │  API-laag                                                  │              │
│  │  TenantRegistrationController  TenantAdminController       │              │
│  │  TenantApiKeyFilter (auth)      GlobalExceptionHandler      │              │
│  └───────────────────────────┬────────────────────────────────┘              │
│                              │                                              │
│  ┌────────────────────┐       │       ┌─────────────────────────────────┐   │
│  │  Polling-laag      │       │       │  Event-consumer-laag            │   │
│  │  OpenMrsAppointment│       │       │  AppointmentEventConsumer       │   │
│  │  Poller            │       │       │  (onAppointment, onCancellation)│   │
│  │  AppointmentRecon- │       │       └────────────────┬────────────────┘   │
│  │  ciler             │       │                        │                   │
│  └─────────┬──────────┘       │                        │                   │
│            │ RabbitMQ publish  │                        │                   │
│            ▼                  ▼                        ▼                   │
│  ┌──────────────────────────────────────────────────────────────┐            │
│  │  Domeinlaag                                                  │            │
│  │                                                              │            │
│  │  NotificationDispatcher ──► NotificationProvider (interface) │            │
│  │                              ├─ SwiftSendProvider           │            │
│  │                              ├─ SecurePostProvider          │            │
│  │                              ├─ LegacyLinkProvider          │            │
│  │                              └─ AsyncFlowProvider           │            │
│  │                                                              │            │
│  │  ReminderScheduler ──► scheduled_notifications (DB)         │            │
│  │  ReminderDispatchJob (elke 60s)                             │            │
│  │  FailedNotificationRetryJob (backoff 5→15 min)             │            │
│  │  DataRetentionJob (dagelijks 02:00)                         │            │
│  │                                                              │            │
│  │  OutboxService ──► outbox_events (DB)                       │            │
│  │  OutboxRelayJob (elke 30s) ──► RabbitMQ                     │            │
│  └──────────────────────────────────────────────────────────────┘            │
│                                                                             │
│  ┌────────────────────────────────────────────────────────────┐              │
│  │  Infrastructuurlaag                                        │              │
│  │  TenantService  TenantContext  AesEncryptionService        │              │
│  │  PersonContactService  MessageHelper                       │              │
│  │  RestTemplateFactory  AppConfig                            │              │
│  └────────────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Componentverantwoordelijkheden:**

| Component | Package | Verantwoordelijkheid |
|---|---|---|
| `TenantRegistrationController` | `tenant/` | REST POST `/api/register` — validatie + tenant aanmaken |
| `TenantApiKeyFilter` | `tenant/` | Servlet-filter: valideert X-API-Key, zet TenantContext |
| `TenantContext` | `tenant/` | ThreadLocal-wrapper voor actieve tenant per thread |
| `OpenMrsAppointmentPoller` | `poller/` | REST v1 polling OpenMRS elke 2 min per tenant |
| `AppointmentReconciler` | `reconciler/` | Fallback polling via watermark (elke 5 min) |
| `AppointmentEventConsumer` | `consumer/` | RabbitMQ-listener voor appointment.scheduled / .cancelled |
| `NotificationDispatcher` | `service/` | Routeert event naar de juiste provider via `tenant.providerName` |
| `NotificationProvider` | `adapter/` | Interface voor alle vier provider-implementaties |
| `ReminderScheduler` | `scheduler/` | Plant 24h- en 1h-herinneringen in `scheduled_notifications` |
| `ReminderDispatchJob` | `scheduler/` | Pollt `scheduled_notifications`, dispatcht vervallen reminders |
| `FailedNotificationRetryJob` | `scheduler/` | Herprobeert mislukte notificaties (max 3×, backoff) |
| `DataRetentionJob` | `scheduler/` | Archiveert + verwijdert PII na 14 dagen, audit na 1 jaar |
| `OutboxService` | `outbox/` | Schrijft events naar `outbox_events` vóór publicatie |
| `OutboxRelayJob` | `outbox/` | Publiceert ongepubliceerde outbox-events naar RabbitMQ (30s) |
| `AesEncryptionService` | `security/` | AES-256-GCM encryptie/decryptie voor credentials |
| `MessageHelper` | `util/` | Berichtopmaak, tijdzone-conversie, PII-masking |
| `PersonContactService` | `service/` | Haalt telefoonnummer op uit OpenMRS per patiënt |

---

## Procesvisualisatie — Afspraakmeldingsstroom

```
OpenMRS          notification-svc              RabbitMQ        Provider (SMS)
   │                    │                          │                  │
   │◄── polling ────────│ (elke 2 min)             │                  │
   │                    │                          │                  │
   │ afspraaklijst ─────►│                          │                  │
   │                    │ writePending()            │                  │
   │                    │──► outbox_events (DB)     │                  │
   │                    │                          │                  │
   │                    │──► publish ──────────────►│                  │
   │                    │                          │                  │
   │                    │◄── consume ──────────────│                  │
   │                    │                          │                  │
   │                    │ scheduleReminders()       │                  │
   │                    │──► scheduled_notifications (DB, 24h + 1h)   │
   │                    │                          │                  │
   │                    │                          │                  │
   │    [60 seconden later — ReminderDispatchJob]  │                  │
   │                    │                          │                  │
   │                    │ dispatch()               │                  │
   │                    │──────────────────────────────────────────►  │
   │                    │                          │      SMS verstuurd│
   │                    │                          │◄──────────────── │
   │                    │ recordResult()            │                  │
   │                    │──► notification_log (DB)  │                  │
```

---

## Relatie tot requirements

| C4-niveau | Welke requirements worden zichtbaar |
|---|---|
| L1 Context | NFR-1 (multi-tenant), NFR-2a (OpenMRS-integratie), NFR-3 (vier providers) |
| L2 Containers | NFR-5b (TLS via nginx), NFR-9a (Grafana/Loki/Prometheus), NFR-7 (RabbitMQ + PostgreSQL) |
| L3 Components | FR-1a/b (ReminderScheduler), FR-1g/h (EventConsumer), NFR-6e (OutboxRelayJob/RetryJob), NFR-10/11 (DataRetentionJob), NFR-5a (AesEncryptionService) |
