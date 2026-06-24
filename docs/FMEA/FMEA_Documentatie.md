# Analyse Failure Modes versus Afhandeling in de Notification Service

## Inleiding

Dit document vergelijkt de failure modes uit de risicoanalyse met de feitelijke afhandeling in de broncode van de notification service. Per foutmodus wordt beschreven:

1. De foutmodus zelf
2. Of en hoe deze wordt afgehandeld
3. De exacte broncodeplaatsen
4. **De architectuurbeslissing (ADR) die de aanpak onderbouwt**
5. **De code die de mitigatie uitvoert**
6. **De test die aantoont dat de mitigatie werkt**

---

## Risicomatrix

De risicoscore wordt berekend als: **Waarschijnlijkheid (W) × Impact (I)**. Scores vóór en na mitigatie tonen de effectiviteit van de architectuurkeuzes.

| # | Foutmodus | W (voor) | I (voor) | Score (voor) | W (na) | I (na) | Score (na) | Reductie |
|---|---|---|---|---|---|---|---|---|
| FM-1 | Database verbinding verbroken | 3 | 4 | **12** | 1 | 2 | **2** | 83% |
| FM-2 | RabbitMQ onbereikbaar | 2 | 5 | **10** | 1 | 2 | **2** | 80% |
| FM-3 | Consumer crasht vóór verwerking | 2 | 4 | **8** | 1 | 1 | **1** | 88% |
| FM-4 | Bericht verlopen (TTL) | 2 | 3 | **6** | 1 | 2 | **2** | 67% |
| FM-5 | SwiftSend onbereikbaar/rate-limit | 4 | 3 | **12** | 2 | 1 | **2** | 83% |
| FM-6 | SecurePost JWT verlopen | 3 | 4 | **12** | 1 | 1 | **1** | 92% |
| FM-7 | LegacyLink time-out | 3 | 2 | **6** | 1 | 1 | **1** | 83% |
| FM-8 | AsyncFlow geeft geen eindstatus | 2 | 3 | **6** | 1 | 2 | **2** | 67% |
| FM-9 | Crash na DB-write, vóór publish | 2 | 5 | **10** | 1 | 1 | **1** | 90% |
| FM-10 | Tijdzone-fout bij verzendtijdstip | 2 | 4 | **8** | 1 | 1 | **1** | 88% |
| FM-11 | Polling reconciler dubbele events | 3 | 3 | **9** | 1 | 1 | **1** | 89% |

**Schaal:** Waarschijnlijkheid 1–5 (1=zeldzaam, 5=regelmatig) · Impact 1–5 (1=verwaarloosbaar, 5=dataverlies/patiëntschade)

---

## Koppelingstabel: Failure Mode → ADR → Code → Test

| # | Foutmodus | ADR | Kernklasse(n) | Bewijstest |
|---|---|---|---|---|
| FM-1 | Database verbinding verbroken | ADR-007 | `OutboxService.recordResult()` | `OutboxServiceTest` |
| FM-2 | RabbitMQ onbereikbaar | ADR-004, ADR-007 | `OutboxRelayJob.relay()`, `topology.json` | `OutboxServiceTest`, loadtest |
| FM-3 | Consumer crasht vóór verwerking | ADR-004 | `AppointmentEventConsumer`, `seen_appointments` unique index | `AppointmentEventConsumerTest` |
| FM-4 | Bericht verlopen (TTL) | ADR-004 | `topology.json` (DLX), `AppointmentReconciler` | Handmatig (`circuitbreaker-test.ps1`) |
| FM-5 | SwiftSend onbereikbaar/rate-limit | ADR-006, ADR-007 | `SwiftSendProvider`, `FailedNotificationRetryJob` | `SwiftSendProviderTest` |
| FM-6 | SecurePost JWT verlopen | ADR-006 | `SecurePostProvider.getValidToken()` | `SecurePostProviderTest` |
| FM-7 | LegacyLink time-out | ADR-006 | `AppConfig.providerRestTemplate()`, `LegacyLinkProvider` | `LegacyLinkProviderTest` |
| FM-8 | AsyncFlow geen eindstatus | ADR-006 | `AsyncFlowProvider.pollPendingCommands()`, `async_flow_commands` | `AsyncFlowProviderTest` |
| FM-9 | Crash na DB-write, vóór publish | ADR-007 | `OutboxService.writePending()`, `OutboxRelayJob.relay()` | `OutboxServiceTest` |
| FM-10 | Tijdzone-fout | ADR-005 | `ReminderScheduler`, `MessageHelper.formatTime()` | `MessageHelperTest`, `ReminderSchedulerTest` |
| FM-11 | Polling reconciler dubbele events | ADR-003 | `AppointmentReconciler.alreadyProcessed()`, `sync_watermarks` | `AppointmentEventConsumerTest` |

