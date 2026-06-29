# Sprekersnotities & slide-bouwgids — BeunMRS-presentatie

> **Doel van dit document.** Per slide staat hier: (1) **wat erop hoort**, (2) een **sprekersnotitie** (wat je vertelt), en (3) **waar je de inhoud terugvindt** in de code/docs. Gebruik (3) om de juiste plek op te zoeken, het te begrijpen, en **zelf een screenshot van de bijpassende code** te maken voor op de slide. Zo leer je de stof terwijl je het deck afbouwt.
>
> Het gegenereerde deck [`BeunMRS-presentatie.pptx`](BeunMRS-presentatie.pptx) is je **startpunt** (tekst staat er al op). Vervang tekst-slides waar mogelijk door echte **code-screenshots**, **C4-diagrammen** en **Grafana-beelden** — dat scoort hoger dan bullets.
>
> **Codepaden** zijn vanaf de repo-root (in VS Code: `Ctrl+P` → plak het pad). **Docs** zijn klikbare links vanuit deze map.

**Bronnen die je vaak nodig hebt:**
- Traceerbaarheidsmatrix (requirement → ADR → code → test): [`../Traceerbaarheid/traceerbaarheidsmatrix.md`](../Traceerbaarheid/traceerbaarheidsmatrix.md)
- ADR's: [`../ADR's/`](../ADR's/) · FMEA: [`../FMEA/FMEA_Documentatie.md`](../FMEA/FMEA_Documentatie.md) · Testrapport: [`../Tests/testrapport.md`](../Tests/testrapport.md)
- Performance: [`../PerformanceRapport/PERFORMANCE-RAPPORT.md`](../PerformanceRapport/PERFORMANCE-RAPPORT.md) · Demo-script: [`demo-runbook.md`](demo-runbook.md)

---

## Slide 1 — Titel

- **Op de slide:** projectnaam, teamnamen, vak/klas, datum 1 juli 2026.
- **Sprekersnotitie:** "Wij zijn team BeunMRS. We bouwden een zelfstandige, multi-tenant SaaS-notificatiemodule voor OpenMRS die patiënten afspraakherinneringen stuurt via verschillende messaging providers." Kort — meteen door.
- **Waar te vinden:** projectomschrijving in [`../../README.md`](../../README.md).

---

## Slide 2 — Agenda

- **Op de slide:** de 6 rubric-blokken (architectuur, duurzaam ontwerp, betrouwbaarheid, testresultaten, realisatie, demo).
- **Sprekersnotitie:** noem dat je de presentatie expliciet rond de beoordelingscriteria hebt opgebouwd en met een live demo afsluit. 15 seconden.
- **Waar te vinden:** rubric in [`../../AGENTS.md`](../../AGENTS.md) (Beroepsproduct rubric).

---

## Slide 3 — Introductie & context

- **Op de slide:** het probleem (gemiste afspraken, versnipperde messaging-platformen), het SaaS-doel, en de drie kernwaarden (zelfstandig/veerkrachtig, uitbreidbaar, aantoonbaar).
- **Sprekersnotitie:** schets waarom dit een SaaS is: organisaties willen herinneringen sturen zonder zelf infrastructuur te beheren; elke tenant heeft eigen OpenMRS-koppeling, sleutels en één provider. Benoem dat "aantoonbaar" de rode draad is (poging 1 verloor punten op architectuur en betrouwbaarheid; nu koppelen we élke eis aan ADR, code én test).
- **Waar te vinden:** opdracht in [`../Info/OpdrachtOpenMRS.md`](../Info/OpdrachtOpenMRS.md); context in [`../../CLAUDE.md`](../../CLAUDE.md).

---

## Slide 4 — Architectuuroverzicht (C4)

- **Op de slide:** **vervang de tekst door je echte C4-diagrammen** (L1 → L2 → L3). Toon één per niveau of een opbouw.
- **Sprekersnotitie:** loop van buiten naar binnen: L1 OpenMRS-instanties → onze module → providers; L2 de containers (poller, RabbitMQ, service, PostgreSQL, NGINX, Grafana-stack); L3 de componenten ín de notification-service. Sluit af met de integratiekeuze: REST v1 polling elke 2 min + RabbitMQ-buffer.
- **Waar te vinden / screenshot:**
  - Diagrammen: [`../C4-diagrammen/`](../C4-diagrammen/) (`.drawio` — exporteer als PNG voor de slide).
  - De componenten in code: `notification-service/src/main/java/com/openmrs/notification/poller/OpenMrsAppointmentPoller.java`, `consumer/AppointmentEventConsumer.java`, `scheduler/ReminderScheduler.java`.
  - Integratie-onderbouwing: ADR-003 in [`../ADR's/`](../ADR's/).

