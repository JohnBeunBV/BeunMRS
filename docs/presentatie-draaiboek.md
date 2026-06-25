# Presentatie-draaiboek — BeunMRS (herkansing)

> **Status: BASIS / werk-in-uitvoering.** Dit draaiboek is het skelet voor de herkansingspresentatie. Onderdelen gemarkeerd met 🔲 zijn nog **in te vullen** zodra de betreffende branches gemerged zijn (zie [Afhankelijkheden](#afhankelijkheden--nog-te-mergen)). Niets hier is definitief; vul aan, herverdeel en schrap waar nodig.

**Gebaseerd op:** [`docs/OPENSTAANDE-PUNTEN.md`](OPENSTAANDE-PUNTEN.md) (Deel C) + de officiële rubric ([`brightspace-docs/LU1-Rubric.pdf`](../brightspace-docs/LU1-Rubric.pdf)).

---

## 0. Kerngegevens

| Veld | Waarde |
|---|---|
| Datum & tijd | 🔲 _[in te vullen — zie Brightspace-nieuwsberichten]_ |
| Locatie / lokaal | 🔲 _[in te vullen]_ |
| Duur presentatie + demo | **30 minuten** (tijdsindeling vrij) |
| Verdiepende vragen | **15 minuten** (door docenten) |
| Aanwezig (verplicht, allen) | Wassim Balouda · Storm Kroonen · Nick de Rooij · Thijs van de Veen |
| Hulpmiddelen | Slides + **live draaiende Docker-stack** (demo's) |

---

## 1. Doel & strategie van deze presentatie

De eerste poging scoorde **33/100** (groep) met **twee nullen**: *Architectuurbeschrijving* (0/20) en *Betrouwbaarheid* (0/20). Docentcitaat: *"In de presentatie zien we dit ook terug."*

**Strategische keuze:** dit is géén feature-tour. De presentatie is opgebouwd rond de twee verloren criteria. De rode draad is **traceerbaarheid en FMEA-doorwerking, live gedemonstreerd** — precies wat de docent miste.

| Rubric-criterium (groep) | 1e poging | Doel | Stage-tijd in dit draaiboek |
|---|---|---|---|
| Architectuurbeschrijving & bedrijfsprocessen | 0/20 | 11–20 | **~7 min (kernblok A)** |
| Betrouwbaarheid | 0/20 | 11–20 | **~9 min (kernblok B)** |
| Duurzaam ontwerp | 11/20 | vasthouden | ~2 min |
| Testresultaten | 11/20 | vasthouden | ~2 min |
| Realisatieverantwoording | 11/20 | vasthouden | ~1,5 min |

**Twee "Goed"-hefbomen (20-punts-eisen) die we expliciet moeten raken:**
- Architectuur → **overwogen alternatieven mét afwijscriteria** (zit in onze ADR's).
- Betrouwbaarheid → **welke test- en verbeterstappen** performance/robuustheid hebben verbeterd (vóór/ná).

---

## 2. Rolverdeling

> OPENSTAANDE Deel C-3: verdeel sprekers zó dat ieder een stuk pakt dat hij/zij **ook in de CGI** moet kunnen verdedigen.

| Segment | Spreker | CGI-koppeling (moet dit individueel kunnen verdedigen) |
|---|---|---|
| Introductie & context | 🔲 _[naam]_ | — |
| Architectuur & C4 | 🔲 _[naam]_ | Duurzaamheid (multi-tenancy) |
| Kernblok A — Traceerbaarheid live | 🔲 _[naam]_ | Duurzaamheid + alternatieven |
| Kernblok B — Betrouwbaarheid/FMEA live | 🔲 _[naam]_ | Representatie van het testen |
| Duurzaam / Test / Realisatie (kort) | 🔲 _[naam]_ | Testen + SDLC-reflectie |
| Afronding & zelfbeoordeling | 🔲 _[naam]_ | — |

---

## 3. Tijdsindeling (run-of-show, 30 min)

| Tijd | Duur | Segment | Spreker | Slide(s) | Demo? |
|---|---|---|---|---|---|
| 0:00 | 2:00 | **Introductie & context** — probleem, SaaS-doel, team | 🔲 | 🔲 | — |
| 2:00 | 2:30 | **Architectuuroverzicht** — C4 L1→L2→L3 consistent | 🔲 | 🔲 | — |
| 4:30 | 7:00 | **KERNBLOK A — Architectuur & traceerbaarheid** | 🔲 | 🔲 | ✅ live (FR-1g) |
| 11:30 | 9:00 | **KERNBLOK B — Betrouwbaarheid & FMEA-doorwerking** | 🔲 | 🔲 | ✅ live (chaos + Grafana) |
| 20:30 | 2:00 | **Duurzaam ontwerp** — multi-tenancy + uitbreidbaarheid | 🔲 | 🔲 | — |
| 22:30 | 2:00 | **Testresultaten** — testpiramide + FMEA-koppeling | 🔲 | 🔲 | — |
| 24:30 | 1:30 | **Realisatieverantwoording** — tools + AI + zelfredzaamheid | 🔲 | 🔲 | — |
| 26:00 | 2:30 | **Afronding** — "23/23 aantoonbaar" + zelfbeoordeling tegen rubric | 🔲 | 🔲 | — |
| 28:30 | 1:30 | **Buffer / overgang naar Q&A** | allen | — | — |

> Tijden zijn richtlijn. Test de werkelijke duur in een generale repetitie en stel bij.

---

## 4. Detailuitwerking kernblokken

### KERNBLOK A — Architectuur & traceerbaarheid (was 0/20)

**Doel:** laten zien dat (niet-)functionele requirements aantoonbaar terugkomen in ontwerp én realisatie — en dat we alternatieven afwogen (20-punts-eis).

**A-1 · Traceerbaarheidsmatrix als overzichtsslide (~1,5 min)**
- Eén slide: *"23 requirements → ADR → code → test. 20 geautomatiseerd bewezen, 3 handmatig geverifieerd."*
- Bron: [`docs/Traceerbaarheid/traceerbaarheidsmatrix.md`](Traceerbaarheid/traceerbaarheidsmatrix.md).
- Boodschap: *dit is de traceerbaarheid die in poging 1 ontbrak.*

**A-2 · Eén requirement LIVE end-to-end (~4 min)** — de centrale demonstratie
- Gekozen requirement: **FR-1g (annulering stopt reminders)**.
- Keten op één slide, dan live:
  1. **Eis** FR-1g → 2. **ADR-004** (waarom event-driven/RabbitMQ) → 3. **Code** `AppointmentEventConsumer.onCancellation()` → `cancelReminders()` → 4. **Test** `AppointmentEventConsumerTest` → 5. **Live demo**.
- **Live demo-stappen:**
  - Maak een afspraak aan → toon reminders in `scheduled_notifications` (status `scheduled`).
  - Annuleer de afspraak → toon dat reminders `status='cancelled'` krijgen.
  - 🔲 _[exacte requests/queries klaarzetten — zie demo pre-flight §6]_
- **Fallback bij demofalen:** 🔲 _[screenshots/opname van de geslaagde flow — nog te maken]_.

**A-3 · Eén NFR met afgewogen alternatief (~1,5 min)** — de 20-punts-hefboom
- Gekozen NFR: **NFR-2a (integratie passend bij doel)** → **ADR-003**.
- Toon de afweging: directe DB-koppeling vs. webhook vs. **polling + RabbitMQ**, met afwijscriteria (betrouwbaarheid bij downtime, schaalbaarheid, koppeling, HL7/FHIR-aansluiting).
- Bron: [`docs/ADR's/ADR-003-integratiemethode.md`](ADR's/ADR-003-integratiemethode.md).
- 🔲 _[optioneel 2e alternatief-voorbeeld: ADR-011 reverse-proxy-consolidatie als herontwerp — alleen als tijd het toelaat]_

---

### KERNBLOK B — Betrouwbaarheid & FMEA-doorwerking (was 0/20)

**Doel:** FMEA die overeenkomt met code + architectuur (11-eis) + **test- en verbeterstappen** aantonen (20-eis). Plus realtime monitoring **live**.

**B-1 · FMEA-doorwerking: één failure mode als keten (~2,5 min)**
- Gekozen failure mode: **FM-9 (crash na DB-write, vóór RabbitMQ-publish)**.
- Keten: **risico** → **architectuurmitigatie** (outbox-patroon, ADR-007) → **code** `OutboxService.writePending()` / `OutboxRelayJob.relay()` → **test** `OutboxServiceTest` (+ W×I vóór/ná uit FMEA).
- Bron: [`docs/FMEA/FMEA_Documentatie.md`](FMEA/FMEA_Documentatie.md).
- 🔲 _[1–2 reserve-failure-modes paraat voor Q&A — bv. FM-1, FM-6]_

**B-2 · Chaos-test LIVE als bewijsstuk (~3 min)** — het hart van de betrouwbaarheidsclaim
- Draai `scripts/circuitbreaker-test.ps1`: simuleer storing → outbox buffert → herstel **zonder berichtverlies**.
- Boodschap: *de FMEA is geen belofte maar bewezen gedrag.*
- 🔲 _[exacte commando's + verwachte output klaarzetten — zie §6]_
- **Fallback:** 🔲 _[opname/screenshots van een geslaagde chaos-run]_.

**B-3 · Realtime monitoring LIVE (~2 min)**
- Open Grafana-dashboard **tijdens** een loadtest (`scripts/loadtest.ps1 -Scenario stress`).
- Toon live: messages/min, errors, retries, per-provider latency.
- Niet alleen een screenshot — **live**.
- 🔲 _[dashboard-naam bevestigen: `beunmrs-perf`? + URL `http://localhost:3000`]_
- **Fallback:** 🔲 _[dashboard-screenshot uit NFR-9a-verificatie — afhankelijk van Deel B3]_.

**B-4 · Test- en verbeterstappen vóór/ná (~1,5 min)** — de 20-punts-hefboom
- Minimaal één concrete verbeter-iteratie met een **vóór/ná-cijfer**.
- Kandidaten (kies + onderbouw):
  - Circuit breaker toegevoegd ná observatie dat poller OpenMRS-uitval niet opving.
  - Outbox-retry 3→5 pogingen na loadtest-observatie.
  - `ON CONFLICT`-fix: `notification_log` van 0 → 518 entries.
- 🔲 _[definitieve verbeterstap + cijfers — afhankelijk van A2-sectie in PERFORMANCE-RAPPORT.md (Storm)]_
- Bron: [`docs/PerformanceRapport/PERFORMANCE-RAPPORT.md`](PerformanceRapport/PERFORMANCE-RAPPORT.md).

---

## 5. Detailuitwerking overige criteria (kort — vasthouden op voldoende)

> Deze drie scoorden al 11/20. Kort en zakelijk tonen dat ze de "Voldoende"-kolom halen; **niet** over-investeren.

**Duurzaam ontwerp (~2 min)**
- Multi-tenancy: één instantie, meerdere ziekenhuizen; isolatie via `tenant_id` + `TenantContext` (ADR-005).
- Uitbreidbaarheid: nieuwe provider = Strategy-pattern (ADR-006). **Volledige verhaal**, niet alleen "klasse aanmaken" (zie OPENSTAANDE E2): klasse + DB CHECK-constraint + registratie-validatie + credentials + contract-test dwingt af.
- 🔲 _[besluit: hier ook ADR-011 als herontwerp-voorbeeld noemen? of bewaren voor CGI]_

**Testresultaten (~2 min)**
- Testpiramide (ADR-009): 87 unit → 9 security → 10 contract → 3 integratie → 2 operationeel (load + chaos). 109 JUnit-tests groen.
- Koppeling met FMEA expliciet maken (verwijst terug naar kernblok B).
- 🔲 _[één concrete kritische zwakte zelf benoemen + verbeterpunt — scoort hoger dan verdedigen]_

**Realisatieverantwoording (~1,5 min)**
- Tools + AI-inzet + **wat we zelf corrigeerden** (zelfredzaamheid).
- Bron: [`docs/Realisatielogboek/realisatielogboek.md`](Realisatielogboek/realisatielogboek.md).
- 🔲 _[D4c commit-tabel uit echte `git log` — nog in te vullen, zie OPENSTAANDE D5]_

---

## 6. Demo pre-flight checklist

> Alle demo's draaien op de live Docker-stack. Test dit **vóór** de presentatie volledig door.

- [ ] `docker compose up -d` — volledige stack draait, OpenMRS volledig opgestart (`Server startup`).
- [ ] Minimaal één tenant geregistreerd (provider 🔲 _[SwiftSend?]_).
- [ ] **Demo A (FR-1g):** requests klaargezet 🔲 _[Postman-collectie / curl-scripts]_ — afspraak aanmaken + annuleren + `scheduled_notifications`-query.
- [ ] **Demo B (chaos):** `scripts/circuitbreaker-test.ps1` getest, verwachte output bekend.
- [ ] **Demo C (monitoring):** Grafana open op 🔲 _[dashboard]_, `scripts/loadtest.ps1 -Scenario stress` getest.
- [ ] DB-client klaar (DBeaver/psql) met opgeslagen queries.
- [ ] Browser-tabs vooraf geopend: Grafana, RabbitMQ UI, registratieportaal.
- [ ] **Fallback-assets** beschikbaar voor elke demo (screenshots/opnames) — 🔲 _nog te maken_.
- [ ] Schermresolutie/zoom getest (code leesbaar op afstand).

---

## 7. Q&A-voorbereiding (15 min vragen)

> OPENSTAANDE Deel C-4: oefen antwoorden op *"hoe weten jullie dat NFR-X behaald is?"* → wijs naar matrix-rij + test.

**Verwachte vragen & waar te wijzen:**

| Verwachte vraag | Antwoordrichting / verwijzing |
|---|---|
| "Hoe weten jullie dat requirement X behaald is?" | Traceerbaarheidsmatrix-rij → ADR → klasse → test |
| "Jullie hebben meer architectuurbeslissingen, toch?" | ADR-001 t/m ADR-011; elk met alternatieven + afwijscriteria |
| "Is de FMEA-doorwerking echt getest of een belofte?" | Kernblok B: chaos-test live; FMEA-tabel ADR→code→test |
| "Waarom shared-schema en geen database-per-tenant?" | ADR-005: afweging beheer/kosten/isolatie |
| "Hoe voeg je een nieuwe provider toe?" | Volledig verhaal (E2): niet alleen klasse — ook CHECK-constraint, validatie, credentials, contract-test |
| "C4-niveaus consistent?" | C4 L1/L2/L3 nalopen; bewust uitleggen waar frontend wel/niet als actor staat |
| 🔲 _[aanvullen na generale repetitie]_ | 🔲 |

- [ ] Ieder weet welk(e) blok(ken) hij/zij verdedigt (zie §2).
- [ ] Generale repetitie met onderling kruisverhoor (één speelt examinator).

---

## 8. Techniek- & materialencheck

- [ ] Laptop(s) + voldoende geheugen voor de Docker-stack tijdens de demo.
- [ ] HDMI/USB-C adapter voor het beamerscherm 🔲 _[lokaal-specifiek]_.
- [ ] Slides geëxporteerd als PDF (fallback als presentatiesoftware faalt).
- [ ] Internet/offline: stack draait lokaal — geen externe afhankelijkheid nodig.
- [ ] Backup-laptop met dezelfde stack 🔲 _[wie?]_.

---

## 9. Afhankelijkheden — nog te mergen

> Deze onderdelen vullen de 🔲-plekken hierboven. De presentatie kan pas **volledig** worden gebouwd zodra deze branches gemerged zijn.

| Onderwerp | Levert input voor | Status / branch |
|---|---|---|
| B3 — Grafana dashboard-screenshot (NFR-9a) | Kernblok B-3 fallback | 🔲 _[branch?]_ |
| B2 — UTF-8 testbericht (NFR-8) | Test-segment bewijs | 🔲 _[branch?]_ |
| B1 — OpenMRS 2.7.x-motivatie (NFR-4) | "23/23 aantoonbaar" claim | 🔲 _[branch?]_ |
| A2 — "test- en verbeterstappen" in PERFORMANCE-RAPPORT | Kernblok B-4 | 🔲 _[branch?]_ |
| D5 — D4c commit-tabel uit `git log` | Realisatie-segment | 🔲 _[branch?]_ |
| Fallback-opnames/screenshots demo's | §6 pre-flight | 🔲 _nog te maken_ |
| Definitieve slidedeck | hele draaiboek | 🔲 _nog te bouwen (ná merges)_ |

---

## 10. Open punten / besluiten nog te nemen

- [ ] 🔲 Welke tenant/provider gebruiken we in de live demo?
- [ ] 🔲 Tonen we ADR-011 (reverse-proxy) in de presentatie of bewaren we die voor de CGI?
- [ ] 🔲 Definitieve spreker-rolverdeling (§2) vastleggen.
- [ ] 🔲 Generale repetitie inplannen + werkelijke duur meten.
- [ ] 🔲 Slidedeck-tool kiezen en deck bouwen op basis van dit draaiboek.

---

_Laatst bijgewerkt: 2026-06-25. Dit is een levend basisdocument — werk bij naarmate branches mergen en besluiten vallen._