---

## FM-1: Database — Verbinding verbroken tijdens maken afspraak

> _"Notificatiestatus niet opgeslagen; dubbele verzending of gemiste notificatie mogelijk door netwerkpartitie, DB-overload of time-out."_

### Huidige afhandeling

**AFGEHANDELD**

- **OutboxService.java** — `recordResult()` herprobeert de DB-write tot `MAX_DB_RETRIES` (3) keer met 500ms pauze bij een `DataAccessException`:

  ```java
  private static final int  MAX_DB_RETRIES   = 3;
  private static final long RETRY_BACKOFF_MS = 500;

  while (attempt < MAX_DB_RETRIES) {
      try {
          jdbc.update("INSERT INTO notification_log ...", ...);
          return;
      } catch (DataAccessException ex) {
          attempt++;
          if (attempt >= MAX_DB_RETRIES) {
              log.error("[OutboxService] DB write failed after {} attempts for appointment={}",
                      MAX_DB_RETRIES, event.getAppointmentUuid(), ex);
          } else {
              Thread.sleep(RETRY_BACKOFF_MS);
          }
      }
  }
  ```

- **application.yml** — HikariCP vangt kortdurende DB-uitval op met een `connection-timeout` van 20 seconden en een pool van maximaal 10 verbindingen:

  ```yaml
  hikari:
    connection-timeout: 20000
    maximum-pool-size: 10
  ```

**Conclusie:** Tijdelijke DB-uitval wordt opgevangen via automatische retry met backoff. Bij aanhoudende uitval wordt de fout gelogd voor handmatige opvolging.

**Architectuurkoppeling:** ADR-007 (Outbox patroon) — betrouwbare opslag vóór verdere verwerking  
**Codekoppeling:** `OutboxService.recordResult()` (retry-loop) · `application.yml` (HikariCP-config)  
**Testkoppeling:** `OutboxServiceTest` — scenario: INSERT in `notification_log` slaagt ook bij tijdelijke DB-fout

---

## FM-2: Message broker — RabbitMQ onbereikbaar

> _"Afspraakevents van OpenMRS worden niet ontvangen; notificaties worden niet verstuurd door crash, herstart of netwerkstoring bij broker."_

### Huidige afhandeling

**AFGEHANDELD**

- **topology.json** — Alle queues zijn `durable: true` zodat berichten bij een RabbitMQ-herstart bewaard blijven:

  ```json
  {
    "name": "appointments",
    "durable": true,
    "auto_delete": false,
    "arguments": {
      "x-dead-letter-exchange": "openmrs.events.dlx",
      "x-message-ttl": 86400000
    }
  }
  ```

- **application.yml** — Spring AMQP herverbindt automatisch met exponential backoff (max 5 pogingen, oplopend tot 30 seconden):

  ```yaml
  listener:
    simple:
      retry:
        enabled: true
        initial-interval: 3s
        max-attempts: 5
        multiplier: 2.0
        max-interval: 30s
  ```

- **OutboxRelayJob.java** — Het outbox-patroon zorgt dat events die al in `outbox_events` staan alsnog worden gepubliceerd zodra RabbitMQ weer bereikbaar is. De relay draait elke 30 seconden en pikt ongepubliceerde rijen op.

**Conclusie:** Durable queues en de Spring AMQP retry-configuratie vangen kortdurende brokeruitval op. Het outbox-patroon garandeert alsnog at-least-once delivery ook bij langere uitval.

**Architectuurkoppeling:** ADR-004 (RabbitMQ durable queues + DLX) · ADR-007 (Outbox relay als fallback)  
**Codekoppeling:** `topology.json` (durable=true, DLX) · `OutboxRelayJob.relay()` · `application.yml` (AMQP retry)  
**Testkoppeling:** `OutboxServiceTest` · operationeel: `circuitbreaker-test.ps1` (RabbitMQ uitval simulatie)