---

## Slide 5 — Functionele eisen (FR)

- **Op de slide:** de FR-tabel (al gevuld). Eventueel een screenshot van de scheduler-code ernaast.
- **Sprekersnotitie:** loop de FR's langs en koppel elk aan een klasse. Benadruk FR-1f (skip reeds-aangevangen) en FR-1g/h (annulering/wijziging) — die tonen we live.
- **Waar te vinden / screenshot:**
  - FR-1a/b (24h+1h plannen): `scheduler/ReminderScheduler.java` → `scheduleReminders()`.
  - FR-1c/d/e (datum/locatie/instructies): `util/MessageHelper.java` → `formatTime()`, `locationSuffix()`, `commentsSuffix()`.
  - FR-1f (skip): `scheduler/ReminderDispatchJob.java` (status `skipped`).
  - FR-1g/h (annuleren/wijzigen): `consumer/AppointmentEventConsumer.java` → `onCancellation()` / `onAppointment()`.
  - FR-2 (logging): `outbox/OutboxService.java` → `recordResult()`.
  - FR-3 (één provider): `service/NotificationDispatcher.java`.
  - Tests als bewijs: `ReminderSchedulerTest`, `ReminderDispatchJobTest`, `AppointmentEventConsumerTest` in `notification-service/src/test/...`.

---

## Slide 6 — NFR: Integratie & platformonafhankelijkheid (NFR-1, 2, 4, 12)

- **Op de slide:** de vier NFR's met één regel uitleg.
- **Sprekersnotitie:** multi-tenancy is het hart (NFR-1). Leg uit dat je via REST v1 bewust 2.x én 3.x compatibel bent (NFR-4) en uitbreidbaar naar andere modules via routing keys (NFR-12).
- **Waar te vinden / screenshot:**
  - NFR-1 (TenantContext): `tenant/TenantContext.java` + `tenant/TenantApiKeyFilter.java`. Mooi screenshot: de ThreadLocal en de `finally`-clear.
  - NFR-4 (REST v1 endpoints): `poller/OpenMrsAppointmentPoller.java` (de `/ws/rest/v1/appointment/search`-call) + ADR-003 § OpenMRS-versiecompatibiliteit.
  - NFR-12 (routing keys): `infra/rabbitmq/definitions/topology.json`.
  - Matrix-rijen NFR-1/2/4/12 in [`../Traceerbaarheid/traceerbaarheidsmatrix.md`](../Traceerbaarheid/traceerbaarheidsmatrix.md).

---

## Slide 7 — NFR: Beveiliging & privacy (NFR-5a–d, 10, 11)

- **Op de slide:** versleuteling, TLS, geen secrets in code, PII-masking, retentie 14 dagen / 1 jaar.
- **Sprekersnotitie:** "Defense in depth": AES-256 in rust, TLS 1.3 in transport, secrets buiten code, PII gemaskeerd in logs, en automatische opschoning. Dit is meteen ook FMEA-/privacy-verhaal.
- **Waar te vinden / screenshot:**
  - NFR-5a (AES-256-GCM): `security/AesEncryptionService.java` → `encrypt()`/`decrypt()`. Sterk screenshot.
  - NFR-5b (TLS 1.3): `infra/nginx/` (zoek `ssl_protocols TLSv1.3`).
  - NFR-5c (geen secrets): `.env.example` + gebruik van env-vars in `security/AesEncryptionService.java`.
  - NFR-5d (PII-masking): `util/MessageHelper.java` → `mask()`.
  - NFR-10/11 (retentie): `scheduler/DataRetentionJob.java` → `runRetention()`; schema `infra/postgres/init/00_schema.sql` (`notification_audit_log`).
  - Tests: `AesEncryptionServiceTest`, `MessageHelperTest`, `DataRetentionJobTest`. Security-audit: [`../Security/SECURITY-AUDIT.md`](../Security/SECURITY-AUDIT.md).

