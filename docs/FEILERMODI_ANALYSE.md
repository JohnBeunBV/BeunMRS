# Analyse Foutmodi versus Afhandeling in de Notification Service

## Inleiding

Dit document vergelijkt de bekende foutmodi (failure modes) uit de risicoanalyse met de **feitelijke afhandeling** in de broncode van de notification service. Per foutmodus wordt beschreven:
1. De foutmodus zelf
2. Of en hoe deze wordt afgehandeld
3. De exacte broncodeplaatsen

---

## FM-1: Notificatie Storm / Dubbele Dispatch

> *"Als een client of een tussengeschakelde load balancer een HTTP-request niet kan bereiken of timeout, kan het zijn dat de client de aanvraag automatisch opnieuw probeert. Hierdoor kan het gebeuren dat er per ongeluk twee identieke 'createAppointment'-events naar dit systeem worden gestuurd. Omdat de outbox geen unieke constraints bevat op appointment_uuid, worden beide events opgeslagen en beide worden verzonden als SMS. Resultaat: De patiënt krijgt twee identieke SMS'en, en het openMRS-systeem produceert dubbele data."*

### Huidige afhandeling

**NIET AFGEHANDELD**

De code controleert nergels op duplicaten:

- **OutboxService.java:129** — De `INSERT`-query heeft geen `ON CONFLICT` of `WHERE NOT EXISTS`:
  ```sql
  INSERT INTO notification_log (tenant_id, patient_uuid, channel, event_type,
  ```

- **NotificationDispatcher.java:131-133** — Geen controle op al bestaande logs voor dit `appointment_uuid`:
  ```java
  String result = jdbc.queryForObject(
      "SELECT appointment_uuid FROM notification_log WHERE ...",
  ```

- Er is geen unieke constraint op `notification_log.appointment_uuid` in de database.

- Er is geen idempotentie-key of correlation ID in het request payload.

---

## FM-2: Ongeldige ontvanger (null telefoonnummer)

> *"Als het telefoonnummer van de patiënt null is in openMRS, wordt de SMS gestuurd naar het telefoonnummer 'unknown'. Dit resulteert in een foute SMS naar een onbekend nummer."*

### Huidige afhandeling

**DEELLIJK AFGEHANDELD**

- **MessageHelper.java:74-76** — Null telefoonnummers worden gemaskeerd:
  ```java
  public static String maskPhone(String phone) {
      if (phone == null || phone.isEmpty()) return "unknown";
  ```
  Dit produceert het letterlijke string `"unknown"` als gemanipuleerd telefoonnummer.

- **OutboxService.java:118** — `maskPhone` wordt aangeroepen vóór de INSERT. Het resultaat `"unknown"` wordt opgeslagen in de payload.

- **NotificationDispatcher.java:131** — Contact info wordt opgehaald:
  ```java
  String phone = fetchContactFromOpenMRS(event, channel, false);
  ```
  Geen validatie dat het nummer niet-lege, gefomatde inhoud heeft.

- **MockMessagingProvider.java:47** — De mock provider valideert niet of het nummer geldig is:
  ```java
  return NotificationResult.ok("mock-msg-id-" + System.currentTimeMillis());
  ```

- **SwiftSendProvider.java:50** — De SwiftSend provider valideert niet of het nummer geldig is:
  ```java
  return NotificationResult.ok(UUID.randomUUID().toString());
  ```

**Conclusie:** Een null telefoonnummer leidt tot het opslaan van `"unknown"` in de log en een dispatch naar een ongeldig nummer. De providers gooien geen exception, maar sturen de boodschap wel naar een ongeldig adres.

---

## FM-3: Schending van tenant-isolatie

> *"De API-key van tenant A zou kunnen worden gebruikt om notificaties voor tenant B te versturen als de thread-local van de tenant niet correct wordt opgeschoond, of als een provider-anroep asynchroon wordt uitgevoerd."*

### Huidige afhandeling

**AFGEHANDELD**

- **TenantApiKeyFilter.java:33-48** — De filter stelt de `TenantContext` en **verwijdert** deze in de `finally`-block:
  ```java
  try {
      TenantContext.set(tenant);
      filterChain.doFilter(request, response);
  } finally {
      TenantContext.clear();
  }
  ```

- **NotificationDispatcher.java:88-94** — De dispatcher gebruikt `TenantContext` niet zelf (tenant-info wordt als parameter meegegeven).

- **FailedNotificationRetryJob.java:120-170** — De retry-job stelt de `TenantContext` per record in en verwijdert deze in de `finally`:
  ```java
  TenantContext.set(tenant);
  try {
      // ... dispatch logica ...
  } catch (Exception ex) {
      // ...
  } finally {
      TenantContext.clear();
  }
  ```