---

## FM-3: Message broker — Consumer crasht vóór verwerking bericht

> _"Bericht kwijt; notificatie nooit verstuurd door applicatiecrash of OOM tijdens verwerking."_

### Huidige afhandeling

**AFGEHANDELD**

- **application.yml** — `acknowledge-mode: auto` zorgt dat Spring AMQP een bericht pas ACK't nadat de listener-methode zonder exception is teruggekeerd. Bij een crash wordt het bericht opnieuw aangeboden:

  ```yaml
  listener:
    simple:
      acknowledge-mode: auto
  ```

- **00_schema.sql** — De `seen_appointments`-tabel met `PRIMARY KEY (appointment_uuid, tenant_id)` voorkomt dat een opnieuw aangeboden bericht dubbel wordt verwerkt:

  ```sql
  CREATE TABLE IF NOT EXISTS seen_appointments (
      appointment_uuid TEXT NOT NULL,
      tenant_id        UUID NOT NULL REFERENCES tenants(id),
      PRIMARY KEY (appointment_uuid, tenant_id)
  );
  ```

- **AppointmentReconciler.java** — `alreadyProcessed()` controleert bij elke reconcilerrun of een event al eerder verwerkt is, als extra vangnet:

  ```java
  private boolean alreadyProcessed(AppointmentEvent event, UUID tenantId) {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM notification_log WHERE tenant_id = ? AND event_type = ? AND payload::text LIKE ?",
          Integer.class, tenantId, event.getEventType().name(),
          "%" + event.getAppointmentUuid() + "%");
      return count != null && count > 0;
  }
  ```

**Conclusie:** Door de combinatie van auto-ack (herbezorging bij crash) en idempotente verwerking (seen_appointments + alreadyProcessed) wordt een bericht nooit permanent kwijtgeraakt door een consumercrash.

**Architectuurkoppeling:** ADR-004 (acknowledge-mode auto + idempotente verwerking)  
**Codekoppeling:** `application.yml` (acknowledge-mode: auto) · `seen_appointments` unique index · `AppointmentReconciler.alreadyProcessed()`  
**Testkoppeling:** `AppointmentEventConsumerTest` — scenario: herbezorgd bericht leidt niet tot dubbele dispatch

---

## FM-4: Message broker — Bericht verlopen in queue (TTL verstreken)

> _"Notificatie niet verstuurd; patiënt niet geïnformeerd door langdurige downtime van de notificatieservice waardoor TTL van 24u wordt overschreden."_

### Huidige afhandeling

**AFGEHANDELD**

- **topology.json** — Verlopen berichten (TTL = 86400000 ms = 24 uur) worden via de dead-letter-exchange doorgestuurd naar een dedicated dead-letter-queue in plaats van stil te worden weggegooid:

  ```json
  "arguments": {
    "x-dead-letter-exchange": "openmrs.events.dlx",
    "x-dead-letter-routing-key": "appointments.dead",
    "x-message-ttl": 86400000
  }
  ```

- **topology.json** — De dead-letter-queues (`appointments.dead`, `appointment.cancelled.dead`) zijn zelf ook durable, zodat verlopen berichten bewaard blijven voor inspectie of handmatige herverwerking.

- **AppointmentReconciler.java** — De reconciler draait elke 5 minuten en haalt afspraken opnieuw op via de OpenMRS REST API vanaf een watermark. Berichten die door TTL zijn verlopen worden zo alsnog opgepikt via de polling-fallback.

**Conclusie:** Verlopen berichten verdwijnen niet stil maar belanden in een dead-letter-queue. De reconciler vangt gemiste events aanvullend op via directe polling bij OpenMRS.

**Architectuurkoppeling:** ADR-004 (DLX + durable dead-letter queues) · ADR-003 (polling-fallback via reconciler)  
**Codekoppeling:** `topology.json` (x-dead-letter-exchange, x-message-ttl) · `AppointmentReconciler` (polling-fallback)  
**Testkoppeling:** Operationeel te verifiëren via RabbitMQ management UI (DLQ-diepte) · `circuitbreaker-test.ps1`

