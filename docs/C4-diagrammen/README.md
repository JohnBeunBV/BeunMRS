# C4-diagrammen — BeunMRS Notificatiemodule

Drie `.drawio`-bestanden (te openen in [draw.io](https://app.diagrams.net) of de VS Code draw.io-extensie). Ze zijn **bewust als één samenhangend geheel** gemaakt: elk niveau is een inzoom van het vorige, gebaseerd op de werkelijke `docker-compose.yml` en de Java-packagestructuur.

| Niveau | Bestand | Zoomt in op |
|---|---|---|
| **L1 — Context** | [`C4-level1-context.drawio`](C4-level1-context.drawio) | Het hele systeem als black box + externe actoren/systemen |
| **L2 — Containers** | [`C4-level2-container.drawio`](C4-level2-container.drawio) | De binnenkant van *BeunMRS Notificatiemodule* (de Docker-containers) |
| **L3 — Componenten** | [`C4-level3-component.drawio`](C4-level3-component.drawio) | De binnenkant van de container *notification-svc* (de Java-componenten) |

---

## Consistentie tussen de niveaus

> Dit is de borging tegen de eerdere feedback ("de verschillende niveaus van het C4-model zijn niet consistent"). Elk element op een niveau is herleidbaar naar precies één element een niveau hoger.

### L1 → L2 — de black box wordt opengeklapt
De vier externe partijen en alle relaties uit L1 komen **ongewijzigd** terug in L2; alleen *ons systeem* wordt opengeklapt in containers.

| L1-element | Komt in L2 terug als |
|---|---|
| Ziekenhuisbeheerder (persoon) | Zelfde persoon, nu gekoppeld aan `notification-frontend`, `notification-nginx` en `grafana` |
| Patiënt (persoon) | Zelfde persoon, ontvangt SMS van de providers |
| OpenMRS (extern) | Zelfde extern systeem, gekoppeld aan `notification-svc` |
| Messaging providers / FakeComWorld (extern) | Zelfde extern systeem, gekoppeld aan `notification-svc` |
| BeunMRS Notificatiemodule (ons systeem) | **Systeemgrens** met containers: frontend, nginx, svc, db, rabbitmq + observability |

| L1-relatie | Komt in L2 terug als |
|---|---|
| Beheerder → systeem (registreert tenant) | Beheerder → frontend + Beheerder → nginx → svc |
| Systeem → OpenMRS (haalt afspraken op) | svc → OpenMRS |
| Systeem → providers (verstuurt SMS) | svc → providers |
| Providers → patiënt (bezorgt SMS) | providers → patiënt |

### L2 → L3 — de container notification-svc wordt opengeklapt
In L3 is alleen `notification-svc` opengeklapt in componenten. De andere containers uit L2 (`notification-db`, `rabbitmq`) en de externe systemen (`OpenMRS`, providers) verschijnen in L3 als **dezelfde** randelementen — zo blijven de koppelingen herleidbaar.

| L2-container/koppeling | Komt in L3 terug als |
|---|---|
| svc → notification-db (JDBC) | OutboxService, ReminderScheduler, TenantService, jobs → `notification-db` |
| svc → rabbitmq (AMQP) | OutboxRelayJob → rabbitmq (publish) · rabbitmq → AppointmentEventConsumer (consume) |
| svc → OpenMRS (REST v1) | OpenMrsAppointmentPoller, AppointmentReconciler, PersonContactService → OpenMRS |
| svc → providers (REST/SOAP) | NotificationDispatcher → NotificationProvider → 4 adapters → providers |

---

## Bron van waarheid

De diagrammen zijn afgeleid van:

- **Containers (L2):** `docker-compose.yml` — services `notification-frontend`, `notification-nginx`, `notification-svc`, `notification-db`, `rabbitmq`, `prometheus`, `loki`, `promtail`, `grafana` (+ externe `gateway`/OpenMRS en `fakecomworld`).
- **Componenten (L3):** packages onder `notification-service/src/main/java/com/openmrs/notification/` — `adapter/`, `config/`, `consumer/`, `model/`, `outbox/`, `poller/`, `reconciler/`, `scheduler/`, `security/`, `service/`, `tenant/`, `util/`.

> **Bij wijzigingen:** pas eerst de laagste relevante laag aan en werk omhoog. Voeg je een container toe → update L2 én controleer of L1 nog klopt. Voeg je een component/klasse toe → update L3 én controleer of de container in L2 nog dezelfde externe koppelingen heeft.

---

## Stijl- en kleurconventie (C4)

De diagrammen gebruiken de officiële draw.io C4-shapes (`mxgraph.c4.person2` voor personen) met **witte, vette titels** + `[Persoon]`/`[Softwaresysteem]`/`[Container]`/`[Component]` + een lichte omschrijving. Alle vlakken zijn donker genoeg gekleurd zodat de witte tekst leesbaar blijft.

| Kleur | Hex | Betekenis |
|---|---|---|
| Donkerblauw (navy) | `#083F75` | Persoon (actor) |
| Helderblauw | `#1061B0` | Ons systeem (L1, in scope) |
| Middenblauw | `#2E6CA8` | Container (L2) — frontend, nginx, svc, db, rabbitmq |
| Blauw | `#3E7CB1` | Component (L3) / observability-container (L2) |
| Lichter blauw | `#4A86BD` | «interface» (L3, `NotificationProvider`) |
| Grijs | `#736782` | Extern systeem (OpenMRS, providers) |

**Pijlen:** dunne `blockThin`-punt, orthogonaal gerouteerd met line-jumps (`jumpStyle=arc`) zodat kruisende lijnen over elkaar "springen" i.p.v. lelijk snijden.

| Pijl | Betekenis |
|---|---|
| Doorgetrokken | Synchrone/normale interactie of dataflow |
| Gestreept | Telemetrie (L2: metrics/logs) · async of interface-realisatie (L3) |

> **C4-shapes laden niet?** Zie je een lege/rechthoekige box i.p.v. de persoon-vorm, activeer dan eenmalig in draw.io via **More Shapes… → Software → C4** de C4-vormenbibliotheek.

---

## Componentverantwoordelijkheden (L3)

| Component | Package | Verantwoordelijkheid |
|---|---|---|
| `TenantRegistrationController` | `tenant/` | REST POST `/api/register` — validatie + tenant aanmaken |
| `TenantApiKeyFilter` | `tenant/` | Servlet-filter: valideert X-API-Key, zet TenantContext |
| `TenantContext` | `tenant/` | ThreadLocal-wrapper voor actieve tenant per thread |
| `TenantService` | `tenant/` | Tenant-CRUD, API-key hash-lookup, credential-encryptie |
| `OpenMrsAppointmentPoller` | `poller/` | REST v1 polling OpenMRS elke 2 min per tenant |
| `AppointmentReconciler` | `reconciler/` | Fallback polling via watermark (elke 5 min) |
| `PersonContactService` | `service/` | Haalt telefoonnummer op uit OpenMRS per patiënt |
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
| `RestTemplateFactory` / `AppConfig` / `MetricsGauges` | `config/` | Per-tenant RestTemplate, beans, Micrometer-metrics |

---

## Relatie tot requirements

Welke requirements op welk C4-niveau zichtbaar worden (zie ook `docs/Traceerbaarheid/traceerbaarheidsmatrix.md`).

| C4-niveau | Welke requirements worden zichtbaar |
|---|---|
| **L1 Context** | NFR-1 (multi-tenant), NFR-2a (OpenMRS-integratie), NFR-3 (vier providers) |
| **L2 Containers** | NFR-5b (TLS via nginx), NFR-9a (Grafana/Loki/Prometheus), NFR-7 (RabbitMQ + PostgreSQL) |
| **L3 Components** | FR-1a/b (ReminderScheduler), FR-1g/h (EventConsumer), NFR-6e (OutboxRelayJob/RetryJob), NFR-10/11 (DataRetentionJob), NFR-5a (AesEncryptionService) |

---

## Procesvisualisatie — afspraakmeldingsstroom

De end-to-end stroom (OpenMRS → poller → outbox → RabbitMQ → consumer → scheduler → dispatch → provider → `notification_log`) is zichtbaar in **L3** via de edges tussen de componenten. Kort samengevat:

1. `OpenMrsAppointmentPoller` haalt afspraken op (REST v1, elke 2 min) en roept `OutboxService.writePending()` aan.
2. `OutboxRelayJob` publiceert het event naar RabbitMQ (elke 30s) — persist-before-publish.
3. `AppointmentEventConsumer` consumeert het event, roept `NotificationDispatcher` (direct verzenden) en `ReminderScheduler` (24h + 1h plannen) aan.
4. `ReminderDispatchJob` (elke 60s) dispatcht vervallen reminders via `NotificationDispatcher`.
5. `NotificationDispatcher` selecteert de provider via `tenant.providerName`, verstuurt de SMS en logt het resultaat via `OutboxService.recordResult()` in `notification_log`.