- **TenantService.java:133** — De `decryptProviderApiKey` methode gebruikt de **explicit tenant parameter**, niet de thread-local:
  ```java
  public String decryptProviderApiKey(Tenant tenant) {
      byte[] key = aesDecrypt(encryptTenantKey(tenant.getEncryptedAesKey()), AES_KEY);
  ```

- **Provider-credenctial flow**: Elke provider send krijgt de credentials **expliciet mee** via de `ProviderCredentials` parameter — er wordt nergels een statische/ thread-local key gebruikt tijdens het versturen.

**Conclusie:** De thread-local wordt correct opgeruimd in alle bekende code-paden. De provider-credenctial flow gebruikt expliciete parameters, geen globale state.

---

## FM-4: AESCrypto key compromised

> *"De AES-encryption key is opgeslagen in de database. Als de database wordt gehackt en de key wordt gestolen, kunnen alle tenant-data worden gedecodeerd. Als de AES-key dezelfde is als de application key in secrets, dan is een inbreuk op de applicatie een inbreuk op alle tenant-data."*

### Huidige afhandeling

**NIET AFGEHANDELD**

- **Tenant.java:33** — De `encryptedAesKey` staat als `@Column` in de entity, direct in de database:
  ```java
  @Column(name = "encrypted_aes_key", columnDefinition = "BYTEA")
  private byte[] encryptedAesKey;
  ```

- **TenantService.java:133-134** — De AES decryptie gebruikt een vaste `AES_KEY`:
  ```java
  private static final byte[] AES_KEY =
      Base64.getDecoder().decode("aW1BTFZpYWRXcUhpS0J0TFVtVjRkSGM0Q21R");
  ```
  Deze is hard-coded in de broncode.

- **TenantService.java:146-148** — De encryptie van provider API keys met de per-tenant AES key:
  ```java
  public ProviderCredentials encryptProviderApiKey(Tenant tenant, String rawApiKey) {
      byte[] encrypted = aesEncrypt(rawApiKey.getBytes(), tenantKey);
  ```
  Zonder deze per-tenant AES key zijn de provider credentials onleesbaar.

- Er is **geen separation of concerns** tussen de applicatie-secret en de AES-master-key.

- Er is **geen HSM, KMS, of environment-based key storage** — de AES_KEY is hard-coded.

**Conclusie:** Als de database én de source code (of build-artefact) worden gecompromitteerd, kunnen alle tenant AES keys worden gedecodeerd, en daarmee alle provider credentials.

---

## FM-5: PII gegevens bewaard zonder toestemming

> *"Telefoonnummers en e-mailadressen van patiënten worden opgeslagen in het notification_log zonder expliciete toestemming van de patiënt (geen opt-in record). Dit is in strijd met AVG/GDPR als er geen wettelijke grond is voor het verwerken van deze gegevens."*

### Huidige afhandeling

**DEELLIJK AFGEHANDELD**

- **MessageHelper.java:74-76** — Telefoonnummers worden gemaskeerd:
  ```java
  public static String maskPhone(String phone) {
      if (phone == null || phone.isEmpty()) return "unknown";
      if (phone.length() < 4) return "***";
      return phone.substring(0, phone.length() - 4) + "****";
  }
  ```

- **MessageHelper.java:58-60** — E-mailadressen worden gemaskeerd:
  ```java
  public static String maskEmail(String email) {
      if (email == null || email.isEmpty()) return "unknown";
      return email.replaceAll("(.{2}).*(@.*)", "$1**$2");
  }
  ```

- **OutboxService.java:118-119** — Masking wordt toegepast vóór de INSERT:
  ```java
  String maskedPhone = MessageHelper.maskPhone(phone);
  String maskedEmail = MessageHelper.maskEmail(email);
  ```

- De **volledig gemaskeerde waarden** zijn nog steeds terug te halen uit de database.
  - `"0612345678"` → `"0612345678****"` — de prefix is nog leesbaar.
  - `"jan@voorbeeld.nl"` → `"ja**@voorbeeld.nl"` — de domein-naam is nog leesbaar.

- Er is **geen opt-in field**, **geen consent record**, en **geen legal basis tracking** in de notification_log tabel.

**Conclusie:** De masking beperkt de risico's, maar de gegevens zijn nog steeds gedeeltelijk re-identificeerbaar. Er is geen mechanisme voor consent management.

---

## FM-6: Provider downtime / Network failure

> *"Als de communicatieprovider (bijv. SwiftSend) down is of een netwerkfout treedt op, worden notificaties永久ly gemist."*

### Huidige afhandeling

**AFGEHANDELD**

- **FailedNotificationRetryJob.java:77-90** — Exponential backoff retry-mechanisme:
  ```java
  @Scheduled(fixedDelayString = "${retry.failed.interval-ms:60000}",
             initialDelayString = "${retry.failed.initial-delay-ms:60000}")
  public void retryFailed() {
      List<Map<String, Object>> rows = jdbc.queryForList(
          "SELECT ... FROM notification_log WHERE status = 'failed' AND retry_count < ? ...",
          MAX_RETRIES, BATCH_SIZE);
  ```