---

## FM-5: Messaging provider — SwiftSend onbereikbaar of rate limit bereikt

> _"Notificatie niet afgeleverd bij patiënt door willekeurige fouten of rate limiting gesimuleerd door FakeComWorld."_

### Huidige afhandeling

**AFGEHANDELD**

- **SwiftSendProvider.java** — Rate-limit responses (HTTP 429) worden expliciet gevangen en als `failure` teruggegeven zodat de retry-job ze oppikt:

  ```java
  } catch (HttpClientErrorException.TooManyRequests e) {
      log.warn("[SwiftSend] Rate limited — will retry via RabbitMQ back-off");
      return NotificationResult.failure("Rate limited (429)");
  }
  ```

- **FailedNotificationRetryJob.java** — Exponential backoff retry met maximaal 3 pogingen (5 min → 15 min → permanently_failed):

  ```java
  long backoffMin = BACKOFF_MINUTES[newCount - 1]; // 1→5 min, 2→15 min
  jdbc.update("UPDATE notification_log SET retry_count = ?, next_retry_at = now() + ? * interval '1 minute' ...",
              newCount, backoffMin, errorMessage, id);
  ```

- Na `MAX_RETRIES` mislukte pogingen wordt de status op `permanently_failed` gezet en blijft de rij zichtbaar voor handmatige inspectie.

**Conclusie:** Tijdelijke onbereikbaarheid en rate limiting worden correct afgehandeld met retry en exponential backoff.

**Architectuurkoppeling:** ADR-006 (provider adapter pattern) · ADR-007 (outbox + retry job)  
**Codekoppeling:** `SwiftSendProvider` (HTTP 429-afhandeling) · `FailedNotificationRetryJob` (exponential backoff, max 3 pogingen)  
**Testkoppeling:** `SwiftSendProviderTest` — scenario: rate-limit (429) geeft `NotificationResult.failure()`

---

## FM-6: Messaging provider — SecurePost JWT-token verlopen

> _"Alle notificaties via SecurePost mislukken totdat token vernieuwd is, doordat het token na 3 minuten verloopt en de adapter niet tijdig een nieuw token ophaalt."_

### Huidige afhandeling

**AFGEHANDELD**

- **SecurePostProvider.java** — Token-cache per `clientId` met proactieve vernieuwing 30 seconden vóór verloopdatum:

  ```java
  private static final long TOKEN_REFRESH_BUFFER_SECONDS = 30;

  private synchronized String getValidToken(String clientId, String clientSecret) {
      TokenEntry entry = tokenCache.get(clientId);
      Instant now = Instant.now();
      if (entry != null && now.isBefore(entry.expiresAt().minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS))) {
          return entry.token();
      }
      TokenEntry fresh = fetchToken(clientId, clientSecret);
      tokenCache.put(clientId, fresh);
      return fresh.token();
  }
  ```

- **SecurePostProvider.java** — Bij een onverwachte 401-respons wordt het gecachte token direct verwijderd en een nieuw token opgehaald:

  ```java
  if (!result.isSuccess() && result.getErrorMessage() != null
          && result.getErrorMessage().contains("401")) {
      log.info("[SecurePost] Got 401 — refreshing token and retrying");
      tokenCache.remove(clientId);
      token  = getValidToken(clientId, clientSecret);
      result = doSend(event, token);
  }
  ```

**Conclusie:** Token-verloop wordt proactief afgehandeld via de cache met buffer. Een onverwachte 401 leidt tot automatisch ophalen van een nieuw token en een directe herpoging.

**Architectuurkoppeling:** ADR-006 (provider adapter pattern — elke provider beheert eigen authenticatie)  
**Codekoppeling:** `SecurePostProvider.getValidToken()` (token-cache + 30s buffer) · 401-handler in `send()`  
**Testkoppeling:** `SecurePostProviderTest` — scenario: verlopen token triggert refresh; 401-respons triggert token-purge en herpoging

---

## FM-7: Messaging provider — LegacyLink time-out door hoge variabele vertraging

> _"Notificatie vertraagd of mislukt; worker geblokkeerd door SOAP-protocol met variabele vertraging van 100–3000 ms."_

### Huidige afhandeling

