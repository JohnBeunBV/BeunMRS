# Demo-runbook — BeunMRS live demonstratie (blok 6)

> **Visueel script voor de verplichte live demo.** Alles gebeurt in vier vensters — geen Postman of terminal nodig. De OpenMRS SPA voor patiënt + afspraak, het registratieportaal voor de tenant, DBeaver om de herinneringen te tonen, en Grafana voor monitoring. (Liever toch via commando's? Zie de **fallback-appendix** onderaan.)

---

## ⏱️ Belangrijke timing — lees dit eerst

De `OpenMrsAppointmentPoller` draait **elke 2 minuten**. Dus:
- Na het aanmaken van een afspraak duurt het **tot ~2 min** voordat de reminders in de database staan.
- Na het annuleren duurt het **tot ~2 min** voordat de reminders op `cancelled` staan.

**Demo-truc (sterk aanbevolen):** maak de patiënt + afspraak (stap 2–3) al **vóór** je aan blok 6 begint. Dan staan de reminders al klaar als je DBeaver opent, en hoef je niet live te wachten. De **annulering** doe je dan live, en je vult de ~2 min wachttijd met architectuuruitleg (poller → consumer → `cancelReminders()`). Houd een **tweede, al-geannuleerde** afspraak achter de hand als bewijs.

---

## 0. Opstelling — vóór de presentatie

**Stack starten** (en wachten tot OpenMRS op is, ~5–10 min):
```powershell
docker compose up -d
docker compose logs -f backend | Select-String "Server startup"
```

**Open deze vier vensters / tabs:**

| Venster | URL / verbinding | Rol |
|---|---|---|
| **OpenMRS SPA** | `http://localhost/openmrs/spa/home` (admin / Admin1234) | patiënt + afspraak aanmaken/annuleren |
| **Registratieportaal** | `https://localhost:3001` (self-signed → waarschuwing accepteren) | tenant aanmaken |
| **Grafana** | `http://localhost:3000/d/beunmrs-perf` (admin / grafana_secret) | live monitoring |
| **DBeaver** | PostgreSQL-verbinding (zie hieronder) | de reminders tonen |

**DBeaver-verbinding** (eenmalig instellen): New Connection → PostgreSQL →
Host `localhost` · Port `5433` · Database `notifications` · User `notify` · Password `notify_secure_password`.
Zet een **SQL Editor** klaar met de queries uit stap 4.

---

## 1. Tenant registreren — in het portaal *(±30 sec)*

Open **https://localhost:3001** en vul het formulier in:
- Slug: `amc` · Naam: `Amsterdam UMC`
- OpenMRS-host: `http://gateway/openmrs` · User: `admin` · Wachtwoord: `Admin1234`
- Provider: **SwiftSend** · Provider-API-key: `sk-swiftsend-demo` · Tijdzone: `Europe/Amsterdam`

→ Klik registreren; je ziet de **API-key** terugkomen.

> **Vertel hierbij:** dit is de SaaS-kant — een ziekenhuis abonneert zich op de dienst, krijgt een eigen API-sleutel en kiest één provider.

---

## 2. Patiënt aanmaken — in de OpenMRS SPA *(±1 min)*

In `spa/home` → **Register patient** (de persoon-met-plusje knop). Vul in:
- Voornaam + achternaam, geslacht, geboortedatum.
- **Belangrijk: voeg een telefoonnummer toe** bij de contactgegevens. Zonder telefoonnummer kan de SMS niet verstuurd worden en zie je later `failed` in plaats van `sent`.

→ Opslaan. (Kun je in het formulier geen telefoonveld vinden, geen ramp — de annulerings-demo werkt ook zonder; alleen het "verzonden"-bewijs mist dan.)

---

## 3. Afspraak inplannen — in de SPA *(±1 min)*

Open de patiënt → **Appointments** → nieuwe afspraak:
- Kies een **service** (bv. General Medicine) en een **datum 2–3 dagen vooruit**.
- Een datum ver genoeg vooruit zorgt dat beide herinneringen (24h + 1h) `pending` blijven en niet meteen vervallen.

→ Opslaan.

> **Vertel hierbij:** dit is exact dezelfde Appointments-module die onze poller elke 2 minuten uitleest via de REST v1 API.

> ⏳ Wacht één poller-cyclus (~2 min) — of doe stap 2–3 al tijdens een eerder blok.

---

## 4. Reminders tonen — in DBeaver *(±1 min)*

In je SQL Editor (of rechtsklik `scheduled_notifications` → **View Data**):
```sql
SELECT sn.type, sn.status, sn.send_at
FROM scheduled_notifications sn
JOIN tenants t ON t.id = sn.tenant_id
WHERE t.slug = 'amc'
ORDER BY sn.send_at;
```
**Verwacht:** twee rijen — `24h` en `1h`, beide `status = pending`. → **bewijs FR-1a/b.**

Toon ook dat de SCHEDULED-notificatie verstuurd is:
```sql
SELECT channel, event_type, status, created_at
FROM notification_log nl
JOIN tenants t ON t.id = nl.tenant_id
WHERE t.slug = 'amc'
ORDER BY created_at DESC;
```

---

## 4b. De patiënt ontvangt de 24h- én 1h-herinnering (FR-1a/b) *(±3 min)*

> Je kunt geen 24 uur wachten, dus toon het in twee delen: (1) de herinneringen zijn op de **juiste tijd** ingepland, en (2) een herinnering gaat **live af** via een "kort-lont"-afspraak. De dispatch-job verstuurt elke ~minuut de herinneringen waarvan de tijd is verstreken.

**Deel 1 — De timing klopt (DBeaver).**
In de query van stap 4 zie je de kolom `send_at`: voor de `24h`-rij staat die exact 24 uur vóór de afspraak, voor de `1h`-rij exact 1 uur ervoor. Reken het samen voor (afspraaktijd − 24u / − 1u) → bewijs dat de berekening klopt.

**Deel 2 — Een herinnering live laten afgaan.** Maak een afspraak met een kort lont:

- **24h-herinnering live:** afspraak op **morgen, ~3 minuten ná de huidige tijd** (bv. nu 14:30 → morgen 14:33). De 24h-herinnering valt dan op *nu + 3 min* → binnen ~1–3 min verschijnt een rij `event_type = REMINDER_24H`, `status = sent`, met de tekst *"Herinnering: uw afspraak is morgen om…"*.
- **1h-herinnering live:** afspraak op **vandaag, ~1 uur + 3 min vanaf nu** (bv. nu 14:30 → vandaag 15:33). De 1h-herinnering valt op *nu + 3 min* → je krijgt een `REMINDER_1H`-rij (*"…over een uur…"*). *(De 24h-herinnering van déze afspraak gaat ook af — die tijd ligt al in het verleden — dat is normaal; wijs naar de REMINDER_1H-rij.)*

Toon de verstuurde herinneringen (DBeaver):
```sql
SELECT event_type, status, sent_at
FROM notification_log nl JOIN tenants t ON t.id = nl.tenant_id
WHERE t.slug = 'amc' AND event_type IN ('REMINDER_24H','REMINDER_1H')
ORDER BY sent_at DESC;
```

**De patiënt-kant:** open **FakeComWorld** (`http://localhost:1337`) — toont het de ontvangen berichten, dan zie je daar de SMS bij de patiënt binnenkomen. Anders is de `status = sent`-rij (+ de service-log met de berichttekst) het bewijs dat het bericht is afgeleverd.

> **Voorbereidingstip:** maak deze kort-lont-afspraken **vlak vóór** dit demo-onderdeel, zodat de herinnering net tijdens je uitleg binnenkomt. Patiënt moet een **telefoonnummer** hebben, anders zie je `failed` i.p.v. `sent`.

> **Als de SPA alleen vaste tijdsloten toelaat** (waardoor "+3 min" niet exact lukt): maak de afspraak via de **REST-appendix** onderaan (`postmanrequests.md` STAP 4) — daar zet je `startDateTime` op de seconde nauwkeurig, bv. `now + 24h + 3min`. Dat geeft je volledige controle over wanneer de herinnering afgaat.

---

## 5. Afspraak annuleren — in de SPA *(±2 min, de kern van FR-1g)*

Ga terug naar de afspraak in de SPA → wijzig de status naar **Cancelled**.

> ⏳ Wacht één poller-cyclus (~2 min). Vul de tijd met de uitleg: de poller pikt de `Cancelled`-status op → `AppointmentEventConsumer` → `cancelReminders()`, scoped op `(appointment_uuid, tenant_id)`.

---

## 6. Reminders gestopt — DBeaver opnieuw draaien *(±30 sec)*

Draai de query uit stap 4 nogmaals (F5 / refresh).

**Verwacht:** beide rijen staan nu op **`cancelled`**. → **bewijs FR-1g: een geannuleerde afspraak stopt de herinneringen.**

---

## 7. Monitoring — Grafana *(±2 min)*

Open **http://localhost:3000/d/beunmrs-perf**. Je ziet de activiteit van je demo terug: notificaties/min per provider, provider-latency, foutpercentage, pending reminders, outbox-pending, retry-uitkomsten.

> **Optioneel — een doorvoer-piek tonen:** draai in een terminal `scripts\loadtest.ps1 -Scenario stress` en kijk het dashboard live oplopen (piek ~166 notif/sec). Niet nodig voor het verhaal, wel indrukwekkend. Dekt NFR-9a.

---

## 8. (Optioneel) Tweede tenant — andere provider *(±1 min)*

Registreer in het portaal een tweede tenant (slug `umcg`, provider **SecurePost**) en maak een afspraak aan. Toon in DBeaver dat `umcg` via SecurePost gaat en `amc` via SwiftSend — **bewijs FR-3 + multi-tenant isolatie (NFR-1)**:
```sql
SELECT t.slug, nl.channel, nl.status, count(*)
FROM notification_log nl JOIN tenants t ON t.id = nl.tenant_id
GROUP BY t.slug, nl.channel, nl.status
ORDER BY t.slug;
```

---

## 9. (Optioneel) Retry bij falen tonen (NFR-7 / FMEA FM-5) *(±1 min)*

> Laat zien dat een mislukte verzending niet verloren gaat maar opnieuw wordt geprobeerd.

**Snelste manier:** maak een afspraak voor een patiënt **zónder telefoonnummer**. De verzending mislukt → de `FailedNotificationRetryJob` neemt het over.

Toon in DBeaver:
```sql
SELECT event_type, status, retry_count, next_retry_at, error_message
FROM notification_log nl JOIN tenants t ON t.id = nl.tenant_id
WHERE t.slug = 'amc' AND status IN ('failed','permanently_failed')
ORDER BY created_at DESC;
```
Je ziet `status = failed`, een oplopende `retry_count`, en een `next_retry_at` (volgende geplande poging).

**Vertel hierbij:** de retry-job probeert **3×** opnieuw met **exponentiële backoff (5 → 15 min)**; lukt het dan nog niet, dan wordt de status `permanently_failed` en stopt het — geen oneindige loop. Je ziet niet alle retries live (duurt ~20 min); de `failed`-rij + `next_retry_at` bewijzen dat het mechanisme **gewapend** is.

> De **diepere** resilience-proef (OpenMRS-storing → outbox buffert → herstel zonder verlies) is de chaos-test `scripts/circuitbreaker-test.ps1` — die draait 15–20 min, dus toon je als **opname**.

---

## Fallback-assets (maken vóór de presentatie)

Neem op / screenshot, voor het geval de live demo hapert:
- [ ] Geslaagde flow stap 4 (reminders `pending`) en stap 6 (reminders `cancelled`)
- [ ] Grafana-dashboard onder load (stap 7)
- [ ] Chaos-run (`scripts/circuitbreaker-test.ps1`) — draait 15–20 min, dus **vooraf** opnemen

---

# Appendix — fallback via commando's

> Werkt de SPA niet mee, of doe je het liever via de terminal? Hier dezelfde stappen via curl / OpenMRS REST / psql.

**Tenant registreren (curl):**
```powershell
curl.exe -k -X POST https://localhost:4000/api/register `
  -H "Content-Type: application/json" `
  -d '{ \"slug\":\"amc\", \"displayName\":\"Amsterdam UMC\", \"openmrsHost\":\"http://gateway/openmrs\", \"openmrsUser\":\"admin\", \"openmrsPassword\":\"Admin1234\", \"providerName\":\"SwiftSend\", \"providerApiKey\":\"sk-swiftsend-demo\", \"timezone\":\"Europe/Amsterdam\" }'
```

**Patiënt + afspraak + annuleren via OpenMRS REST:** zie [`../Info/postmanrequests.md`](../Info/postmanrequests.md) — STAP 1 (patiënt), STAP 4 (afspraak), STAP 8 (annuleren). Zet `startDateTime` 2–3 dagen vooruit.

**Reminders bekijken via psql** (zelfde queries als DBeaver):
```powershell
docker exec -it notification-db psql -U notify -d notifications -c "SELECT sn.type, sn.status, sn.send_at FROM scheduled_notifications sn JOIN tenants t ON t.id = sn.tenant_id WHERE t.slug = 'amc' ORDER BY sn.send_at;"
```