- **FailedNotificationRetryJob.java:175-190** — Backoff scheduling:
  ```java
  private void handleFailedAttempt(String id, int currentRetryCount, String errorMessage) {
      int newCount = currentRetryCount + 1;
      if (newCount >= MAX_RETRIES) {
          markPermanentlyFailed(id, errorMessage);
      } else {
          long backoffMin = BACKOFF_MINUTES[newCount - 1]; // 1→5 min, 2→15 min
          jdbc.update("UPDATE notification_log SET retry_count = ?, next_retry_at = now() + ? * interval '1 minute' ...",
  ```

- **FailedNotificationRetryJob.java:193-201** — Na 3 pogingen: `permanently_failed` status.

- **NotificationDispatcher.java:117** — Foutmelding wordt opgeslagen in `error_message`:
  ```java
  jdbc.update("UPDATE notification_log SET status = 'failed', error_message = ?, ...",
  ```

- **MockMessagingProvider.java:28-30** — Configurable mock failures voor testing:
  ```java
  if (isFailureScenario) {
      return NotificationResult.failure("Simulated mock failure");
  }
  ```

**Conclusie:** Provider downtime wordt correct afgehandeld met retry (3 pogingen, exponential backoff). Na 3 mislukkingen gaat de status op `permanently_failed`. Er is echter **geen dead-letter queue of alerting** voor permanently failed notificaties.

---

## FM-7: Database unavailable during write

> *"Als de database tijdens het opslaan van een event down is, gaat het event permanent verloren en wordt er geen notificatie verstuurd."*

### Huidige afhandeling

**NIET AFGEHANDELD**

- **OutboxService.java:127-129** — De INSERT faalt met een exception zonder retry of fallback:
  ```java
  } catch (Exception e) {
      log.error("[OutboxService] Error storing appointment event: {}", maskedPhone);
      return maskedPhone;
  }
  ```

- Er is **geen out-of-band persistentie** (bijv. een message queue zoals RabbitMQ/Kafka) als fallback.

- De `return maskedPhone;` na de catch is alleen voor de method signature — het event is al verloren.

- **NotificationDispatcher.java:145** — De dispatch wordt pas uitgevoerd na het opslaan. Als de INSERT faalt, wordt de dispatch nooit bereikt.

**Conclusie:** Een database-fout tijdens het opslaan van een event resulteert in永久ly data loss. Geen retry, geen fallback, geen recovery.

---

## FM-8: JSON payload corruptie of parsing errors

> *"Als de JSON payload in notification_log corrupt is of niet kan worden geparsed, kan een retry niet worden uitgevoerd."*

### Huidige afhandeling

**AFGEHANDELD**

- **FailedNotificationRetryJob.java:211-240** — De `reconstructEvent` methode vangt parsing errors:
  ```java
  private AppointmentEvent reconstructEvent(String payloadJson, String eventTypeStr,
                                             UUID tenantId, String timezone) {
      try {
          @SuppressWarnings("unchecked")
          Map<String, Object> p = objectMapper.readValue(payloadJson, Map.class);
          // ... reconstruct event ...
      } catch (Exception ex) {
          log.error("[RetryJob] Kon payload niet parsen: {}", ex.getMessage());
          return null;
      }
  }
  ```

- **FailedNotificationRetryJob.java:124-126** — Null return wordt afgehandeld:
  ```java
  if (event == null) {
      markPermanentlyFailed(id, "Payload kon niet worden geparsed");
      return;
  }
  ```

**Conclusie:** Corrupte payloads worden opgevangen en de status gaat op `permanently_failed` met een duidelijke reden. Dit is correct afgehandeld.

---

## Samenvatting

| # | Foutmodus | Status |
|---|-----------|--------|
| FM-1 | Dubbele dispatch / notification storm | NIET AFGEHANDELD |
| FM-2 | Ongeldige ontvanger (null telefoonnummer) | DEELLIJK AFGEHANDELD |
| FM-3 | Schending tenant-isolatie | AFGEHANDELD |
| FM-4 | AES key compromise | NIET AFGEHANDELD |
| FM-5 | PII zonder toestemming | DEELLIJK AFGEHANDELD |
| FM-6 | Provider downtime / network failure | AFGEHANDELD |
| FM-7 | Database unavailable during write | NIET AFGEHANDELD |
| FM-8 | JSON payload corruptie | AFGEHANDELD |

### Niet afgehandelde foutmodi (prioriteit op risico)

1. **FM-1** — Dubbele dispatch: geen idempotentie of unique constraint
2. **FM-4** — AES key compromise: harde kodering van de master key
3. **FM-7** — Database unavailable: geen fallback-mechanisme
