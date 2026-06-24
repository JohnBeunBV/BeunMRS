# Traceerbaarheidsmatrix — BeunMRS Notificatiemodule

**Versie:** 1.0  
**Datum:** 2026-06-24  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Doel

Dit document toont de directe koppeling tussen elke functionele en niet-functionele requirement, de architectuurbeslissing die de aanpak rechtvaardigt, de klasse(n) die de eis implementeren en de test(s) die aantonen dat de eis daadwerkelijk behaald is.

Deze matrix is het antwoord op de vraag: **"Hoe weten jullie dat requirement X behaald is?"**

---

## Leeswijzer

| Kolom | Betekenis |
|---|---|
| **Requirement** | ID + korte omschrijving uit `docs/Info/OpdrachtOpenMRS.md` |
| **ADR** | Architectuurbeslissing(en) die de keuze onderbouwen |
| **Implementatie** | Java-klasse(n) + kernmethode(n) die de eis realiseren |
| **Bewijs (test)** | Test klasse(n) + scenario die aantonen dat de eis werkt |
| **Status** | ✅ Bewezen / ⚠️ Handmatig te verifiëren |

---

## Functionele Eisen

| Requirement | ADR | Implementatie | Bewijs (test) | Status |
|---|---|---|---|---|
| **FR-1a** — Notificatie 24 uur vóór afspraak | ADR-003, ADR-004, ADR-007 | `ReminderScheduler.scheduleReminders()` — berekent `appointmentTime - 24h` en slaat op in `scheduled_notifications` | `ReminderSchedulerTest` — test `send_at` berekening voor 24h offset | ✅ |
| **FR-1b** — Notificatie 1 uur vóór afspraak | ADR-003, ADR-004, ADR-007 | `ReminderScheduler.scheduleReminders()` — berekent `appointmentTime - 1h` en slaat op in `scheduled_notifications` | `ReminderSchedulerTest` — test `send_at` berekening voor 1h offset | ✅ |
| **FR-1c** — Bericht bevat datum en tijd | ADR-002 | `MessageHelper.formatTime(instant, timezone)` — formatteert in Nederlandse notatie per tenant-tijdzone | `MessageHelperTest` — test tijdnotatie met tijdzone-override | ✅ |
| **FR-1d** — Bericht bevat locatie (polikliniek/kamer) | ADR-003 | `MessageHelper.locationSuffix(locationName)` — voegt locatienaam toe uit OpenMRS `locationUuid`-veld | `MessageHelperTest` — test locationSuffix formatting | ✅ |
| **FR-1e** — Bericht bevat instructies (nuchter etc.) | ADR-003 | `MessageHelper.commentsSuffix(comments)` — voegt `comments`-veld toe; `OpenMrsAppointmentPoller.enrichComments()` haalt dit op via extra GET-call | `MessageHelperTest` — test commentsSuffix; `postmanrequests.md` stap 5 voor manuele verificatie | ✅ |
| **FR-1f** — Sla notificatie over als afspraak al gestart is | ADR-004 | `ReminderDispatchJob.processReminder()` — controleert `send_at < NOW()` vóór dispatch; zet status op `skipped` | `ReminderSchedulerTest` — scenario: dispatch na afspraakmomeet | ✅ |
| **FR-1g** — Bij annulering: stop reminders | ADR-004 | `AppointmentEventConsumer.onCancellation()` → `ReminderScheduler.cancelReminders()` — UPDATE `scheduled_notifications` WHERE `status='scheduled'` | `AppointmentEventConsumerTest` — scenario: CANCELLED event triggert cancelReminders | ✅ |
| **FR-1h** — Bij wijziging: pas reminders aan | ADR-004 | `AppointmentEventConsumer.onAppointment()` — bij UPDATED: eerst `cancelReminders()`, dan `scheduleReminders()` | `AppointmentEventConsumerTest` — scenario: UPDATED event triggert cancel + reschedule | ✅ |
| **FR-2** — Logging voor factuurcontrole | ADR-007 | `OutboxService.recordResult()` — slaat elke notificatiepoging op in `notification_log` met provider, status, retry_count, gemaskeerde ontvanger | `OutboxServiceTest` — test INSERT in notification_log met gemaskeerde PII | ✅ |
| **FR-3** — Één provider per organisatie | ADR-005, ADR-006 | `NotificationDispatcher.dispatch()` — leest `tenant.providerName` en routeert naar de juiste `NotificationProvider`-implementatie | `NotificationDispatcherTest` — test routing naar geconfigureerde provider; `NotificationProviderContractTest` — DB CHECK-constraint validatie | ✅ |

---

## Niet-Functionele Eisen

