# Presentatie-draaiboek — BeunMRS (herkansing)

> **Status: INHOUDELIJK COMPLEET — logistiek nog in te vullen.** Dit draaiboek volgt het **volledige-rubric-dekking-model**: élke FR en NFR wordt expliciet behandeld, overwogen alternatieven mét afwijscriteria komen aan bod, ontwerpprincipes (SOLID) worden benoemd, de tests worden per soort uitgesplitst, en we sluiten af met een verplichte **live demonstratie**. Gemodelleerd naar de aanpak van een groep die hiermee de maximale score haalde. De resterende 🔲-plekken zijn organisatorisch: datum/lokaal, spreker-rolverdeling, slidedeck bouwen, fallback-opnames en de generale repetitie.

**Gebaseerd op:** de officiële rubric (`AGENTS.md` — Beroepsproduct rubric) + [`docs/Traceerbaarheid/traceerbaarheidsmatrix.md`](../Traceerbaarheid/traceerbaarheidsmatrix.md).

---

## 0. Kerngegevens

| Veld | Waarde |
|---|---|
| Datum & tijd | **1 juli 2026** |
| Locatie / lokaal | n.v.t. |
| Duur presentatie + demo | **30 minuten** (tijdsindeling vrij) |
| Verdiepende vragen | **15 minuten** (door docenten) |
| Aanwezig (verplicht, allen) | Wassim Balouda · Storm Kroonen · Nick de Rooij · Thijs van de Veen |
| Hulpmiddelen | Slides + **live draaiende Docker-stack** (demo) |

---

## 1. Doel & strategie

De eerste poging scoorde **33/100** (groep) met **twee nullen**: *Architectuurbeschrijving* (0/20) en *Betrouwbaarheid* (0/20). Docentcitaat: *"In de presentatie zien we dit ook terug."*

**Strategische keuze:** deze presentatie is **volledig en aantoonbaar**. We laten zien dat élke functionele en niet-functionele eis terugkomt in ontwerp én realisatie (traceerbaarheidsmatrix als basis), we benoemen expliciet de **overwogen alternatieven met afwijscriteria** (de 20-punts-hefboom voor architectuur) en de **test- en verbeterstappen vóór/ná** (de 20-punts-hefboom voor betrouwbaarheid), en we bewijzen het met een **live demo**.