---

## Slide 8 — NFR: Messaging & berichtverwerking / HL7 (NFR-3, 6a–e, 7)

- **Op de slide:** 4 providers; HL7-aansluiting opgesplitst in 6a–e; zelfstandig + fallback.
- **Sprekersnotitie:** leg uit dat "HL7-aansluiting" bij ons concreet wordt in 5 eigenschappen: validatie, acknowledgement, logging, transformatie, queueing+retry. Elke provider transformeert het event naar zijn eigen formaat (JSON/SOAP/async).
- **Waar te vinden / screenshot:**
  - NFR-3 (4 providers): `adapter/swiftsend/SwiftSendProvider.java`, `adapter/securepost/SecurePostProvider.java`, `adapter/legacylink/LegacyLinkProvider.java`, `adapter/asyncflow/AsyncFlowProvider.java`. Screenshot: de SOAP-envelope in LegacyLink of de twee-fasen poll in AsyncFlow — die zien er indrukwekkend uit.
  - NFR-6a (validatie): `consumer/AppointmentEventConsumer.java` + `model/AppointmentEvent.java` (Jackson-deserialisatie).
  - NFR-6b (ack): `application.yml` (`acknowledge-mode: auto`) — `notification-service/src/main/resources/application.yml`.
  - NFR-6d (transformatie): de `send()`-methode in elke adapter.
  - NFR-6e (queueing+retry): `outbox/OutboxRelayJob.java` + `scheduler/FailedNotificationRetryJob.java`.
  - NFR-7 (fallback): `reconciler/AppointmentReconciler.java` + circuit breaker in `service/NotificationDispatcher.java`.

---

## Slide 9 — NFR: Observability & internationaal (NFR-9, 8, 13)