| Requirement | ADR | Implementatie | Bewijs (test) | Status |
|---|---|---|---|---|
| **NFR-1** — Multi-tenant SaaS | ADR-001, ADR-005 | `TenantContext` (ThreadLocal) — draagt tenant door request/job lifecycle; alle queries gefilterd op `tenant_id`; `TenantApiKeyFilter` zet context per request | `TenantApiKeyFilterTest` — test cross-tenant isolatie; `EndToEndNotificationFlowTest` — test multi-tenant isolatie met echte PostgreSQL | ✅ |
| **NFR-2a** — Integratie passend bij doel | ADR-003 | REST v1 polling elke 2 min via `OpenMrsAppointmentPoller`; RabbitMQ als buffer; watermark-cursor in `sync_watermarks` | `postmanrequests.md` stap 6 (appointment search); integratie via Docker Compose stack | ✅ |
| **NFR-2b** — Gedocumenteerd voor beheerders | — | `docs/README-beheerder.md` — ~700 regels operationele handleiding | Handmatige review van `README-beheerder.md` | ✅ |
| **NFR-2c** — Beveiligd volgens best practices | ADR-002, ADR-005 | API key auth (`TenantApiKeyFilter`), AES-256 (`AesEncryptionService`), PII-masking (`MessageHelper.mask()`), `GlobalExceptionHandler` (geen info-disclosure) | `TenantApiKeyFilterTest`; `AesEncryptionServiceTest`; `SECURITY-AUDIT.md` | ✅ |
| **NFR-3** — 4 providers (SwiftSend, LegacyLink, AsyncFlow, SecurePost) | ADR-006 | Vier `NotificationProvider`-implementaties in `adapter/` package; elk met eigen credential-afhandeling | `NotificationProviderContractTest` — valideert dat alle vier providers aanwezig zijn, Spring-discoverable zijn en aan het contract voldoen | ✅ |
| **NFR-4** — OpenMRS 2.7.x compatibiliteit | ADR-003 | Gebruikt uitsluitend `/ws/rest/v1/` endpoints die stabiel zijn sinds OpenMRS 2.x; geen FHIR2 (zie HAPI-0302 notitie in ADR-003) | `postmanrequests.md` — gedocumenteerde endpoints; `docs/Info/OpdrachtOpenMRS.md` versievereiste | ⚠️ Handmatig te verifiëren op 2.7.x |
| **NFR-5a** — AES-256 voor opslag van credentials | ADR-002 | `AesEncryptionService.encrypt()` — AES-256-GCM met willekeurige IV; sleutel via `DB_ENCRYPTION_KEY` env-var | `AesEncryptionServiceTest` — test encryptie/decryptie, tamper-detectie, willekeurige IV per plaintext | ✅ |
| **NFR-5b** — TLS 1.3 voor transport | ADR-002 | `infra/nginx/` — NGINX met `ssl_protocols TLSv1.3`; extern bereikbaar via `https://localhost:4000` | `SECURITY-AUDIT.md` sectie transport; handmatige verificatie via `curl --tlsv1.3` | ✅ |
| **NFR-5c** — Credentials niet in code/config | ADR-002 | `.env`-bestand (in `.gitignore`); `AesEncryptionService` laadt sleutel uit env-var; geen hardcoded secrets in productie-pad | Git history check; `SECURITY-AUDIT.md` sectie secrets | ✅ |
| **NFR-5d** — Logs niet onbeveiligd (PII gemaskeerd) | ADR-005 | `MessageHelper.mask()` — telefoon wordt `+31****678`; Loki draait op intern Docker-netwerk (poort 3100 niet extern) | `OutboxServiceTest` — controleert dat gemaskeerde waarden opgeslagen worden; `MessageHelperTest` mask()-tests | ✅ |
| **NFR-6a** — HL7 berichtontvangst + validatie | ADR-004 | Jackson-gebaseerde deserialisatie van `AppointmentEvent` met strict type-mapping; RabbitMQ `MessageConverter` valideert JSON-structuur | `AppointmentEventConsumerTest` — test event-deserialisatie en null-afhandeling | ⚠️ Aantonen met expliciete validatielog |
| **NFR-6b** — ACK voor ontvangstbevestiging | ADR-004, ADR-007 | Spring AMQP auto-ack na succesvolle consumerverwerking; `notification_log.status` als audittrail; outbox-patroon als bevestigingsbewijs | `OutboxServiceTest` — INSERT in notification_log bevestigt ontvangst; `README-beheerder.md` sectie 8 | ✅ |
| **NFR-6c** — Logging en tracking | ADR-007 | `notification_log` — provider, status, retry_count, tenant_id, appointment_uuid per notificatiepoging | `OutboxServiceTest`; `EndToEndNotificationFlowTest` — controleert notification_log na dispatch | ✅ |
| **NFR-6d** — Berichttransformatie | ADR-006 | Provider-adapters mappen `AppointmentEvent` naar providerspecifiek formaat (JSON, SOAP XML, AsyncFlow commands) | `SwiftSendProviderTest`, `LegacyLinkProviderTest`, `AsyncFlowProviderTest` — testen berichtinhoud per provider | ✅ |
| **NFR-6e** — Queueing en retry | ADR-004, ADR-007 | RabbitMQ queues (durable, DLX); `OutboxRelayJob` (30s interval, max 5 pogingen); `FailedNotificationRetryJob` (exponential backoff: 5→15 min) | `OutboxServiceTest`; `FMEA_Documentatie.md` FM-2, FM-5; `PERFORMANCE-RAPPORT.md` (100% outbox success) | ✅ |
| **NFR-7** — Zelfstandig + fallback bij uitval | ADR-003, ADR-007 | Outbox-patroon (`outbox_events` → `OutboxRelayJob`); circuit breaker (5 fouten → 2 min pauze per tenant-slug); `AppointmentReconciler` als fallback poller | `circuitbreaker-test.ps1` operationeel testscript; `FMEA_Documentatie.md` FM-9 | ✅ |
| **NFR-8** — Karaktersets (UTF-8) | ADR-002 | PostgreSQL DB op UTF-8; Spring `application.yml` UTF-8; JSON-responses via Jackson; NGINX met UTF-8 charset | `README-beheerder.md` sectie 9; handmatige test met niet-Latijnse karakters (Arabisch/Chinees) | ⚠️ Handmatige UTF-8 testbericht aantonen |
| **NFR-9a** — Monitoring + dashboard | ADR-010 | Prometheus-metrics via Micrometer; Loki voor logs (Promtail); Grafana dashboard op `http://localhost:3000` | `README-beheerder.md` sectie 10 (Grafana dashboard URLs); `infra/grafana/provisioning/` | ⚠️ Dashboard-screenshot als bewijs toevoegen |
| **NFR-9b** — OpenTelemetry | ADR-010 | Bewust niet geïmplementeerd — Micrometer + Prometheus + Loki dekt NFR-9a volledig. Motivatie in ADR-010. | ADR-010 motivatie | ✅ (gemotiveerd) |
| **NFR-10** — 14-dagen verwijdering PII | ADR-008 | `DataRetentionJob.deletePiiData()` — dagelijks 02:00, DELETE FROM `notification_log` WHERE `created_at < NOW() - 14 days` | `DataRetentionJob` aanwezig; cron `"0 0 2 * * *"` in broncode | ✅ |
| **NFR-11** — 1-jaar meta-info retentie | ADR-008 | `DataRetentionJob.archiveToAuditLog()` + `purgeOldAuditLog()` — PII-vrije kopie naar `notification_audit_log`; purge na 1 jaar | `DataRetentionJob` aanwezig; `notification_audit_log` tabel in schema | ✅ |
| **NFR-12** — Uitbreidbaar naar andere modules | ADR-006 | `NotificationProvider` interface — nieuwe klasse + `@Component` = nieuwe provider zonder verdere wijzigingen; RabbitMQ routing keys uitbreidbaar | `NotificationProviderContractTest` — bewijst Spring-discoverability en interface-naleving van alle providers | ✅ |
| **NFR-13** — Tijdzones | ADR-005 | `tenants.timezone` (IANA, default `Europe/Amsterdam`); `MessageHelper.formatTime(instant, timezone)` converteert UTC naar tenant-tijdzone bij weergave | `MessageHelperTest` — test tijdnotatie met tijdzone-override; `ReminderSchedulerTest` — test UTC-opslag | ✅ |