| Rubric-criterium (groep) | 1e poging | Doel | Hefboom naar "Goed" (20) |
|---|---|---|---|
| Architectuurbeschrijving & bedrijfsprocessen | 0/20 | 20 | **Overwogen alternatieven mét afwijscriteria** (onze ADR's) |
| Betrouwbaarheid | 0/20 | 20 | **Test- en verbeterstappen vóór/ná** (FMEA + performance-rapport) |
| Duurzaam ontwerp | 11/20 | 20 | **Uitzonderingsscenario's + uitbreidbaarheid via ontwerpprincipes** |
| Testresultaten | 11/20 | 20 | **Additionele methodieken** (security, architectuur, chaos) geautomatiseerd |
| Realisatieverantwoording | 11/20 | 20 | **Concrete voorbeelden + verbeterpunten** |

---

## 2. Agenda (de zes blokken)

Deze agenda staat op slide 2 en volgt exact de rubric-criteria:

1. **Architectuurbeschrijving & bedrijfsprocessen** — alle FR + NFR + overwogen alternatieven
2. **Duurzaam ontwerp** — ontwerpprincipes (SOLID) + uitbreidbaarheid + multi-tenancy
3. **Betrouwbaarheid** — FMEA + genomen maatregelen + performance & monitoring
4. **Testresultaten** — per testsoort
5. **Realisatieverantwoording** — ontwikkeltools + AI-inzet
6. **Demonstratie** — live

---

## 3. Rolverdeling

> Verdeel sprekers zó dat ieder een blok pakt dat hij/zij **ook in de CGI** moet kunnen verdedigen.

| Agendablok | Spreker | CGI-koppeling (individueel verdedigen) |
|---|---|---|
| Intro & agenda | 🔲 _[naam]_ | — |
| 1. Architectuur & bedrijfsprocessen | 🔲 _[naam]_ | Duurzaamheid + alternatieven |
| 2. Duurzaam ontwerp | 🔲 _[naam]_ | Duurzaamheid (multi-tenancy, SOLID) |
| 3. Betrouwbaarheid | 🔲 _[naam]_ | Representatie van het testen (FMEA) |
| 4. Testresultaten | 🔲 _[naam]_ | Representatie van het testen |
| 5. Realisatieverantwoording | 🔲 _[naam]_ | SDLC-reflectie + AI-inzet |
| 6. Live demonstratie | 🔲 _[naam]_ | — (allen paraat voor vragen) |

---

## 4. Tijdsindeling (run-of-show, 30 min)

| Tijd | Duur | Agendablok | Kerninhoud | Demo? |
|---|---|---|---|---|
| 0:00 | 2:00 | **Intro & agenda** | probleem, SaaS-doel, team, agenda | — |
| 2:00 | 7:00 | **1. Architectuur & bedrijfsprocessen** | alle FR + NFR (thematisch) · overwogen alternatieven · C4 L1→L3 | — |
| 9:00 | 3:00 | **2. Duurzaam ontwerp** | SOLID-principes · uitbreidbaarheid · multi-tenancy | — |
| 12:00 | 4:00 | **3. Betrouwbaarheid** | FMEA + maatregelen · performance · monitoring | 🎞️ opname (chaos) |
| 16:00 | 5:00 | **4. Testresultaten** | 6 slides: aanpak + unit · security · contract · integratie · chaos/performance | — |
| 21:00 | 2:00 | **5. Realisatieverantwoording** | tools · AI + zelfredzaamheid | — |
| 23:00 | 6:00 | **6. LIVE DEMONSTRATIE** | register → afspraak → reminders → annuleren · Grafana live | ✅ live |
| 29:00 | 1:00 | **Afronding** | "33/33 aantoonbaar" + zelfbeoordeling | — |

> Tijden zijn richtlijn. Test de werkelijke duur in een generale repetitie en stel bij. De **chaos-test draait 15–20 min** en past niet live in het slot → toon die als korte opname/screenshot in blok 3. De **loadtest + Grafana is snel en kan wél live** in blok 6.

---

## 5. Detailuitwerking per agendablok

### Blok 1 — Architectuurbeschrijving & bedrijfsprocessen (~7 min) — *was 0/20*

**Doel:** aantonen dat (niet-)functionele requirements aantoonbaar terugkomen in ontwerp én realisatie (11-eis) én dat we alternatieven afwogen met criteria (20-eis).

**1.1 · Functionele eisen (alle, ~1,5 min)**

| FR | Eis | Realisatie |
|---|---|---|
| **FR-1** | Patiënt ontvangt afspraakherinnering | `ReminderScheduler` + `ReminderDispatchJob` |
| FR-1a/b | 24 uur én 1 uur vóór de afspraak | twee rijen in `scheduled_notifications` |
| FR-1c/d/e | Datum/tijd · locatie · instructies | `MessageHelper.formatTime/locationSuffix/commentsSuffix` |
| FR-1f | Geen notificatie voor reeds aangevangen afspraak | `ReminderDispatchJob` → `status='skipped'` |
| FR-1g/h | Annulering stopt · wijziging past reminders aan | `AppointmentEventConsumer` → `cancelReminders()` / reschedule |
| **FR-2** | Logging voor factuurcontrole | `notification_log` + `notification_audit_log` |
| **FR-3** | Eén provider per organisatie | `NotificationDispatcher` leest `tenant.providerName` |

**1.2 · Niet-functionele eisen — thematisch gegroepeerd (~2,5 min)**

> Toon ze in dezelfde clusters als de slide; verwijs steeds naar matrix-rij → ADR → code → test.

- **Integratie & platformonafhankelijkheid:** NFR-1 (multi-tenant, zelfstandig) · NFR-2 (passende + gedocumenteerde + beveiligde integratie, 2a/2b/2c) · NFR-4 (OpenMRS 2.7.x via `/ws/rest/v1/`) · NFR-12 (uitbreidbaar naar andere OpenMRS-modules via RabbitMQ routing keys).
- **Beveiliging & privacy:** NFR-5a (AES-256-GCM) · NFR-5b (TLS 1.3, NGINX) · NFR-5c (geen secrets in code, `.env` + env-vars) · NFR-5d (PII gemaskeerd) · NFR-10 (14-dagen verwijdering, `DataRetentionJob`) · NFR-11 (1-jaar PII-vrije audit-log).
- **Messaging & berichtverwerking:** NFR-3 (alle 4 providers) · NFR-6 (HL7-aansluiting: 6a validatie via Jackson strict, 6b ack via AMQP + status-trail, 6c logging/tracking, 6d transformatie per provider-adapter, 6e queueing + retry) · NFR-7 (zelfstandig + fallback: outbox + circuit breaker + reconciler).
- **Observability:** NFR-9a (Grafana + Prometheus + Loki) · NFR-9b (OpenTelemetry — **bewust niet**, gemotiveerd in ADR-010).
- **Internationaal:** NFR-8 (UTF-8 end-to-end) · NFR-13 (tijdzones per tenant).

> **Let op (verdedigbaar verschil):** de opdracht noemt OpenTelemetry; wij dekken NFR-9 met Micrometer + Prometheus + Loki en motiveren in **ADR-010** waarom OTLP-overhead niet gerechtvaardigd is voor één service. Benoem dit proactief — het is een bewuste afweging, geen omissie.

**1.3 · Overwogen alternatieven mét afwijscriteria (~2 min)** — *de 20-punts-hefboom*

| Beslissing | Gekozen | Afgevallen alternatieven + afwijscriterium |
|---|---|---|
| **ADR-001** zelfstandig vs ingebouwd | Zelfstandige SaaS-module | Ingebouwde OpenMRS-module → niet multi-tenant, gekoppeld aan OpenMRS-release-cyclus |
| **ADR-003** integratiemethode | REST v1 polling + RabbitMQ-buffer | Webhook → events verloren bij downtime · AtomFeed → vereist Bahmni · FHIR2 → `HAPI-0302` (Appointment niet ondersteund) · directe DB-koppeling → breekt bij schemawijziging |
| **ADR-005** multi-tenancy | Shared-schema + `tenant_id` + `TenantContext` | Database-per-tenant → hogere beheer-/kostenlast bij veel tenants |
| **ADR-010** monitoring | Micrometer + Prometheus + Loki | OpenTelemetry/OTLP → overhead niet gerechtvaardigd voor single-service |

Bron: [`docs/ADR's/`](../ADR's/) (ADR-001 t/m ADR-011).

**1.4 · C4-diagrammen (~1 min)**
- L1 (systeemcontext) → L2 (containers) → L3 (componenten notification-svc), consistent doorlopen.
- Bron: [`docs/C4-diagrammen/`](../C4-diagrammen/).

---

### Blok 2 — Duurzaam ontwerp (~3 min) — *vasthouden + naar 20*

**Doel:** ontwerpprincipes expliciet maken (11-eis) + uitbreidbaarheid en uitzonderingsscenario's onderbouwen (20-eis).

**2.1 · Ontwerpprincipes (SOLID), gekoppeld aan onze code**

| Principe | In onze code |
|---|---|
| **Open-Closed** | `NotificationProvider`-interface — nieuwe provider = nieuwe klasse, **nul** wijzigingen aan bestaande code. Afgedwongen door `NotificationProviderContractTest`. |
| **Dependency Inversion** | `NotificationDispatcher` hangt af van de `NotificationProvider`-abstractie, niet van een concrete adapter; Spring injecteert de implementaties. |
| **Single Responsibility** | Aparte klassen/jobs: poller · scheduler · dispatch-job · retry-job · retentie-job · outbox-relay — elk één verantwoordelijkheid. |
| **Liskov Substitution** | Elke `NotificationProvider` is uitwisselbaar zonder de dispatcher te wijzigen. |
| **Interface Segregation** | Smalle interface (`providerName` · `isEnabled` · `send`) — geen provider draagt ongebruikte methodes. |

**2.2 · Uitbreidbaarheid (het volledige verhaal, niet alleen "klasse aanmaken")**
- Nieuwe provider = klasse + `@Component` → **plus** DB CHECK-constraint uitbreiden + registratie-validatie + credential-afhandeling. De `NotificationProviderContractTest` dwingt dit op build-time af (uniqueness, Spring-discoverability, schema-conformiteit). Bron: ADR-006.

**2.3 · Uitzonderingsscenario's & persistentie (best practices)**
- Multi-tenancy: isolatie via `tenant_id` + `TenantContext` (ADR-005).
- Idempotentie: `seen_appointments` PRIMARY KEY + `ON CONFLICT DO NOTHING` — nooit dubbel verzenden.
- Transactionele outbox: data + event in één commit (ADR-007) — geen dual-write.
- Repository-laag scoped per tenant; partiële writes → rollback.

---

### Blok 3 — Betrouwbaarheid (~4,5 min) — *was 0/20*

**Doel:** FMEA die overeenkomt met code + architectuur (11-eis) + **test- en verbeterstappen** aantonen (20-eis), plus realtime monitoring.

**3.1 · FMEA — failure modes → maatregel (~2 min)**
- 11 failure modes, elk gekoppeld aan ADR → code → test, met W×I vóór/ná.
- Toon één keten volledig: **FM-9 (crash na DB-write, vóór publish)** → outbox-patroon (ADR-007) → `OutboxService.writePending()` / `OutboxRelayJob.relay()` → `OutboxServiceTest` (W×I 10 → 1, 90% reductie).
- Bronnen: [`docs/FMEA/FMEA_Documentatie.md`](../FMEA/FMEA_Documentatie.md) (volledige analyse + risicomatrix) en [`docs/FMEA/FMEA_BeunMRS.xlsx`](../FMEA/FMEA_BeunMRS.xlsx) (rubric-format: component/failure mode/effect/oorzaak/maatregel).

**3.2 · Genomen maatregelen (~1 min)**
- Circuit breaker (5 fouten → 2 min pauze per tenant-slug) · transactionele outbox + relay · retry met exponential backoff (5→15 min → `permanently_failed`) · durable queues + DLX · duplicate guard (`seen_appointments`) · watermark per tenant · jitter/health checks.

**3.3 · Performance & monitoring (~1,5 min)**
- **Test- en verbeterstappen vóór/ná** (20-punts-hefboom) — **gekozen verbeterstap: de `ON CONFLICT`-bugfix.** Symptoom: alle notificaties verwerkt maar `notification_log` bleef leeg. Oorzaak: `ON CONFLICT ON CONSTRAINT idx_...` verwees naar een *index* i.p.v. een *named constraint* → PostgreSQL-fout. Fix: kolom-gebaseerde conflictdetectie `ON CONFLICT (tenant_id, patient_uuid, event_type, channel) WHERE status='sent'`. **Meetbaar resultaat: `notification_log` na 518 dispatches van 0 → 518 rijen; DB-write-fouten per batch van 518 → 0; duplicate-guard van uit → actief.** Bron: [`docs/PerformanceRapport/PERFORMANCE-RAPPORT.md`](../PerformanceRapport/PERFORMANCE-RAPPORT.md) §7.
- Gemeten: piek **166 notificaties/sec**, outbox-relay **100%**, latency **~63 ms**.
- Chaos-test als bewijs (opname/screenshot — draait te lang voor live): storing → outbox buffert → herstel **zonder berichtverlies** (NFR-7).

---

### Blok 4 — Testresultaten (~5 min, 6 slides) — *vasthouden + naar 20*

**Doel:** aantonen dat tests werking én betrouwbaarheid valideren, mét additionele methodieken (security, architectuur, chaos) — geautomatiseerd. Per testsoort één slide, naar het model van een groep die hier maximaal scoorde.

| # | Slide | Aantal | Tool | Kernpunt |
|---|---|---|---|---|
| 4.0 | **Testaanpak & testpiramide** | — | — | gelaagde aanpak; 129 groen; tooling-overzicht |
| 4.1 | **Unit-tests** | 110 | JUnit 5 + Mockito | providers, scheduler, dispatcher, idempotentie, retentie, retry |
| 4.2 | **Security-tests** | 9 | Spring MockMvc | ontbrekende/ongeldige API-key, cross-tenant isolatie, ThreadLocal-hygiëne |
| 4.3 | **Architectuur- / contract-tests** | 10 | classpath-scan + reflectie | provider-extension-point op build-time afgedwongen |
| 4.4 | **Integratietests** | 3 | Testcontainers + PostgreSQL 16 | echte keten *register → dispatch → notification_log* |
| 4.5 | **Chaos & performance** | 2 scripts | Docker + loadtest/Grafana | storing→herstel zonder verlies · 166 notif/sec |

**Per slide screenshot-tip:** unit → een testklasse · security → `TenantApiKeyFilterTest` (cross-tenant) · contract → `NotificationProviderContractTest` · integratie → Testcontainers-output · chaos/performance → Grafana onder load. Code: `notification-service/src/test/...`; scripts: `scripts/`.

- **129 JUnit-tests groen** — 0 failures, 0 errors (JDK 24, geverifieerd 26 juni 2026); 132 mét Docker.
- Herkansing-toevoeging benoemen: 5 resilience-/job-/service-testklassen (`ReminderDispatchJobTest`, `FailedNotificationRetryJobTest`, `DataRetentionJobTest`, `OutboxRelayJobTest`, `PersonContactServiceTest`).
- Eén kritische zwakte zélf benoemen + verbeterpunt (scoort hoger dan verdedigen).
- **Coverage komt bewust NIET op een slide.** Reden: het staat nergens anders in de documentatie (testrapport, matrix), de rubric vraagt het niet, en een los getal zou inconsistent zijn én onnodig vragen oproepen over de branch coverage. Het testverhaal leunt op de **129-test-uitsplitsing per soort** — dat is compleet op zichzelf.
- **Wél paraat voor Q&A:** JaCoCo is geconfigureerd in de pom. Als een docent ernaar vraagt, antwoord eerlijk: totaal **62% regels / 47% branches**, **kernlogica ~80% regels** (consumer/service/scheduler/outbox/adapters/security 85–100%); `poller`/`reconciler`/`config` zijn bewust niet unit-getest maar gevalideerd via `EndToEndNotificationFlowTest` + de live demo. Rapport genereren: `mvn -f notification-service/pom.xml test` → `target/site/jacoco/index.html`.
- Bron: [`docs/Tests/testrapport.md`](../Tests/testrapport.md).

---

### Blok 5 — Realisatieverantwoording (~2 min) — *vasthouden + naar 20*

**Doel:** tools + AI-inzet + reflectie op waarde/kosten + **wat we zelf corrigeerden** (zelfredzaamheid).

**5.1 · Ontwikkeltools** — IntelliJ IDEA · VS Code · Docker Compose · Postman · DBeaver · Git/GitHub (branches + PR's + takenbord) · RabbitMQ Management UI · Grafana · Draw.io/Markdown/Mermaid voor docs-in-de-codebase.

**5.2 · AI-tools (Claude Code)** — code-generatie + sparring → tijdwinst; reflectie op kosten (iteraties, debugtijd, "AI-waan"). **Benadruk zelfredzaamheid:** ADR's en FMEA zijn inhoudelijk door het team bepaald; AI verzorgde opmaak/boilerplate. Concrete voorbeelden met prompts + menselijke correcties in [`docs/Realisatielogboek/realisatielogboek.md`](../Realisatielogboek/realisatielogboek.md) (D4a/b/c).

---

### Blok 6 — LIVE DEMONSTRATIE (~7 min) — *verplicht*

**Doel:** bewijzen dat het werkt. Strak script, alles vooraf klaargezet (zie §6 pre-flight).

| Stap | Handeling | Bewijs op scherm |
|---|---|---|
| 1 | Tenant registreren via portaal (`https://localhost:3001`) | API-key terug |
| 2 | Afspraak aanmaken in OpenMRS | poller pikt op |
| 3 | Reminders tonen in `scheduled_notifications` | 24h + 1h rijen, status `pending` |
| 4 | Afspraak **annuleren** (FR-1g) | reminders → `status='cancelled'` |
| 5 | **Grafana live** tijdens `loadtest.ps1 -Scenario stress` | messages/min, errors, retries, per-provider latency |
| 6 | (optioneel) tweede tenant met andere provider | juiste provider per tenant in logs |

- **Fallback bij demofalen:** 🔲 _[screenshots/opname van de geslaagde flow + chaos-run — nog te maken]_.

---

## 6. Demo pre-flight checklist

> Alle demo's draaien op de live Docker-stack. Test dit **vóór** de presentatie volledig door. **Volledig script: [`docs/demo-runbook.md`](demo-runbook.md)** — exacte commando's, tenant-registratie en SQL-queries.

- [ ] `docker compose up -d` — volledige stack draait, OpenMRS volledig opgestart (`Server startup`).
- [ ] Tenant **`amc` (SwiftSend)** geregistreerd via portaal of curl (zie runbook §1).
- [ ] **Demo (FR-1g):** afspraak ~2 dagen vooruit aangemaakt (runbook §2) zodat reminders `pending` blijven; `scheduled_notifications`- en annuleer-query's klaar.
- [ ] **Grafana live:** dashboard `beunmrs-perf` open (`http://localhost:3000/d/beunmrs-perf`), `scripts/loadtest.ps1 -Scenario stress` getest.
- [ ] **Chaos-opname** gereed voor blok 3 (`scripts/circuitbreaker-test.ps1` vooraf gedraaid + opgenomen/gescreenshot).
- [ ] DB-client klaar (DBeaver/psql) met opgeslagen queries.
- [ ] Browser-tabs vooraf geopend: Grafana, RabbitMQ UI, registratieportaal.
- [ ] **Fallback-assets** beschikbaar voor elke demo (screenshots/opnames) — 🔲 _nog te maken_.
- [ ] Schermresolutie/zoom getest (code leesbaar op afstand).

---

## 7. Q&A-voorbereiding (15 min vragen)

> Oefen antwoorden op *"hoe weten jullie dat NFR-X behaald is?"* → wijs naar matrix-rij + ADR + test.

| Verwachte vraag | Antwoordrichting / verwijzing |
|---|---|
| "Hoe weten jullie dat requirement X behaald is?" | Traceerbaarheidsmatrix-rij → ADR → klasse → test |
| "Jullie hebben meer architectuurbeslissingen, toch?" | ADR-001 t/m ADR-011; elk met alternatieven + afwijscriteria |
| "Waarom polling en geen events/webhook?" | ADR-003: webhook verliest events bij downtime; polling + outbox = fallback ingebouwd |
| "De opdracht vraagt OpenTelemetry — waarom niet?" | ADR-010: Micrometer + Prometheus + Loki dekt NFR-9a; OTLP-overhead niet gerechtvaardigd voor één service |
| "Wat is jullie code-coverage?" | Eerlijk: kernlogica ~80% regels (consumer/service/scheduler/outbox/adapters 85–100%); poller/reconciler/config bewust via integratietest + demo i.p.v. unit-test. Branch coverage is verbeterpunt. (Niet op slide — JaCoCo op verzoek te draaien.) |
| "Is de FMEA-doorwerking echt getest of een belofte?" | Blok 3 + chaos-opname; FMEA-tabel ADR→code→test |
| "Waarom shared-schema en geen database-per-tenant?" | ADR-005: afweging beheer/kosten/isolatie |
| "Hoe voeg je een nieuwe provider toe?" | Volledig verhaal: klasse + CHECK-constraint + validatie + credentials + contract-test |
| "C4-niveaus consistent?" | C4 L1/L2/L3 nalopen; bewust uitleggen waar frontend wel/niet als actor staat |
| 🔲 _[aanvullen na generale repetitie]_ | 🔲 |

- [ ] Ieder weet welk(e) blok(ken) hij/zij verdedigt (zie §3).
- [ ] Generale repetitie met onderling kruisverhoor (één speelt examinator).

---

## 8. Techniek- & materialencheck

- [ ] Laptop(s) + voldoende geheugen voor de Docker-stack tijdens de demo.
- [ ] HDMI/USB-C adapter voor het beamerscherm 🔲 _[lokaal-specifiek]_.
- [ ] Slides geëxporteerd als PDF (fallback als presentatiesoftware faalt).
- [ ] Internet/offline: stack draait lokaal — geen externe afhankelijkheid nodig.
- [ ] Backup-laptop met dezelfde stack 🔲 _[wie?]_.

---

## 9. Status afhankelijkheden

> De technische afhankelijkheden zijn **allemaal gemerged**. Wat resteert is organisatorisch (fallback-opnames + slidedeck).

| Onderwerp | Levert input voor | Status |
|---|---|---|
| Traceerbaarheidsmatrix (33/33 detailregels ✅) | Blok 1 | ✅ Gemerged |
| FMEA + Excel (rubric-format) | Blok 3 | ✅ Aanwezig (`docs/FMEA/`) |
| Grafana dashboard `beunmrs-perf` (NFR-9a) | Blok 3 + 6 | ✅ Gemerged — meetwaarden in testrapport §3.15 |
| UTF-8 testbericht (NFR-8) | Blok 1 | ✅ Gemerged — testrapport §3.17 |
| OpenMRS 2.7.x-motivatie (NFR-4) | Blok 1 | ✅ Gemerged — ADR-003 |
| Performance vóór/ná-meting | Blok 3 | ✅ Gemerged — PERFORMANCE-RAPPORT.md |
| D4c commit-tabel uit `git log` | Blok 5 | ✅ Gemerged — realisatielogboek |
| Fallback-opnames/screenshots demo's | §6 pre-flight | 🔲 _nog te maken_ |
| Definitieve slidedeck | hele draaiboek | 🔲 _nog te bouwen_ |

---

## 10. Open punten / besluiten nog te nemen

- [x] ~~Welke tenant/provider in de live demo?~~ **SwiftSend** (tenant `amc`).
- [ ] 🔲 Definitieve spreker-rolverdeling (§3) vastleggen.
- [x] ~~Eén concrete vóór/ná-verbeterstap kiezen voor blok 3~~ **`ON CONFLICT`-fix (0 → 518 rijen)** — zie blok 3.3.
- [x] ~~JaCoCo coverage meten~~ **Gemeten: 62% regels / 47% branches totaal; ~80% kernlogica.** Besluit wel/niet tonen: zie blok 4 (advies: weglaten of eerlijk als kern-coverage).
- [ ] 🔲 Generale repetitie inplannen + werkelijke duur meten.
- [x] ~~Slidedeck-tool kiezen~~ **PowerPoint**. Deck nog te bouwen op basis van dit draaiboek (onderdeel C).
- [ ] 🔲 Fallback-opnames/screenshots maken (onderdeel C — vereist draaiende stack).

---

_Laatst bijgewerkt: 2026-06-28. Volledige-rubric-dekking-model: alle FR + NFR expliciet, alternatieven mét criteria, SOLID, test-uitsplitsing per soort, live demo. Resterende open punten zijn organisatorisch._