- **Op de slide:** monitoring-stack; bewuste keuze géén OpenTelemetry; UTF-8; tijdzones.
- **Sprekersnotitie:** **dit is een verdedig-slide.** Zeg expliciet: de opdracht noemt OpenTelemetry, wij dekken de eis met Micrometer + Prometheus + Loki en motiveren dat in ADR-010 (OTLP-overhead niet gerechtvaardigd voor één service). Toon dat je een bewuste afweging maakte, geen omissie.
- **Waar te vinden / screenshot:**
  - NFR-9a (metrics): `config/MetricsGauges.java` (de gauges) + Grafana-dashboard `beunmrs-perf`. Screenshot het dashboard.
  - NFR-9b-motivatie: ADR-010 in [`../ADR's/`](../ADR's/).
  - NFR-8 (UTF-8): zoek `StandardCharsets.UTF_8` in `security/AesEncryptionService.java` en `tenant/TenantService.java`; bewijs in [`../Tests/testrapport.md`](../Tests/testrapport.md) §3.17.
  - NFR-13 (tijdzones): `util/MessageHelper.java` → `formatTime(instant, timezone)`; `tenant/Tenant.java` (`timezone`).

---

## Slide 10 — Overwogen alternatieven (mét afwijscriteria)

- **Op de slide:** de tabel met gekozen vs. afgevallen opties + waarom. **Dit is de 20-punten-hefboom voor architectuur — neem hier de tijd.**
- **Sprekersnotitie:** behandel minstens ADR-003 grondig: waarom polling i.p.v. webhook (events weg bij downtime), AtomFeed (vereist Bahmni), FHIR2 (HAPI-0302). En ADR-005: shared-schema vs database-per-tenant (beheer/kosten). Het criterium telt, niet alleen de keuze.
- **Waar te vinden / screenshot:** ADR-001, ADR-003, ADR-005, ADR-010 in [`../ADR's/`](../ADR's/). Screenshot de "Overwogen alternatieven"-sectie uit ADR-003.

---

## Slide 11 — Ontwerpprincipes (SOLID)

- **Op de slide:** de SOLID-tabel gekoppeld aan onze code.
- **Sprekersnotitie:** je sterkste verhaal is **Open-Closed**: een nieuwe provider is één nieuwe klasse, nul wijzigingen aan bestaande code — en dat wordt op build-time afgedwongen door een contract-test. Liskov/DI volgen daaruit: de dispatcher kent alleen de interface.
- **Waar te vinden / screenshot:**
  - De interface: `adapter/NotificationProvider.java` (klein, 3 methodes — perfecte screenshot voor Interface Segregation + Open-Closed).
  - DI/Liskov: `service/NotificationDispatcher.java` (injecteert `List<NotificationProvider>`).
  - Single Responsibility: laat de package-structuur zien (poller/scheduler/outbox/...).
  - Afdwinging: `NotificationProviderContractTest` in `notification-service/src/test/...`.

---

## Slide 12 — Uitbreidbaarheid & uitzonderingsscenario's

- **Op de slide:** het volledige uitbreid-verhaal + idempotentie/outbox/rollback.
- **Sprekersnotitie:** benadruk dat "nieuwe provider toevoegen" méér is dan een klasse: ook DB CHECK-constraint, registratie-validatie en credentials — en de contract-test bewaakt het. Noem idempotentie (nooit dubbel verzenden) als concreet uitzonderingsscenario.
- **Waar te vinden / screenshot:**
  - Uitbreid-demo (in proza): [`../Tests/testrapport.md`](../Tests/testrapport.md) §4 (de "vijfde provider"-demo).
  - Idempotentie: `outbox/OutboxService.java` (`ON CONFLICT DO NOTHING`) + schema `seen_appointments` in `infra/postgres/init/00_schema.sql`.
  - CHECK-constraint: `infra/postgres/init/00_schema.sql` (`provider_name IN (...)`).
  - ADR-006 (Strategy-pattern) in [`../ADR's/`](../ADR's/).

---

## Slide 13 — Betrouwbaarheid: FMEA

- **Op de slide:** wat FMEA is + één failure mode volledig uitgewerkt (FM-9).
- **Sprekersnotitie:** leg uit dat je 11 faalscenario's vooraf in kaart bracht, elk met maatregel én test. Loop FM-9 helemaal door: risico → outbox (ADR-007) → code → test → risicoreductie 10→1.
- **Waar te vinden / screenshot:**
  - FMEA: [`../FMEA/FMEA_Documentatie.md`](../FMEA/FMEA_Documentatie.md) + Excel [`../FMEA/FMEA_BeunMRS.xlsx`](../FMEA/FMEA_BeunMRS.xlsx). Screenshot de risicomatrix of de koppelingstabel.
  - FM-9 code: `outbox/OutboxService.java` → `writePending()` en `outbox/OutboxRelayJob.java` → `relay()`.

---

## Slide 14 — Betrouwbaarheid: genomen maatregelen

- **Op de slide:** circuit breaker, outbox+relay, retry-backoff, DLX, duplicate guard, watermark/reconciler.
- **Sprekersnotitie:** koppel elke maatregel aan een failure mode uit de FMEA. Dit toont dat de FMEA gerealiseerd is in code, niet alleen op papier.
- **Waar te vinden / screenshot:**
  - Circuit breaker: `service/NotificationDispatcher.java`.
  - Retry-backoff (5→15 min): `scheduler/FailedNotificationRetryJob.java`. Goed screenshot: het `BACKOFF_MINUTES`-array.
  - DLX/durable queues: `infra/rabbitmq/definitions/topology.json`.
  - Duplicate guard / watermark: `reconciler/AppointmentReconciler.java`; schema `seen_appointments`, `sync_watermarks`.

---

## Slide 15 — Betrouwbaarheid: performance, monitoring & verbeterstap

- **Op de slide:** de meetcijfers (166/sec, 100% outbox, ~63 ms, 1,95 s) + de **vóór/ná ON CONFLICT-fix** + Grafana-screenshot.
- **Sprekersnotitie:** dit is de **20-punten-hefboom voor betrouwbaarheid**: laat een concrete verbeterstap met cijfer zien. Vertel het bug-verhaal: notification_log bleef leeg → oorzaak (conflict op index i.p.v. constraint) → fix → 0 naar 518 rijen. Dat is meetbare verbetering.
- **Waar te vinden / screenshot:**
  - Cijfers + bug-verhaal: [`../PerformanceRapport/PERFORMANCE-RAPPORT.md`](../PerformanceRapport/PERFORMANCE-RAPPORT.md) §7. Screenshot de vóór/ná-tabel.
  - De fix in code: `outbox/OutboxService.java` (de `ON CONFLICT (...)`-regel).
  - Grafana: `http://localhost:3000/d/beunmrs-perf` (screenshot live onder load) — of het bestaande `grafana-multiprovider.png` bij het performance-rapport.

---

## Slide 16 — Testen: aanpak & testpiramide

- **Op de slide:** de gelaagde aanpak (unit → security → contract → integratie → operationeel), 129 groen, tooling-overzicht.
- **Sprekersnotitie:** introduceer de testpiramide en waaróm gelaagd: snelle unit-tests onderaan, dure-maar-realistische integratietests bovenaan, plus operationele tests die je met mocks niet kunt aantonen. Zeg dat je nu per laag inzoomt.
- **Waar te vinden / screenshot:** run-bewijs bovenaan [`../Tests/testrapport.md`](../Tests/testrapport.md) — screenshot `Tests run: 129, Failures: 0`. Eventueel een testpiramide-figuur.

---

## Slide 17 — Unit-tests (110)

- **Op de slide:** 110 unit-tests met JUnit 5 + Mockito; wat ze dekken.
- **Sprekersnotitie:** het fundament. Componenten geïsoleerd met mocks, draait in seconden. Noem de herkansing-toevoeging (5 job-/service-testklassen). Geef één concreet voorbeeld, bv. ReminderSchedulerTest die de 24h/1h-berekening tot op de seconde checkt.
- **Waar te vinden / screenshot:** kies een leesbare testklasse, bv. `notification-service/src/test/java/com/openmrs/notification/scheduler/ReminderSchedulerTest.java` of `outbox/OutboxServiceTest.java`. Screenshot een paar `@Test`-methodes.

---

## Slide 18 — Security-tests (9)

- **Op de slide:** 9 security-tests met Spring MockMvc; negatieve scenario's + cross-tenant.
- **Sprekersnotitie:** dit is een **additionele methodiek** (telt voor de 20). Benadruk de cross-tenant isolatie: sleutel A mag nooit naar tenant B resolven — bewijst NFR-1 op de inkomende keten. Ook ThreadLocal-hygiëne (context gewist, ook bij exception).
- **Waar te vinden / screenshot:** `notification-service/src/test/java/com/openmrs/notification/tenant/TenantApiKeyFilterTest.java`. Screenshot de cross-tenant-test.

---

## Slide 19 — Architectuur- / contract-tests (10)

- **Op de slide:** 10 contract-tests (classpath-scan + reflectie) die het provider-extension-point afdwingen.
- **Sprekersnotitie:** **additionele methodiek (architectuur) — sterk punt.** Uitbreidbaarheid is geen belofte in proza maar wordt op build-time afgedwongen: een nieuwe provider zonder `@Component`, met dubbele naam of buiten de CHECK-constraint laat de build falen.
- **Waar te vinden / screenshot:** `NotificationProviderContractTest` in `notification-service/src/test/...`. Screenshot de test die alle 4 providers valideert.

---

## Slide 20 — Integratietests (3)

- **Op de slide:** 3 integratietests met Testcontainers + echte PostgreSQL 16; de volledige keten.
- **Sprekersnotitie:** wat mocks niet kunnen — de echte database met het productieschema. Valideert JSONB, CHECK-constraints, SHA-256 key-lookup én multi-tenant isolatie tegen de DB-grens. Docker-gated → 132 tests mét Docker.
- **Waar te vinden / screenshot:** `EndToEndNotificationFlowTest` in `notification-service/src/test/...`. Screenshot de Testcontainers-output of de test zelf.

---

## Slide 21 — Chaos- & performance-tests (operationeel)

- **Op de slide:** twee scripts tegen de live stack; chaos (NFR-7) + performance (166 notif/sec).
- **Sprekersnotitie:** **20-punten-hefboom: additionele methodieken + meetbaar gedrag.** Chaos bewijst herstel zonder berichtverlies bij een OpenMRS-storing; de loadtest meet de doorvoer. Verwijs naar de FMEA (blok 3) — dit is het bewijs dat de maatregelen werken. Coverage niet op slide; als ze vragen → eerlijk (zie draaiboek blok 4).
- **Waar te vinden / screenshot:** `scripts/circuitbreaker-test.ps1`, `scripts/loadtest.ps1`; cijfers in [`../PerformanceRapport/PERFORMANCE-RAPPORT.md`](../PerformanceRapport/PERFORMANCE-RAPPORT.md). Screenshot Grafana onder load.

---

## Slide 22 — Realisatieverantwoording: ontwikkeltools

- **Op de slide:** de tools-lijst.
- **Sprekersnotitie:** kort per tool waaróm (waarde + kosten). Niet opsommen maar reflecteren: bv. Docker Compose = hele stack in één commando = snelle iteratie.
- **Waar te vinden:** [`../Realisatielogboek/realisatielogboek.md`](../Realisatielogboek/realisatielogboek.md) (D4a).

---

## Slide 23 — Realisatieverantwoording: AI & zelfredzaamheid

- **Op de slide:** AI-inzet, waarde/kosten, en expliciet de zelfredzaamheid.
- **Sprekersnotitie:** dit is belangrijk voor de CGI-reflectie. Zeg: AI deed boilerplate/opmaak, maar de **architectuurkeuzes (ADR's) en de FMEA-scenario's bepaalden wij zelf**; beveiligingsgrenzen en domeinkeuzes corrigeerden we handmatig. Geef één concreet voorbeeld (bv. het ontbrekende `finally`-blok in de tenant-filter dat handmatig is toegevoegd).
- **Waar te vinden:** [`../Realisatielogboek/realisatielogboek.md`](../Realisatielogboek/realisatielogboek.md) (D4b — voorbeelden met prompts + correcties).

---

## Slide 24 — Live demonstratie

- **Op de slide:** de 5 demo-stappen als checklist (zodat het publiek de flow volgt).
- **Sprekersnotitie:** spreek de stappen uit terwijl je ze doet. Verklaar de ~2 min poller-wachttijd met architectuur-uitleg (poller → consumer → cancelReminders). Houd de fallback-opname klaar.
- **Waar te vinden / uitvoeren:** volledig script met commando's en SQL-queries: [`demo-runbook.md`](demo-runbook.md). **Test dit vooraf helemaal door** (pre-flight in het draaiboek §6).

---

## Slide 25 — Afronding: alles aantoonbaar + zelfbeoordeling

- **Op de slide:** de samenvatting (23 requirement-ID's → 33 detailregels ✅, 11 ADR's, FMEA→test, 129 tests, SOLID+Strategy).
- **Sprekersnotitie:** sluit de cirkel naar slide 3: "we beloofden aantoonbaarheid — dit is het bewijs." Zeg expliciet dat je minimaal de 'Goed'-kolom van de rubric haalt en waarom.
- **Waar te vinden:** samenvatting in [`../Traceerbaarheid/traceerbaarheidsmatrix.md`](../Traceerbaarheid/traceerbaarheidsmatrix.md).

---

## Slide 26 — Vragen?

- **Op de slide:** "Vragen?" + teamnaam.
- **Sprekersnotitie:** verwijs vragen naar de matrix-rij + ADR + test. Bereid de Q&A-tabel uit het draaiboek §7 voor — vooral OpenTelemetry, polling-vs-events, multi-tenancy en coverage.
- **Waar te vinden:** Q&A-voorbereiding in [`presentatie-draaiboek.md`](presentatie-draaiboek.md) §7.

---

> Bewerk het deck vanaf nu rechtstreeks in PowerPoint (`BeunMRS-presentatie.pptx`) — gebruik dit document als leidraad per slide.
