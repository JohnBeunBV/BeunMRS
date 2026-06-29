# Demo-runbook — BeunMRS live demonstratie (blok 6)

> Concreet, klikbaar script voor de **verplichte live demo**. Alles draait op de lokale Docker-stack. Demo-tenant = **SwiftSend**. De OpenMRS-requests staan volledig in [`docs/Info/postmanrequests.md`](../Info/postmanrequests.md); dit runbook voegt de **BeunMRS-kant** toe (tenant registreren + de live SQL-queries) en zet alles in de juiste volgorde.

---

## ⏱️ Belangrijke timing — lees dit eerst

De `OpenMrsAppointmentPoller` draait **elke 2 minuten**. Dat betekent:
- Na het aanmaken van een afspraak duurt het **tot ~2 min** voordat de reminders in `scheduled_notifications` staan.
- Na het annuleren duurt het **tot ~2 min** voordat de reminders op `cancelled` staan.

**Demo-truc (sterk aanbevolen):** maak de afspraak **vóór** je aan blok 6 begint (tijdens blok 5 / realisatie), zodat de reminders al `pending` zijn als je ze toont. De **annulering** doe je dan live, en je vult de ~2 min wachttijd met de architectuuruitleg (poller → consumer → `cancelReminders()`). Houd een **tweede, vooraf-geannuleerde** afspraak achter de hand als bewijs als je niet wilt wachten.

---

## 0. Pre-flight (vóór de presentatie)

```powershell
# Volledige stack starten
docker compose up -d

# Wachten tot OpenMRS volledig op is (5-10 min) — wacht op "Server startup"
docker compose logs -f backend | Select-String "Server startup"
```

Open vooraf in browser-tabs:
- Registratieportaal: **https://localhost:3001** (self-signed → waarschuwing accepteren)
- Grafana: **http://localhost:3000/d/beunmrs-perf** (admin / grafana_secret)
- RabbitMQ UI: **http://localhost:15672** (rabbit / rabbit_secret)

Zet een terminal klaar voor de `psql`-queries (zie §3).

---

## 1. Tenant registreren — SwiftSend (~30 sec)

**Optie A — via het portaal (visueel, aanbevolen voor demo):**
Ga naar **https://localhost:3001** en vul in: slug `amc`, naam `Amsterdam UMC`, OpenMRS-host `http://gateway/openmrs`, user `admin`, wachtwoord `Admin1234`, provider **SwiftSend**, provider-API-key `sk-swiftsend-demo`, tijdzone `Europe/Amsterdam`. → **Kopieer de `apiKey` uit de response.**

**Optie B — via curl (fallback):**
```powershell
curl.exe -k -X POST https://localhost:4000/api/register `
  -H "Content-Type: application/json" `
  -d '{ \"slug\":\"amc\", \"displayName\":\"Amsterdam UMC\", \"openmrsHost\":\"http://gateway/openmrs\", \"openmrsUser\":\"admin\", \"openmrsPassword\":\"Admin1234\", \"providerName\":\"SwiftSend\", \"providerApiKey\":\"sk-swiftsend-demo\", \"timezone\":\"Europe/Amsterdam\" }'
```
Response bevat `apiKey` (eenmalig getoond — bewaar 'm).

---

## 2. Patiënt + afspraak aanmaken (~1 min)

Volg [`postmanrequests.md`](../Info/postmanrequests.md):
- **STAP 0c** → geldig OpenMRS-ID genereren
- **STAP 1** → patiënt aanmaken → kopieer `patientUuid`
- **STAP 4** → afspraak aanmaken → kopieer `appointmentUuid`

> **Zet `startDateTime` ~2 dagen in de toekomst** zodat beide reminders (24h + 1h) `pending` blijven en niet meteen vervallen. Voorbeeld: `"startDateTime": "2026-07-03T10:00:00.000Z"`.

> ⏳ Wacht één poller-cyclus (~2 min) — of doe stap 2 al tijdens blok 5.

---

## 3. Reminders tonen — `pending` (~1 min)

```powershell
docker exec -it notification-db psql -U notify -d notifications -c "SELECT sn.type, sn.status, sn.send_at FROM scheduled_notifications sn JOIN tenants t ON t.id = sn.tenant_id WHERE t.slug = 'amc' ORDER BY sn.send_at;"
```

**Verwacht:** twee rijen — `24h` en `1h`, beide `status = pending`.

Toon ook het notification_log (bevestiging dat de SCHEDULED-notificatie verstuurd is):
```powershell
docker exec -it notification-db psql -U notify -d notifications -c "SELECT channel, event_type, status, retry_count, created_at FROM notification_log nl JOIN tenants t ON t.id = nl.tenant_id WHERE t.slug = 'amc' ORDER BY created_at DESC LIMIT 5;"
```

---

## 4. Afspraak annuleren → reminders stoppen (FR-1g) (~2 min)

Annuleer via [`postmanrequests.md`](../Info/postmanrequests.md) **STAP 8** (`changeStatus` → `Cancelled`).

> ⏳ Wacht één poller-cyclus (~2 min). Vul de tijd met de uitleg: poller pikt de `Cancelled`-status op → `AppointmentEventConsumer` → `cancelReminders()` scoped op `(appointment_uuid, tenant_id)`.

Toon daarna opnieuw:
```powershell
docker exec -it notification-db psql -U notify -d notifications -c "SELECT sn.type, sn.status FROM scheduled_notifications sn JOIN tenants t ON t.id = sn.tenant_id WHERE t.slug = 'amc' AND sn.appointment_uuid = '<APPOINTMENT_UUID>';"
```

**Verwacht:** beide rijen `status = cancelled`. → **Bewijs FR-1g.**

---

## 5. Grafana live tijdens loadtest (~2 min)

Open **http://localhost:3000/d/beunmrs-perf** en start in een terminal:
```powershell
powershell -ExecutionPolicy Bypass -File scripts\loadtest.ps1 -Scenario stress
```

Toon **live** in het dashboard: notificaties/min per provider, provider-latency (p50/p95/p99), foutpercentage, pending reminders, outbox-pending, retry-uitkomsten.

> Dit dekt NFR-9a (monitoring) en de schaalbaarheidsclaim (piek **166 notif/sec**, zie PERFORMANCE-RAPPORT.md).

---

## 6. (Optioneel) Tweede tenant — andere provider (~1 min)

Registreer een tweede tenant met **SecurePost** (slug `umcg`, provider `SecurePost`) en maak één afspraak aan. Toon in het `notification_log` dat tenant `umcg` via SecurePost gaat en `amc` via SwiftSend — **bewijs FR-3 + multi-tenant isolatie (NFR-1)**:
```powershell
docker exec -it notification-db psql -U notify -d notifications -c "SELECT t.slug, nl.channel, nl.status, count(*) FROM notification_log nl JOIN tenants t ON t.id = nl.tenant_id GROUP BY t.slug, nl.channel, nl.status ORDER BY t.slug;"
```

---

## Fallback-assets (maken vóór de presentatie — onderdeel C)

Neem op / screenshot, voor het geval de live demo hapert:
- [ ] Geslaagde flow stap 3 (reminders `pending`) en stap 4 (reminders `cancelled`)
- [ ] Grafana-dashboard onder load (stap 5)
- [ ] Chaos-run (`scripts/circuitbreaker-test.ps1`) — draait 15-20 min, dus **vooraf** opnemen