---

## Samenvatting

| Categorie | Totaal | ✅ Bewezen | ⚠️ Handmatig |
|---|---|---|---|
| Functionele eisen (FR) | 10 | 10 | 0 |
| Niet-functionele eisen (NFR) | 13 | 10 | 3 |
| **Totaal** | **23** | **20** | **3** |

### Openstaande handmatige verificaties

| Requirement | Actie |
|---|---|
| NFR-4 | Test poller tegen OpenMRS 2.7.x specifiek |
| NFR-8 | Stuur testbericht met Arabische/Chinese tekst door de stack |
| NFR-9a | Voeg Grafana dashboard-screenshot toe als bewijs |

---

## Relatie tot ADRs

Alle ADRs zijn te vinden in `docs/ADR 1 - 4/`. De nummering correspondeert direct:

| ADR | Behandelt requirements |
|---|---|
| ADR-001 | NFR-1, NFR-2a |
| ADR-002 | NFR-2c, NFR-5a, NFR-5b, NFR-5c, NFR-8, NFR-9a |
| ADR-003 | FR-1a t/m FR-1h, NFR-2a, NFR-4, NFR-6a |
| ADR-004 | FR-1f, FR-1g, FR-1h, NFR-6b, NFR-6e, NFR-7 |
| ADR-005 | NFR-1, NFR-5d, NFR-13, FR-3 |
| ADR-006 | FR-3, NFR-3, NFR-12 |
| ADR-007 | FR-1a, FR-1b, FR-2, NFR-6b, NFR-6c, NFR-6e, NFR-7 |
| ADR-008 | NFR-10, NFR-11 |
| ADR-009 | Teststrategie (meta) |
| ADR-010 | NFR-9a, NFR-9b |
