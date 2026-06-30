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