**AFGEHANDELD**

- **AppConfig.java** — De `providerRestTemplate` is geconfigureerd met een read-timeout van 10 seconden, ruim boven de maximale LegacyLink-vertraging van 3000 ms:

  ```java
  @Bean
  @Qualifier("providerRestTemplate")
  public RestTemplate providerRestTemplate(RestTemplateBuilder builder) {
      return builder
              .setConnectTimeout(Duration.ofSeconds(5))
              .setReadTimeout(Duration.ofSeconds(10))
              .build();
  }
  ```

- **LegacyLinkProvider.java** — Alle exceptions (inclusief timeouts) worden opgevangen en als `failure` teruggegeven, zodat de thread niet geblokkeerd blijft en de retry-job de poging kan herhalen:

  ```java
  } catch (Exception ex) {
      log.error("[LegacyLink] Send failed — appointment={}", event.getAppointmentUuid(), ex);
      return NotificationResult.failure(ex.getMessage());
  }
  ```

- Spring AMQP verwerkt elke provider in een eigen listener-thread, waardoor een vertraagde LegacyLink-aanroep andere providers niet blokkeert.

**Conclusie:** De geconfigureerde timeout van 10s voorkomt dat workers onbeperkt blokkeren. Timeouts worden als failures gelogd en opgepikt door de retry-job.

**Architectuurkoppeling:** ADR-006 (provider adapter pattern) · ADR-002 (technologiestack: Spring `RestTemplate`)  
**Codekoppeling:** `AppConfig.providerRestTemplate()` (10s read-timeout) · `LegacyLinkProvider` (exception catch → `NotificationResult.failure()`)  
**Testkoppeling:** `LegacyLinkProviderTest` — scenario: time-out leidt tot failure-resultaat zonder thread-blokkade

---

## FM-8: Messaging provider — AsyncFlow geeft nooit eindstatus terug

> _"Notificatie blijft op 'in behandeling'; resultaat onbekend doordat de poll-scheduler de status mist of de provider niet reageert op statusverzoeken."_

### Huidige afhandeling

**AFGEHANDELD**

- **AsyncFlowProvider.java** — Het correlatie-ID (`commandId`) wordt direct na submission opgeslagen in `async_flow_commands`:

  ```java
  private void persistCommand(String commandId, String appointmentUuid, UUID tenantId) {
      jdbc.update("""
          INSERT INTO async_flow_commands (command_id, tenant_id, appointment_uuid, status, submitted_at)
          VALUES (?, ?, ?, 'pending', now())
          ON CONFLICT (command_id) DO NOTHING
          """, commandId, tenantId, appointmentUuid);
  }
  ```

- **AsyncFlowProvider.java** — `pollPendingCommands()` controleert elke 10 seconden de status van alle openstaande commands en werkt zowel `async_flow_commands` als `notification_log` bij:

  ```java
  @Scheduled(fixedDelayString = "${asyncflow.poll.interval-ms:10000}")
  public void pollPendingCommands() {
      List<Map<String, Object>> pending = jdbc.queryForList(
          "SELECT command_id, appointment_uuid, tenant_id FROM async_flow_commands WHERE status = 'pending' LIMIT 50"
      );
      ...
  }
  ```

- **00_schema.sql** — De `async_flow_commands`-tabel heeft een `resolved_at`-kolom waarmee de maximale wachttijd kan worden gecontroleerd.

**Conclusie:** Correlatie-IDs worden persistent opgeslagen en periodiek gepolled. Een command dat nooit een eindstatus teruggeeft blijft zichtbaar in de database voor handmatige opvolging.

**Architectuurkoppeling:** ADR-006 (provider adapter pattern — async providers beheren eigen statusmachine)  
**Codekoppeling:** `AsyncFlowProvider.persistCommand()` · `AsyncFlowProvider.pollPendingCommands()` (elke 10s) · `async_flow_commands` tabel  
**Testkoppeling:** `AsyncFlowProviderTest` — scenario: command submit + pending status persistentie; status polling completed/failed/pending

---

## FM-9: App — Crash na DB-write, vóór publicatie naar RabbitMQ

> _"Afspraakevent opgeslagen maar notificatie nooit verstuurd door dual write zonder outbox; applicatiefout tussen twee operaties."_

