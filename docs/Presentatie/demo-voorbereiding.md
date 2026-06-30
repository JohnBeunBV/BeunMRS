# Demo-voorbereiding — schone run zonder ruis

> Voer dit uit **vóór** de demo (vanavond als test, morgen ~30 min van tevoren). Doel: alleen jouw demo-patiënt genereert verkeer — geen `failed`-ruis van de OpenMRS demo-afspraken.

---

## Het probleem (kort)

OpenMRS laadt standaard **demo-afspraken zonder telefoonnummer** in (ook opnieuw na een `down -v`). De poller plant daar herinneringen voor → die kunnen niet verstuurd worden → `failed` met meerdere retries. Dat vervuilt `notification_log` en Grafana.

**Oplossing:** markeer die bestaande afspraken als "gezien" (`seen_appointments`), dan slaat de poller ze over. Alleen afspraken die je **daarná** aanmaakt worden nog verwerkt.

> **Let op:** wachten tot de retries uitdoven helpt niet — de `failed`-rijen blijven staan. Resetten met `down -v` helpt ook niet — OpenMRS laadt de demo-afspraken opnieuw in. De stappen hieronder zijn de werkende route.

---

## A. (Optioneel) Een bestaande tenant volledig verwijderen

Heb je al een tenant geregistreerd en wil je schoon opnieuw beginnen? Draai dit in **DBeaver** (SQL Editor → selecteer alles → **Execute script** / Alt+X).

Eerst kijken welke slug je gebruikte:
```sql
SELECT slug, display_name, provider_name FROM tenants;
```

Dan verwijderen (vervang `'amc'` door jouw slug; volgorde is belangrijk vanwege de foreign keys):
```sql
DELETE FROM notification_audit_log  WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM notification_log        WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM scheduled_notifications WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM outbox_events           WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM seen_appointments       WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM sync_watermarks         WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM async_flow_commands     WHERE tenant_id IN (SELECT id FROM tenants WHERE slug = 'amc');
DELETE FROM tenants                 WHERE slug = 'amc';
```
Daarna is de tenant én al z'n data weg en kun je dezelfde slug opnieuw gebruiken.

---

## B. Schone demo-prep — de juiste volgorde

> Voer deze stappen ná elkaar uit. PowerShell vanuit de projectmap (`...\BeunMRS`).

**1. Stack draait en OpenMRS is op** (wacht op `Server startup`):
```powershell
docker compose up -d
docker compose logs -f backend | Select-String "Server startup"
```
(Ctrl+C om te stoppen met meekijken zodra je de regel ziet.)

**2. Registreer je tenant** via het portaal **https://localhost:3001**
- Slug `amc` · Naam `Amsterdam UMC` · OpenMRS-host `http://gateway/openmrs`
- User `admin` · Wachtwoord `Admin1234`
- Provider **SwiftSend** · Provider-API-key `sk-swiftsend-demo` · Tijdzone `Europe/Amsterdam`

**3. Markeer de bestaande OpenMRS-afspraken als 'gezien'** (poller slaat ze over) — doe dit **direct** na stap 2:
```powershell
powershell -ExecutionPolicy Bypass -File scripts\cleanup-db.ps1
```

**4. Ruim de ruis op die al voor `amc` was ontstaan** (vervang `amc` als je een andere slug gebruikt):
```powershell
"DELETE FROM notification_log WHERE tenant_id IN (SELECT id FROM tenants WHERE slug='amc'); DELETE FROM scheduled_notifications WHERE tenant_id IN (SELECT id FROM tenants WHERE slug='amc'); DELETE FROM outbox_events WHERE tenant_id IN (SELECT id FROM tenants WHERE slug='amc');" | docker exec -i notification-db psql -U notify -d notifications
```

**5. Herstart de service** (zet Grafana's fouttellers terug op nul):
```powershell
docker compose restart notification-svc
```

---

## C. Verificatie — is het schoon?

In **DBeaver**:
```sql
SELECT COUNT(*) FROM notification_log nl
JOIN tenants t ON t.id = nl.tenant_id
WHERE t.slug = 'amc';
```
**Verwacht: 0.** Zo niet, herhaal stap 4 (en check of stap 3 goed liep).

---

## D. De demo zelf

Nu is het schoon. Maak je **demo-patiënt (mét telefoonnummer!)** en een afspraak 2–3 dagen vooruit — die is nieuw, dus de poller pakt 'm wél op, zonder de oude ruis.

Volg verder het visuele script: **[demo-runbook.md](demo-runbook.md)** (stap 2 t/m 7).

---

## Waarom de volgorde klopt
- Na **stap 3** staan alle demo-afspraken in `seen_appointments` → de poller negeert ze voorgoed, ook na een herstart.
- **Stap 4** wist wat er eventueel vlak vóór stap 3 al was ingepland.
- Jouw demo-afspraak in **stap D** is nieuw → niet 'gezien' → wordt wél netjes verwerkt.
