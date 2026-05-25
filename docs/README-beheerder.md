# Technische Beheerdershandleiding — BeunMRS Notificatiemodule

**Versie:** 1.0  
**Datum:** 2026-05-25  
**Doelgroep:** IT-beheerders en systeembeheerders die de BeunMRS notificatiemodule installeren en onderhouden.

---

## Inhoudsopgave

1. [Systeemvereisten](#1-systeemvereisten)
2. [Installatie en opstarten](#2-installatie-en-opstarten)
3. [Koppeling met OpenMRS](#3-koppeling-met-openmrs)
4. [Tenant registreren en configureren](#4-tenant-registreren-en-configureren)
5. [Berichtproviders configureren](#5-berichtproviders-configureren)
6. [TLS 1.3 in productie (Let's Encrypt)](#6-tls-13-in-productie-lets-encrypt)
7. [Logboekbeheer en Loki](#7-logboekbeheer-en-loki)
8. [HL7-equivalent: berichtontvangstbevestiging](#8-hl7-equivalent-berichtontvangstbevestiging)
9. [UTF-8 tekenset](#9-utf-8-tekenset)
10. [Grafana monitoring dashboard](#10-grafana-monitoring-dashboard)
11. [Uitbreidbaarheid: nieuwe provider toevoegen](#11-uitbreidbaarheid-nieuwe-provider-toevoegen)
12. [Beveiliging samenvatting](#12-beveiliging-samenvatting)
13. [Beheerders-API](#13-beheerders-api)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. Systeemvereisten

| Vereiste | Minimaal |
|----------|----------|
| Docker Desktop | 4.x of hoger |
| Docker Compose | v2 (`docker compose`, niet `docker-compose`) |
| RAM | 6 GB (8 GB aanbevolen bij volledige stack met OpenMRS) |
| Vrije schijfruimte | 10 GB |
| Vrije poorten | 80, 1337, 3000, 3001, 3100, 4000, 5433, 9090, 15672 |
| OS | Windows 10/11, macOS 12+, of Ubuntu 22.04+ |

---

## 2. Installatie en opstarten

### Eerste installatie

```powershell
# Stap 1: Repository klonen
git clone <repository-url>
cd BeunMRS

# Stap 2: Omgevingsvariabelen instellen
cp .env.example .env
# Bewerk .env met productiewaarden (zie sectie 2.1)

# Stap 3: Volledige stack starten
docker compose up -d

# Stap 4: Controleer of alles draait
docker compose ps
```

### 2.1 Verplichte omgevingsvariabelen voor productie

Open `.env` en stel de volgende waarden in vóór de eerste start:

```bash
# ── Encryptie (VERPLICHT te wijzigen in productie) ────────────────
DB_ENCRYPTION_KEY=<base64-encoded 32 bytes>   # AES-256 sleutel
# Genereer met: openssl rand -base64 32

# ── Admin API-sleutel ──────────────────────────────────────────────
SAAS_ADMIN_KEY=<sterk willekeurig wachtwoord>
# Genereer met: openssl rand -hex 32

# ── Databasewachtwoorden ───────────────────────────────────────────
NOTIFICATION_DB_PASSWORD=<sterk wachtwoord>
OPENMRS_DB_PASSWORD=<sterk wachtwoord>
OPENMRS_DB_ROOT_PASSWORD=<sterk wachtwoord>
RABBITMQ_PASSWORD=<sterk wachtwoord>
GRAFANA_PASSWORD=<sterk wachtwoord>
```

> ⚠️ **Nooit de standaardwachtwoorden uit `.env.example` gebruiken in productie.**  
> Het `.env` bestand staat in `.gitignore` en wordt nooit ingecheckt.

### 2.2 Herstarten en rebuilden

```powershell
# Alleen notificatieservice herbouwen na codewijziging
docker compose build notification-svc
docker compose up -d notification-svc

# Volledige schone herstart (wist alle data!)
docker compose down -v
docker compose up -d

# Logs volgen
docker compose logs -f notification-svc
```

### 2.3 Stack zonder OpenMRS (snelle start)

```powershell
docker compose -f docker-compose.noopenmrs.yml up -d
```

Gebruik dit wanneer OpenMRS al elders draait en je alleen de notificatieservice wilt starten.

---

## 3. Koppeling met OpenMRS

### Integratiemethode

BeunMRS gebruikt **REST v1 polling** om afspraken op te halen:

```
POST /ws/rest/v1/appointment/search
Authorization: Basic <base64(user:password)>
Content-Type: application/json

{
  "startDate": "<watermark-datum>",
  "endDate":   "<watermark + 30 dagen>"
}
```

- **Interval:** elke 2 minuten
- **Venster:** 30 dagen vooruit per poll-cyclus
- **Cursor:** `sync_watermarks` tabel — per tenant, per resource-type
- **Compatibiliteit:** OpenMRS REST v1 API (stabiel sinds OpenMRS 2.x)

> **Waarom niet FHIR2?** De FHIR2-module geeft fout `HAPI-0302: Unknown resource type 'Appointment'` — Appointment is niet ondersteund in de standaard FHIR2-module. Zie ADR-003.

### OpenMRS-gegevens per tenant configureren

Bij tenant-registratie stel je het volgende in:

| Veld | Beschrijving | Voorbeeld |
|------|-------------|---------|
| `openmrsHost` | Volledig adres van OpenMRS | `http://gateway/openmrs` |
| `openmrsUser` | Gebruiker met rechten op `/ws/rest/v1/appointment/search` | `admin` |
| `openmrsPassword` | Wachtwoord (wordt AES-256 versleuteld opgeslagen) | `Admin1234` |

### Vereiste OpenMRS-rechten

De gebruiker die wordt ingesteld als `openmrsUser` heeft minimaal nodig:
- Toegang tot `GET /ws/rest/v1/appointment/search`
- Toegang tot `GET /ws/rest/v1/appointment/{uuid}` (voor `comments` ophalen)
- Toegang tot `GET /ws/rest/v1/patient/{uuid}/person` (voor contactgegevens)

### Comments-workaround

De `POST /appointment/search` API retourneert altijd `"comments": null`. De service doet daarom automatisch een extra aanroep:

```
GET /ws/rest/v1/appointment?uuid={uuid}&v=full
```

Dit is ingebouwd in `OpenMrsAppointmentPoller.enrichComments()` en vereist geen configuratie.

---

## 4. Tenant registreren en configureren

### 4.1 Registratie via het portaal

1. Ga naar **https://localhost:3001** (of het productie-URL)
2. Accepteer de TLS-waarschuwing bij gebruik van een self-signed certificaat
3. Vul het formulier in:
   - **Slug:** unieke identifier voor de organisatie (bijv. `amc`, `vumc`)
   - **Weergavenaam:** naam van het ziekenhuis
   - **OpenMRS host:** URL van de OpenMRS-installatie
   - **Gebruikersnaam + wachtwoord:** OpenMRS-beheeraccount
   - **Provider:** SMS-provider keuze
   - **Provider API-sleutel:** sleutel van de gekozen provider
   - **Tijdzone:** IANA tijdzone (bijv. `Europe/Amsterdam`)
4. Klik op **Registreren**
5. ⚠️ **Sla de gegenereerde API-sleutel direct op** — deze wordt nooit opnieuw getoond

### 4.2 Registratie via de API

```http
POST https://localhost:4000/api/register
Content-Type: application/json

{
  "slug":            "amc",
  "displayName":     "Amsterdam UMC",
  "openmrsHost":     "http://gateway/openmrs",
  "openmrsUser":     "admin",
  "openmrsPassword": "Admin1234",
  "providerName":    "SwiftSend",
  "providerApiKey":  "swiftsend_crypto_key_abc123",
  "timezone":        "Europe/Amsterdam"
}
```

**Succesvolle response (200 OK):**

```json
{
  "tenantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "slug":     "amc",
  "apiKey":   "beunmrs_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

### 4.3 API-sleutel gebruiken

Voeg de API-sleutel toe als header bij elke aanroep naar de notificatie-API:

```http
GET https://localhost:4000/api/...
X-API-Key: beunmrs_<jouw-sleutel>
```

### 4.4 Eerste afspraak testen

Maak een testafspraak aan in OpenMRS:

```http
POST http://localhost/openmrs/ws/rest/v1/appointment
Authorization: Basic admin:Admin1234
Content-Type: application/json

{
  "patientUuid":     "4df50238-84b9-45ec-9f43-b85701234506",
  "serviceUuid":     "7ba3aa21-cc56-47ca-bb4d-a60549f666c0",
  "locationUuid":    "ba685651-ed3b-4e63-9b35-78893060758a",
  "startDateTime":   "2026-06-15T10:00:00.000Z",
  "endDateTime":     "2026-06-15T10:30:00.000Z",
  "appointmentKind": "Scheduled",
  "comments":        "Nuchter komen. Paspoort meenemen."
}
```

Controleer binnen 2 minuten (één poll-cyclus) of de reminders zijn ingepland:

```sql
SELECT id, appointment_uuid, scheduled_at, status
FROM scheduled_notifications
ORDER BY created_at DESC
LIMIT 5;
```

Je verwacht twee rijen: één voor 24 uur vóór en één voor 1 uur vóór de afspraak.

---

## 5. Berichtproviders configureren

Alle vier providers communiceren via **FakeComWorld** (mock SMS-platform) voor lokale ontwikkeling en demo's.

| Provider | Protocol | Authenticatie | Beperkingen (FakeComWorld) |
|----------|----------|---------------|--------------------------|
| **SwiftSend** | REST API | `X-API-Key` header | 20 req/min, 10% foutinjectie |
| **SecurePost** | REST API | JWT (3 min geldigheid) | Aparte token-endpoint, hoge auth-vertraging |
| **LegacyLink** | SOAP WebService | HTTP Basic Auth | Zware XML-payloads, 100–3000ms latentie |
| **AsyncFlow** | REST (polling) | `X-API-Key` header | Asynchroon — stuurt commando, polt daarna op status |

### Provider-credentials instellen

De credentials worden per provider ingesteld in `.env`:

```bash
SWIFTSEND_API_KEY=swiftsend_crypto_key_abc123
SECUREPOST_CLIENT_ID=securepost-client-id-prod
SECUREPOST_CLIENT_SECRET=securepost-client-secret-key-xyz
SECUREPOST_JWT_SECRET=securepost-jwt-secret-key-must-be-32-chars-long
LEGACYLINK_USERNAME=legacylink-soap-user
LEGACYLINK_PASSWORD=legacylink-soap-secure-password
ASYNCFLOW_API_KEY=asyncflow_token_key_7890
```

> In productie: vervang FakeComWorld door de echte provider-endpoints via de bijbehorende omgevingsvariabelen.

---

## 6. TLS 1.3 in productie (Let's Encrypt)

De huidige configuratie gebruikt **zelfondertekende certificaten** die automatisch worden gegenereerd bij de Docker-build. Voor productie moeten deze vervangen worden door certificaten van Let's Encrypt.

### Huidige configuratie (dev/demo)

- Certificaat wordt gegenereerd in `infra/nginx/Dockerfile` bij build-tijd
- NGINX (`infra/nginx/nginx.conf`) dwingt uitsluitend TLS 1.3 af:
  ```nginx
  ssl_protocols TLSv1.3;
  ```
- Security headers ingesteld: `HSTS`, `X-Content-Type-Options`, `X-Frame-Options`

### Productie: Let's Encrypt certificaat instellen

**Stap 1:** Genereer het certificaat met Certbot (eenmalig):

```bash
certbot certonly --standalone -d jouwdomein.nl
# Certificaten staan in: /etc/letsencrypt/live/jouwdomein.nl/
```

**Stap 2:** Mount het certificaat in de NGINX-container via `docker-compose.yml`:

```yaml
notification-nginx:
  build:
    context: ./infra/nginx
  volumes:
    - /etc/letsencrypt/live/jouwdomein.nl/fullchain.pem:/etc/nginx/ssl/cert.pem:ro
    - /etc/letsencrypt/live/jouwdomein.nl/privkey.pem:/etc/nginx/ssl/key.pem:ro
```

**Stap 3:** Pas `infra/nginx/nginx.conf` aan:

```nginx
server_name jouwdomein.nl;
```

**Stap 4:** Automatisch vernieuwen via cron:

```bash
0 3 * * * certbot renew --quiet && docker compose restart notification-nginx
```

### Controleren of TLS 1.3 actief is

```bash
openssl s_client -connect localhost:4000 -tls1_3
# Verwacht: "Protocol: TLSv1.3"
```

---

## 7. Logboekbeheer en Loki

### Loki-configuratie (intern netwerk)

Loki draait op het interne Docker-netwerk en is alleen bereikbaar via Grafana. De service gebruikt geen authenticatie in de huidige configuratie — dit is veilig zolang Loki niet extern bereikbaar is.

**Voor productie:** Beperk Loki-toegang via netwerksegmentatie of voeg Loki Basic Auth toe in `loki-config.yml`.

### Logs bekijken in Grafana

1. Ga naar http://localhost:3000 → **Explore**
2. Selecteer datasource: **Loki**
3. Gebruik de volgende query-formaten:

```logql
# Alle logs van de notificatieservice
{container_name="notification-svc"}

# Logs van een specifieke tenant
{container_name="notification-svc"} |= "amc"

# Foutmeldingen
{container_name="notification-svc"} |= "ERROR"

# Verstuurde notificaties
{container_name="notification-svc"} |= "status=sent"

# FakeComWorld ontvangst controleren
{container_name="fakecomworld"} |= "SwiftSend"
```

### PII-maskering in logs

Alle persoonlijke gegevens worden gemaskeerd vóór ze in logs verschijnen:

| Gegeven | Voorbeeld origineel | Gemaskeerd |
|---------|---------------------|------------|
| Telefoonnummer | `+31612345678` | `+31****678` |

Telefoongegevens worden nooit in plaintext opgeslagen in de database.

### Data-retentie

| Dataset | Bewaartermijn | Beheerder |
|---------|---------------|-----------|
| `notification_log` (met PII) | 14 dagen | `DataRetentionJob` — dagelijks 02:00 |
| `notification_audit_log` (PII-vrij) | 1 jaar | `DataRetentionJob` — dagelijks 02:00 |
| Loki-logs | Standaard: geen limiet | Stel in via `loki-config.yml` → `retention_period` |

---

## 8. HL7-equivalent: berichtontvangstbevestiging

Hoewel BeunMRS geen HL7-berichten verstuurt, implementeert het een vergelijkbaar bevestigingsmechanisme via RabbitMQ en statustracking.

### Bevestigingsflow

```
OpenMRS → (REST poll) → notification-svc → outbox_events (PERSIST)
                                         ↓
                              RabbitMQ (PUBLISH + ACK)
                                         ↓
                         notification_log.status = 'sent' / 'failed'
```

1. **Persist-before-publish:** het event wordt atomisch opgeslagen in `outbox_events` vóór publicatie op RabbitMQ. Bij een crash wordt het opnieuw opgepakt.
2. **RabbitMQ acknowledgement:** de consumer bevestigt (`ack`) verwerking pas nadat het event is opgeslagen. Bij een crash wordt het bericht opnieuw aangeboden (durable queues).
3. **Status-trail in `notification_log`:** elke notificatie krijgt een status (`scheduled`, `sent`, `failed`, `skipped`, `cancelled`, `permanently_failed`). Dit vormt de audit trail.

### Status opvragen

```sql
-- Laatste 20 verzendpogingen voor een tenant
SELECT appointment_uuid, provider_name, status, retry_count, created_at
FROM notification_log
WHERE tenant_id = '<tenant-uuid>'
ORDER BY created_at DESC
LIMIT 20;
```

### Dead Letter Exchange (DLX)

Berichten die niet verwerkt kunnen worden, gaan naar de DLX (`openmrs.events.dlx`). Deze zijn inzichtelijk via de RabbitMQ-beheerinterface op http://localhost:15672.

---

## 9. UTF-8 tekenset

Het systeem ondersteunt UTF-8 end-to-end voor internationale patiëntgegevens:

| Laag | UTF-8 instelling |
|------|-----------------|
| PostgreSQL | `ENCODING = 'UTF8'` (standaard in Docker image) |
| Spring Boot | `CharacterEncodingFilter` — UTF-8 voor alle requests/responses |
| JSON | Jackson `ObjectMapper` — UTF-8 standaard |
| RabbitMQ | Berichten als `byte[]` met UTF-8 encoding |
| NGINX | Geen transformatie — pass-through |

### Testbericht met niet-Latijnse karakters

```http
POST https://localhost:4000/api/register
Content-Type: application/json; charset=UTF-8

{
  "slug": "test-utf8",
  "displayName": "مستشفى الأمل",
  "comments": "يرجى الحضور مبكراً"
}
```

Dit wordt correct verwerkt en opgeslagen zonder tekenverlies.

---

## 10. Grafana monitoring dashboard

### Toegang

- URL: http://localhost:3000
- Gebruikersnaam: `admin` (of waarde van `GRAFANA_USER` in `.env`)
- Wachtwoord: waarde van `GRAFANA_PASSWORD` in `.env`

### Beschikbare dashboards

Na het starten van de stack zijn de dashboards automatisch beschikbaar via Grafana provisioning (`infra/grafana/provisioning/`):

| Dashboard | Inhoud |
|-----------|--------|
| **BeunMRS Operational** | Berichtdoorvoer (msg/min), provider latentie, foutpercentage |
| **Loki Logs** | Live applicatielogs via Explore |

### Nuttige Prometheus-metrics

De notificatieservice exporteert metrics via Spring Boot Actuator (Micrometer):

```
# Doorvoer
http_server_requests_seconds_count{uri="/api/register"}
http_server_requests_seconds_count{uri="/actuator/health"}

# JVM
jvm_memory_used_bytes
jvm_threads_live_threads

# Database
hikaricp_connections_active
```

Bereikbaar op: http://localhost:9090 (Prometheus UI)

### Aanbevolen alerts instellen

In Grafana → Alerting → Alert rules:

| Alert | Conditie | Ernst |
|-------|----------|-------|
| Hoog foutpercentage | `status=failed` > 10/min | 🔴 Kritiek |
| Circuit breaker open | Log bevat "circuit open" | 🟠 Waarschuwing |
| Service onbereikbaar | Health check faalt | 🔴 Kritiek |
| RabbitMQ DLX berichten | DLQ queue groeit | 🟠 Waarschuwing |

---

## 11. Uitbreidbaarheid: nieuwe provider toevoegen

Het systeem is ontworpen zodat een nieuwe SMS-provider toevoegen minimale codewijziging vereist.

### Stap-voor-stap

**Stap 1:** Maak een nieuwe klasse die `NotificationProvider` implementeert:

```java
package com.openmrs.notification.adapter;

import com.openmrs.notification.model.AppointmentEvent;
import com.openmrs.notification.model.NotificationResult;
import com.openmrs.notification.model.ProviderCredentials;
import com.openmrs.notification.service.NotificationProvider;
import org.springframework.stereotype.Component;

@Component
public class NieuweProviderAdapter implements NotificationProvider {

    @Override
    public String getProviderName() {
        return "NieuweProvider";  // Moet overeenkomen met providerName in registratie
    }

    @Override
    public NotificationResult send(AppointmentEvent event, ProviderCredentials credentials) {
        // Implementeer hier de verzendlogica
        // credentials.apiKey() bevat de ontsleutelde API-sleutel
        // event.patientPhone() bevat het telefoonnummer
        return NotificationResult.success();
    }
}
```

**Stap 2:** Voeg de provider toe aan de whitelist in `TenantRegistrationController.java`:

```java
private static final Set<String> VALID_PROVIDERS =
    Set.of("SwiftSend", "SecurePost", "LegacyLink", "AsyncFlow", "NieuweProvider");
```

**Stap 3:** Voeg de CHECK constraint toe aan het databaseschema:

```sql
ALTER TABLE tenants DROP CONSTRAINT tenants_provider_name_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_provider_name_check
    CHECK (provider_name IN ('SwiftSend','SecurePost','LegacyLink','AsyncFlow','NieuweProvider'));
```

> ⚠️ Schema-wijziging vereist: `docker compose down -v && docker compose up -d`

**Stap 4:** Rebuild de service:

```powershell
docker compose build notification-svc
docker compose up -d notification-svc
```

Geen andere klassen hoeven gewijzigd te worden — `NotificationDispatcher` detecteert de nieuwe provider automatisch via Spring's dependency injection.

### Uitbreidbare RabbitMQ routing keys

Nieuwe notificatietypen (bijv. laboratoriumresultaten) kunnen worden toegevoegd zonder de huidige code te wijzigen:

```java
// Nieuwe routing key: "labresult.ready"
// Nieuwe queue binden aan openmrs.events exchange
// Nieuwe consumer klasse maken die AppointmentEventConsumer volgt
```

---

## 12. Beveiliging samenvatting

| Maatregel | Implementatie |
|-----------|---------------|
| **Transport** | TLS 1.3 via NGINX (`ssl_protocols TLSv1.3`) |
| **Versleuteling in rust** | AES-256-GCM voor alle credentials in database (`AesEncryptionService`) |
| **API-authenticatie** | SHA-256 hash lookup via `X-API-Key` header |
| **Beheerder-authenticatie** | `X-Admin-Key` header voor `/api/admin/**` endpoints |
| **Multi-tenant isolatie** | `TenantContext` (ThreadLocal) + alle queries gefilterd op `tenant_id` |
| **SQL injection** | JdbcTemplate parameterized queries — geen string concatenatie |
| **XSS** | React JSX auto-escaping + geen `dangerouslySetInnerHTML` |
| **Informatie-onthulling** | Generic foutmeldingen naar client, details alleen in server-logs |
| **PII-maskering** | Telefoon gemaskeerd in logs (`+31****678`) |
| **Input-validatie** | Slug regex, provider whitelist, IANA timezone validatie |
| **Security headers** | `HSTS`, `X-Content-Type-Options`, `X-Frame-Options` via NGINX |
| **Secrets beheer** | `.env` bestand (nooit ingecheckt) + `DB_ENCRYPTION_KEY` env var |

### Productie-checklist beveiliging

- [ ] `DB_ENCRYPTION_KEY` ingesteld (niet de dev-fallback)
- [ ] `SAAS_ADMIN_KEY` ingesteld (niet `admin-secret`)
- [ ] Alle databasewachtwoorden gewijzigd
- [ ] Self-signed certificaat vervangen door Let's Encrypt (zie sectie 6)
- [ ] Loki-poort (3100) niet extern bereikbaar
- [ ] PostgreSQL-poort (5433) niet extern bereikbaar
- [ ] RabbitMQ-beheer (15672) beperkt tot intern netwerk

---

## 13. Beheerders-API

Alle admin-endpoints vereisen de `X-Admin-Key` header.

### Alle tenants opvragen

```http
GET https://localhost:4000/api/admin/tenants
X-Admin-Key: <SAAS_ADMIN_KEY>
```

**Response:**
```json
[
  {
    "id":           "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "slug":         "amc",
    "displayName":  "Amsterdam UMC",
    "providerName": "SwiftSend",
    "openmrsHost":  "http://gateway/openmrs",
    "active":       true,
    "createdAt":    "2026-05-25T10:00:00Z"
  }
]
```

### Tenant deactiveren

```http
DELETE https://localhost:4000/api/admin/tenants/{tenant-uuid}
X-Admin-Key: <SAAS_ADMIN_KEY>
```

Een gedeactiveerde tenant ontvangt geen polls meer en kan niet inloggen. Data blijft bewaard.

### Health check

```http
GET https://localhost:4000/actuator/health
```

**Response:**
```json
{
  "status": "UP",
  "components": {
    "db":       { "status": "UP" },
    "rabbit":   { "status": "UP" },
    "diskSpace":{ "status": "UP" }
  }
}
```

---

## 14. Troubleshooting

### OpenMRS start niet op

```powershell
docker compose logs -f backend
# Wacht op: "Server startup in X ms"
# Normaal 5-10 minuten — dit is verwacht
```

### Notificaties worden niet verstuurd

```powershell
# Controleer of de poller werkt
docker compose logs notification-svc | Select-String "poller"

# Controleer scheduled_notifications
# Verbind met database:
docker exec -it notification-db psql -U notify -d notifications -c \
  "SELECT status, COUNT(*) FROM scheduled_notifications GROUP BY status;"
```

### "slug already in use" bij registratie

De slug is al bezet. Kies een andere slug of deactiveer de bestaande tenant via de beheerders-API.

### Database is leeg na herstart

```powershell
# Schema is niet automatisch bijgewerkt bij wijziging
# Volledige reset (wist alle data!):
docker compose down -v
docker compose up -d
```

### TLS-certificaat fout in browser

Dit is normaal bij gebruik van self-signed certificaten. Klik op "Geavanceerd → Toch doorgaan" of voeg het certificaat toe als vertrouwd in je browser.

### Circuit breaker open

```powershell
docker compose logs notification-svc | Select-String "circuit"
# Bij "circuit open": wacht 2 minuten, de circuit breaker reset automatisch
```

### RabbitMQ-berichten in DLQ

Ga naar http://localhost:15672 → Queues → `openmrs.events.dlx` om de berichten te inspecteren. Berichten in de DLQ kunnen handmatig opnieuw worden aangeboden.