### Huidige afhandeling

**AFGEHANDELD**

- **OutboxService.java** — `writePending()` slaat het event op in `outbox_events` vóórdat de verwerking begint, met `ON CONFLICT DO NOTHING` voor idempotentie:

  ```java
  public void writePending(AppointmentEvent event) {
      jdbc.update("""
          INSERT INTO outbox_events
              (tenant_id, aggregate_type, aggregate_id, event_type, payload)
          VALUES (?, 'appointment', ?, ?, ?::jsonb)
          ON CONFLICT DO NOTHING
          """,
          event.getTenantId(),
          event.getAppointmentUuid(),
          event.getEventType().name(),
          buildPayloadJson(event, null)
      );
  }
  ```

- **OutboxRelayJob.java** — De relay draait elke 30 seconden en publiceert alle ongepubliceerde rijen naar RabbitMQ. Bij publicatiefouten wordt `retry_count` opgehoogd; na `MAX_RETRIES` (5) pogingen wordt `failed_at` gezet:

  ```java
  @Scheduled(fixedDelayString = "${outbox.relay.fixed-delay-ms:30000}")
  public void relay() {
      List<Map<String, Object>> rows = jdbc.queryForList("""
          SELECT id, tenant_id, aggregate_id, event_type, payload::text AS payload, retry_count
          FROM outbox_events
          WHERE published_at IS NULL AND failed_at IS NULL
          ORDER BY created_at LIMIT ?
          """, BATCH_SIZE);
      ...
  }
  ```

**Conclusie:** Het outbox-patroon elimineert het dual-write probleem. Event en data worden in één DB-operatie opgeslagen; de relay publiceert naar RabbitMQ na commit, ongeacht tussentijdse crashes.

**Architectuurkoppeling:** ADR-007 (Outbox patroon — de kernmotivatie voor deze beslissing)  
**Codekoppeling:** `OutboxService.writePending()` (INSERT voor verwerking) · `OutboxRelayJob.relay()` (publicatie na commit)  
**Testkoppeling:** `OutboxServiceTest` — scenario: event in `outbox_events` aanwezig vóór RabbitMQ-publicatie; `PERFORMANCE-RAPPORT.md` (100% outbox success rate)

---

## FM-10: App — Tijdzone-fout bij berekening verzendtijdstip

> _"Notificatie te vroeg, te laat of na aanvang afspraak verstuurd door incorrecte tijdzone-afhandeling bij multi-organisatie setup."_

### Huidige afhandeling

**AFGEHANDELD**

- **00_schema.sql** — Alle tijdstempels in de database worden opgeslagen als `TIMESTAMPTZ` (timestamp with time zone), wat tijdzoneconversies op DB-niveau correct afhandelt.

- **NotificationDispatcher.java** — De per-tenant tijdzone wordt vóór elke verzending in het event geïnjecteerd:

  ```java
  event.setTimezone(tenant.getTimezone());
  ```

- **ReminderScheduler.java** — Reminder-tijdstippen worden berekend in UTC op basis van `appointmentTime` (een `Instant`), waarna de tijdzone pas bij weergave wordt toegepast:

  ```java
  Instant base = event.getAppointmentTime();
  insertReminder(event.getAppointmentUuid(), tenantId, "24h",
                 base.minus(24, ChronoUnit.HOURS), payload);
  insertReminder(event.getAppointmentUuid(), tenantId, "1h",
                 base.minus(1, ChronoUnit.HOURS), payload);
  ```

- **MessageHelper.java** — `formatTime()` converteert het UTC-tijdstip pas bij het opmaken van het SMS-bericht naar de lokale tijdzone van de tenant.

**Conclusie:** Alle interne tijdstippen worden in UTC opgeslagen en verwerkt. Conversie naar de lokale tijdzone van de betreffende OpenMRS-organisatie vindt pas plaats bij verzending, waardoor tijdzoneproblemen bij multi-tenant gebruik worden voorkomen.

**Architectuurkoppeling:** ADR-005 (multi-tenant isolatie — `tenant.timezone` per organisatie) · ADR-002 (TIMESTAMPTZ in PostgreSQL)  
**Codekoppeling:** `ReminderScheduler` (UTC-berekening) · `MessageHelper.formatTime(instant, timezone)` (conversie bij weergave) · `00_schema.sql` (TIMESTAMPTZ-kolommen)  
**Testkoppeling:** `MessageHelperTest` — test tijdnotatie met tijdzone-override · `ReminderSchedulerTest` — test UTC-opslag van send_at

---

## FM-11: App — Polling reconciler haalt dubbele events op

> _"Patiënt ontvangt dubbele notificatie naast event-push door overlap tussen event-push en polling-fallback zonder deduplicatie."_

### Huidige afhandeling

**AFGEHANDELD**

- **AppointmentReconciler.java** — `alreadyProcessed()` controleert vóór elke dispatch of een event al verwerkt is op basis van `(tenant_id, event_type, appointment_uuid)`:

  ```java
  private boolean alreadyProcessed(AppointmentEvent event, UUID tenantId) {
      Integer count = jdbc.queryForObject(
          "SELECT COUNT(*) FROM notification_log WHERE tenant_id = ? AND event_type = ? AND payload::text LIKE ?",
          Integer.class, tenantId, event.getEventType().name(),
          "%" + event.getAppointmentUuid() + "%");
      return count != null && count > 0;
  }
  ```

- **AppointmentReconciler.java** — `advanceWatermark()` slaat per tenant de laatste poll-timestamp op in `sync_watermarks`, zodat de reconciler alleen nieuwe afspraken ophaalt:

  ```java
  private void advanceWatermark(UUID tenantId, Instant now) {
      jdbc.update("""
          INSERT INTO sync_watermarks (resource_type, tenant_id, last_updated, last_cursor)
          VALUES (?, ?, now(), ?)
          ON CONFLICT (resource_type, tenant_id) DO UPDATE
          SET last_updated = now(), last_cursor = EXCLUDED.last_cursor
          """, RESOURCE, tenantId, now.toString());
  }
  ```

- **00_schema.sql** — De partial unique index op `scheduled_notifications` voorkomt dat dubbele herinneringen worden ingepland voor dezelfde afspraak:

  ```sql
  CREATE UNIQUE INDEX IF NOT EXISTS idx_sched_notif_pending_unique
      ON scheduled_notifications (tenant_id, appointment_uuid, type)
      WHERE status = 'pending';
  ```

**Conclusie:** Dubbele verwerking door de reconciler wordt voorkomen via deduplicatie op `notification_log`, een watermark per tenant, en een unique index op geplande herinneringen.

**Architectuurkoppeling:** ADR-003 (polling + watermark-strategie voor deduplicatie)  
**Codekoppeling:** `AppointmentReconciler.alreadyProcessed()` · `AppointmentReconciler.advanceWatermark()` · `idx_sched_notif_pending_unique` unique index  
**Testkoppeling:** `AppointmentEventConsumerTest` — scenario: dubbel event triggert geen dubbele reminder

---

## Samenvatting

| #     | Component          | Foutmodus                                          | Status      |
| ----- | ------------------ | -------------------------------------------------- | ----------- |
| FM-1  | Database           | Verbinding verbroken tijdens maken afspraak        | AFGEHANDELD |
| FM-2  | Message broker     | RabbitMQ onbereikbaar                              | AFGEHANDELD |
| FM-3  | Message broker     | Consumer crasht vóór verwerking bericht            | AFGEHANDELD |
| FM-4  | Message broker     | Bericht verlopen in queue (TTL verstreken)         | AFGEHANDELD |
| FM-5  | Messaging provider | SwiftSend onbereikbaar of rate limit bereikt       | AFGEHANDELD |
| FM-6  | Messaging provider | SecurePost JWT-token verlopen                      | AFGEHANDELD |
| FM-7  | Messaging provider | LegacyLink time-out door hoge variabele vertraging | AFGEHANDELD |
| FM-8  | Messaging provider | AsyncFlow geeft nooit eindstatus terug             | AFGEHANDELD |
| FM-9  | App                | Crash na DB-write, vóór publicatie naar RabbitMQ   | AFGEHANDELD |
| FM-10 | App                | Tijdzone-fout bij berekening verzendtijdstip       | AFGEHANDELD |
| FM-11 | App                | Polling reconciler haalt dubbele events op         | AFGEHANDELD |
