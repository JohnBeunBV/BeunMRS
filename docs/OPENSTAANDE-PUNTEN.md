# Herkansing — Actieplan & openstaande punten

**Nieuwe deadline: 26 juni 2026.** Dit document vertaalt de docentfeedback (groep 33/100, CGI 25/100 — beide onder de vereiste 55) naar concrete acties. Alles is geordend op **waar we punten verloren**, niet op algemene hygiëne.

> **Kernregel voor de herkansing:** de docent gaf twee keer een **0**. Daar moeten de punten vandaan komen. Alles wat al "voldoende" scoorde (Duurzaam ontwerp, Test, Realisatie 11/20; CGI-reflectie 25/40) is bijzaak — niet daarin investeren tenzij het de nullen helpt.

---

## Wat er herkanst moet worden

### Groep — Beroepsproduct (33/100)
| Criterium | Score | Status |
|---|---|---|
| **Architectuurbeschrijving & bedrijfsprocessen** | **0/20** | 🔴 MOET omhoog |
| **Betrouwbaarheid** | **0/20** | 🔴 MOET omhoog |
| Duurzaam ontwerp | 11/20 | 🟢 voldoende |
| Testresultaten | 11/20 | 🟢 voldoende |
| Realisatieverantwoording | 11/20 | 🟢 voldoende |

### CGI — individueel (25/100, dit is **per persoon**)
| Criterium | Score | Status |
|---|---|---|
| **Duurzaamheid van het ontwerp** | **0/30** | 🔴 MOET omhoog |
| **Representatie van het testen** | **0/30** | 🔴 MOET omhoog |
| Reflectie SDLC + AI | 25/40 | 🟢 voldoende |

---

## Letterlijke docentfeedback → oorzaak → actie

### Groepsfeedback (geciteerd)

> *"We missen de traceerbaarheid vanuit de requirements naar jullie ontwerp en realisatie. Ook de relatie met het testen ontbreekt."*

- **Oorzaak (1e poging):** geen traceerbaarheidsmatrix.
- **Status nu:** ✅ `docs/Traceerbaarheid/traceerbaarheidsmatrix.md` bestaat (23 requirements → ADR → code → test → status).
- **Resterende actie:** deze matrix moet **centraal in de presentatie** staan — de docent zei dat het ontbreken óók in de presentatie terugkwam. Zie [Deel C](#deel-c--presentatie-de-nullen-worden-mondeling-verloren).

> *"Informatie is niet consistent: de verschillende niveau's van het C4-model."*

- **Status nu:** C4 L1/L2/L3 bestaat en is intern grotendeels consistent. **MAAR** er is een concrete inconsistentie elders die exact deze kritiek voedt → zie [Deel D, punt D1](#d1--adr-mapverwijzing-klopt-niet-consistentie-bug).
- **Resterende actie:** C4 visueel nalopen op consistentie tussen niveaus (containers in L2 = componenten-groepen in L3 = actoren in L1). Frontend staat in L2 maar niet als actor-context in L1 — bewust uitleggen of toevoegen.

> *"Jullie hebben meer architectuurbeslissingen, toch?"*

- **Oorzaak (1e poging):** slechts ADR-001 t/m 004.
- **Status nu:** ✅ ADR-001 t/m ADR-010.
- **Resterende actie:** controleer of élke niet-triviale keuze een ADR heeft (zie [D2](#d2--ontbrekende-adrs-controleren)). En: ADR's moeten **alternatieven mét afwijscriteria** bevatten — dat is de 20-punts-eis. ADR-003 doet dit goed; controleer dat 001/002/004–010 dit óók doen.

> *"De FMEA is dun uitgewerkt en de doorwerking is niet terug te zien in de architectuur, realisatie en testen."*

- **Status nu:** ✅ Sterk verbeterd — `FMEA_Documentatie.md` heeft 11 failure modes met W×I scores vóór/ná + ADR→klasse→test per FM.
- **Resterende actie:** de **doorwerking moet bewijsbaar** zijn, niet aspiratie. Verifieer dat élke genoemde test écht bestaat én het faalscenario test (zie [D3](#d3--fmea-claims-verifiëren-bewijs--belofte)). Meerdere FM's verwijzen nu naar "handmatig" / "operationeel te verifiëren" — dat is precies "doorwerking niet in de testen". Maak er waar mogelijk een geautomatiseerde test van.

> *"Toon aan welke requirements daadwerkelijk behaald zijn en hoe je dat weet."*

- **Status nu:** ✅ Traceerbaarheidsmatrix "Status"-kolom (20 ✅ bewezen, 3 ⚠️ handmatig).
- **Resterende actie:** de 3 ⚠️'s afmaken (NFR-4, NFR-8, NFR-9a) zodat je in de presentatie kunt zeggen "23/23 aantoonbaar" → zie [Deel B](#deel-b--de-3-handmatige-verificaties-afmaken).

### CGI-feedback (persoonlijk, geciteerd)

> *"Multitenancy/SaaS niet goed begrepen."*
> *"Uitbreiden met een nieuwe provider, geen goed beeld hoe dat moet buiten klasse aanmaken."*
> *"Systematische aanpak te mager."*

→ Dit zijn **kennisgaten in het mondeling**, geen documentgaten. Niet op te lossen met schrijven — alleen met **begrijpen + oefenen**. Zie [Deel E — CGI-voorbereiding](#deel-e--cgi-voorbereiding-individueel--hier-liggen-jouw-punten).

---

## Deel A — De twee groep-nullen omhoog

### A1 🔴 Architectuurbeschrijving (0/20 → doel 11–20)

**Wat de rubric wil voor 11:** FR/NFR toelichten op basis van het ontwerp + code-voorbeelden. Voor 20: + overwogen alternatieven met afwijscriteria.

**Wat we hebben:** traceerbaarheidsmatrix + 10 ADR's + C4. De *inhoud* is er. We zakten op **overtuigend kunnen toelichten**.

**Acties:**
1. Maak een **presentatie-rode draad** die één requirement end-to-end volgt, live: bijv. **FR-1g (annulering stopt reminders)** → ADR-004 (waarom RabbitMQ/event-driven) → `AppointmentEventConsumer.onCancellation()` → `cancelReminders()` → `AppointmentEventConsumerTest` → demo live een afspraak annuleren en `status='cancelled'` tonen. Dit is *de traceerbaarheid die ze misten*, live gedemonstreerd.
2. Doe hetzelfde voor één NFR met een **afgewogen alternatief**: **NFR-2a integratie** → ADR-003 toont database-koppeling vs webhook vs polling+RabbitMQ, met criteria (betrouwbaarheid bij downtime, schaalbaarheid, koppeling). Dit pakt de 20-punts-eis.
3. Zorg dat elke spreker minstens één code-voorbeeld op het scherm kan tonen.

### A2 🔴 Betrouwbaarheid (0/20 → doel 11–20)

**Wat de rubric wil voor 11:** FMEA die overeenkomt met code + architectuur, plus performancerapportage + realtime monitoring. Voor 20: + welke test- en verbeterstappen de performance/robuustheid hebben verbeterd.

**Wat we hebben:** FMEA (11 modes), `PERFORMANCE-RAPPORT.md` (166 notif/sec), Grafana/Prometheus/Loki, chaos-test.

**Acties:**
1. **FMEA-doorwerking bewijzen** (zie D3): per failure mode live de keten tonen — risico → architectuurmitigatie → code → test die het faalscenario aantoont. De chaos-test (`circuitbreaker-test.ps1`) is hét bewijsstuk: storing → outbox buffert → herstel zonder verlies.
2. **Realtime monitoring live tonen** in de demo: Grafana-dashboard `beunmrs-perf` open tijdens een loadtest. Niet alleen een screenshot — live.
3. **"Verbeterstappen" voor de 20:** documenteer minstens één concrete verbeter-iteratie. Voorbeeld: "circuit breaker toegevoegd ná observatie dat poller OpenMRS-uitval niet opving" of "outbox-retry van 3→5 pogingen na loadtest-observatie". Een vóór/ná-cijfer maakt dit hard. Leg dit vast in `PERFORMANCE-RAPPORT.md` onder een kop "Test- en verbeterstappen".

---

## Deel B — De 3 handmatige verificaties afmaken

Deze staan als ⚠️ in de traceerbaarheidsmatrix. Afmaken = "23/23 aantoonbaar" in de presentatie.

### B1 🟡 NFR-4 — OpenMRS 2.7.x
Zie [D2](#d2--ontbrekende-adrs-controleren)/onderbouwing. Snelste route: korte motivatie-notitie dat we uitsluitend `/ws/rest/v1/`-endpoints gebruiken (stabiel sinds 2.x) en dat de poller-laag uitwisselbaar is (ADR-003). Eventueel ADR-011. **Inspanning: ~1 u.**

### B2 🟡 NFR-8 — UTF-8 testbericht
Maak een afspraak met Arabische/Chinese `comments`, volg door de stack, screenshot van intacte tekst in `notification_log` + provider-payload. Leg vast in README-beheerder + `docs/Tests/`. **Inspanning: ~1 u.**

### B3 🟡 NFR-9a — Grafana dashboard-bewijs
Draai `scripts\loadtest.ps1 -Scenario stress`, screenshot dashboard (messages/min, errors, retries, per-provider latency), toevoegen aan `docs/PerformanceRapport/`. **Inspanning: ~1 u.**

---

## Deel C — Presentatie (de nullen worden mondeling verloren)

De docent: *"In de presentatie zien we dit ook terug."* De documenten zijn nu goed; de **presentatie moet ze laten zien**.

**Acties:**
1. Maak `docs/presentatie-draaiboek.md` met tijdsindeling (30 min) waarin **traceerbaarheid en FMEA-doorwerking de hoofdmoot zijn**, niet een feature-tour.
2. Verplichte onderdelen in de presentatie:
   - Eén requirement **live** van eis → ADR → code → test → demo (zie A1).
   - FMEA **live**: één failure mode + de chaos-test als bewijs (zie A2).
   - Traceerbaarheidsmatrix als **één overzichtsslide**: "23 requirements, 20 geautomatiseerd bewezen, 3 handmatig geverifieerd."
   - Realtime Grafana tijdens een loadtest.
3. Verdeel sprekers zo dat ieder een stuk pakt dat hij/zij ook in de CGI moet kunnen verdedigen.
4. **Oefen de 15 min vragen**: bereid antwoorden voor op "hoe weten jullie dat NFR-X behaald is?" (→ wijs naar matrix-rij + test).

---

## Deel D — Consistentie & verificatie (raakt "informatie niet consistent")

### D1 ✅ ADR-map-verwijzing gecorrigeerd (consistentie-bug)
~~De traceerbaarheidsmatrix en `CLAUDE.md` verwezen naar `docs/ADR 1 - 4/`, maar de map heet `docs/ADR's/`.~~ **Opgelost (2026-06-25):** alle verwijzingen in `README.md`, `CLAUDE.md` en de traceerbaarheidsmatrix wijzen nu naar `docs/ADR's/`, en de ADR-telling is bijgewerkt naar ADR-001 t/m ADR-011.

### D2 ✅ ADR-volledigheid gecheckt
~~Controleer ook dat elke ADR een "Overwogen opties + afwijscriteria"-sectie heeft (20-punts-eis).~~  
**Opgelost (2026-06-25):** alle 11 ADRs nagelopen op alternatieven + afwijscriteria.
- ADR-001/003/004/005/006/007/008/009/010/011: hadden al volledige opties + afwijscriteria.
- **ADR-002 herschreven:** per technologielaag (backend, queue, database) nu expliciete Optie 1/2/3-structuur met voordelen, nadelen en "Afgewezen omdat"-conclusie. TLS en circuit breaker zijn gedekt in ADR-011 resp. ADR-003/007.

### D3 ✅ FMEA-claims verifiëren (bewijs ≠ belofte)
~~Dit is het hart van de betrouwbaarheids-feedback. **Elke test die FMEA/traceability noemt moet écht bestaan en het scenario testen.**~~  
**Opgelost (2026-06-25):** alle 11 FM's nagelopen tegen de echte testklassen.
- FM-1: 2 tests toegevoegd aan `OutboxServiceTest` (`recordResult_dbFailsOnce_retriesAndSucceeds`, `recordResult_dbFailsAllRetries_doesNotThrow`) — retry-loop bewezen, tests groen (9/9).
- FM-3: hergeformuleerd naar `EndToEndNotificationFlowTest` (echte PostgreSQL + `seen_appointments` PRIMARY KEY).
- FM-11: hergeformuleerd naar `EndToEndNotificationFlowTest` (zelfde reden als FM-3).
- FM-2/4/5/6/7/8/9/10: geverifieerd — claims kloppen of zijn eerlijk als operationeel vermeld.

### D4 ✅ Repo-hygiëne gecheckt
~~Verplicht: geen libraries/temp/secrets.~~ **Opgelost (2026-06-25):** alle checks groen.
- `.gitignore` dekt `**/target/`, `node_modules/`, `.env` ✅
- `git ls-files` bevat geen `target/`, `node_modules/`, `.env` — alleen `.env.example` gecommit ✅
- `DB_ENCRYPTION_KEY` heeft lege default (`${DB_ENCRYPTION_KEY:}`) — veilig ✅
- `SAAS_ADMIN_KEY` heeft default `admin-secret` in `application.yml:37` — bekende valkuil (gedocumenteerd in `CLAUDE.md`), acceptabel voor demo; productie: zet `SAAS_ADMIN_KEY` env var ✅

### D5 ✅ D4c — commit-tabel bijgewerkt
~~Het realisatielogboek heeft nog een placeholder-tabel.~~ **Opgelost (2026-06-25):** tabel in `docs/Realisatielogboek/realisatielogboek.md` bijgewerkt met echte git-cijfers en placeholder-waarschuwing verwijderd.

| Teamlid | Commits |
|---|---|
| Wassim Balouda (Wasssiimm) | 28 |
| Thijs van de Veen (Dice-cmd) | 16 |
| Storm Kroonen (S.k2004) | 10 |
| Nick de Rooij (NickdeRooij) | 4 |
| **Totaal** | **58** |

> ⚠️ **Vóór inlevering bijwerken:** commit-aantallen opnieuw uitvoeren zodra alle herkansingswerk gecommit is. Commando: `git log --format="%an" | Sort-Object | Group-Object | Select-Object Count, Name | Sort-Object Count -Descending`

---

## Deel E — CGI-voorbereiding (individueel — hier liggen JOUW punten)

> De CGI is **persoonlijk** en **mondeling**. Jij scoorde 0/30 op Duurzaamheid en 0/30 op Testen. Dit is **niet** met documenten op te lossen — alleen met begrijpen en hardop oefenen. Dit is veruit je grootste puntenwinst (van 25 naar 55+ vereist minstens +30).

### E1 🔴 Duurzaamheid van het ontwerp (0/30) — wat je moet kunnen

**De docent vraagt twee dingen:**
1. *Vergelijk een aangereikt alternatief ontwerp met het jouwe — voor- én nadeel voor een gegeven NFR.*
2. *Schets-toekomstige OpenMRS-ontwikkeling — hoe werk je dat uit vanuit het huidige ontwerp, incl. herontwerp?*

**Feedback zei: "Multitenancy/SaaS niet goed begrepen."** Bereid voor dat je deze concepten **diepgaand** kunt uitleggen:

- **Wat maakt dit SaaS/multi-tenant?** Eén draaiende instantie bedient meerdere ziekenhuizen; elke tenant heeft eigen OpenMRS-host, eigen (AES-256 versleutelde) credentials, eigen provider, eigen API-key (SHA-256 lookup), eigen tijdzone. Isolatie via `tenant_id` op élke query + `TenantContext` (ThreadLocal) die de tenant door de hele request/job-lifecycle draagt.
- **Alternatief dat ze kunnen voorleggen:** *database-per-tenant* i.p.v. *shared-schema met tenant_id-kolom*.
  - Jouw keuze (shared schema): **voordeel** = eenvoudiger beheer, goedkoper, één migratie; **nadeel** = isolatie is applicatielogica (een vergeten `WHERE tenant_id` lekt data), "noisy neighbor" risico.
  - Database-per-tenant: voordeel = harde isolatie; nadeel = N migraties, duurder, complexer onboarden.
  - Dit staat in ADR-005 — **lees die en kun je hem navertellen**.
- **Toekomstige ontwikkeling-voorbeeld om te oefenen:** "OpenMRS gaat FHIR2 Appointment ondersteunen" → jouw antwoord: het `AppointmentEvent`-model is provider-agnostisch (ADR-003), dus alleen de **poller-laag** vervangen, rest ongewijzigd. Of: "een tweede bedrijfsproces (bijv. labuitslagen) wil notificaties" → nieuwe RabbitMQ routing key + consumer, dispatcher/providers ongewijzigd (NFR-12).

### E2 🔴 Provider-uitbreiding — volledig verhaal (feedback: "geen goed beeld buiten klasse aanmaken")

Het testrapport demo zegt "maak één klasse, klaar" — **dat is precies waarom je zakte: het is incompleet.** Het volledige verhaal dat je moet kunnen vertellen:
1. Nieuwe klasse `QuickSmsProvider implements NotificationProvider` + `@Component` (Spring auto-detectie).
2. **DB CHECK-constraint** op `tenants.provider_name` uitbreiden — anders kan geen tenant hem kiezen.
3. **Registratie-validatie** (`TenantRegistrationController`) accepteert de nieuwe naam.
4. **Credentials**: welk auth-model heeft de provider? (header/JWT/SOAP/async) — dat bepaalt de `send()`-implementatie en welke `ProviderCredentials`-velden nodig zijn.
5. **`NotificationProviderContractTest`** dekt hem automatisch (uniqueness, @Component, schema-match) — leg uit dat dit de uitbreiding *afdwingt*, niet alleen toelaat.
6. Wat **niet** wijzigt: dispatcher, outbox, consumer, scheduler (Open/Closed-principe).
- Dit is het Strategy-pattern (ADR-006). **Lees ADR-006 en oefen dit als 2-minuten-verhaal met het whiteboard.**

### E3 🔴 Representatie van het testen (0/30) — wat je moet kunnen

**De docent vraagt:** testaanpak toelichten, kritisch evalueren, koppelen aan FMEA.

**Oefen dit verhaal:**
- **De testpiramide** (ADR-009): 87 unit (geïsoleerd, Mockito) → 9 security (MockMvc, HTTP-grens) → 10 contract (classpath-scan, dwingt architectuur af) → 3 integratie (Testcontainers, echte Postgres) → 2 operationeel (load + chaos).
- **Concreet voorbeeld** dat je kunt navertellen: `EndToEndNotificationFlowTest` valideert wat mocks niet kunnen — schema, JSONB, CHECK-constraints, SHA-256 lookup samen.
- **FMEA-koppeling** (dit miste expliciet): "FM-9 crash na DB-write → mitigatie outbox-patroon (ADR-007) → code `OutboxService.writePending()` → bewijs `OutboxServiceTest` + chaos-test 100% outbox success." Kun je dit voor 2–3 failure modes uit je hoofd?
- **Kritische evaluatie (voor de hoge score):** benoem zélf de zwaktes — bijv. `alreadyProcessed()` LIKE-query niet geïndexeerd; integratietests skippen als Docker ontbreekt; sommige FMEA-mitigaties alleen handmatig getest. Verbeterpunt erbij. **Zelf zwaktes benoemen scoort hoger dan ze verdedigen.**

### E4 🔴 Systematische aanpak + SDLC-reflectie (reflectie is 25/40 — vasthouden/verhogen)
- Bereid per **SDLC-fase je eigen bijdrage** voor (analyse/ontwerp/realisatie/test), gekoppeld aan de D4c-commits.
- Per fase **één AI-voorbeeld**: de prompt, de output, en **wat jij corrigeerde** (de correctie bewijst zelfredzaamheid — feedback zei "veel AI gebruikt", dus benadruk je eigen oordeel).
- Bereid het antwoord voor op "(on)afhankelijkheid vs zelfredzaamheid" — dat is de 40-punts-zin.

### E5 🟢 Bekend verbeterpunt — reverse-proxy-consolidatie (CGI-munitie)

> Niet implementeren vóór inlevering (werkende stack niet riskeren) — wél paraat hebben als herontwerp-voorbeeld.

- **Observatie:** er zijn **twee** NGINX-reverse-proxies naar dezelfde backend. UI-API-verkeer loopt via de frontend-NGINX (`notification-frontend`), technische API-toegang via `notification-nginx`. Daardoor dekken security-policies op `notification-nginx` (TLS, headers, rate-limiting) het UI-verkeer **niet** uniform.
- **Herontwerp:** consolideren naar **één ingress** die de SPA serveert én alle `/api/`-toegang afhandelt → uniforme afdwinging van NFR-5b (TLS 1.3) en NFR-2c (security best practices), simpeler beheer, schonere C4-containerplaat.
- **Vastgelegd in:** [`ADR-011`](ADR's/ADR-011-reverse-proxy-topologie.md) (huidige opzet + alternatieven + besluit).
- **Waarom relevant:** raakt drie rubric-onderdelen tegelijk — groep-Architectuur (alternatief + criterium), CGI-Duurzaamheid (herontwerp toelichten), en NFR-2c/5b security.

---

## Voorgestelde 2-dagen planning (deadline 26 juni)

**Dag 1 — bewijs hard maken (groep-nullen):**
- [x] D1 ADR-map-verwijzing fixen (15 min, iedereen profiteert)
- [x] D3 FMEA-claims verifiëren tegen echte tests — **belangrijkste groep-taak** (Wassim)
- [ ] B1/B2/B3 de 3 ⚠️-verificaties afmaken (verdeel: Thijs NFR-4+8, Storm NFR-9a)
- [ ] A2 "test- en verbeterstappen" sectie in performance-rapport (Storm)
- [x] D2 ADR-volledigheid + alternatieven-secties checken (Nick)
- [x] D5 commit-tabel uit git log (Thijs, 30 min)

**Dag 2 — presentatie + CGI:**
- [ ] C presentatie-draaiboek met traceerbaarheid + FMEA als hoofdmoot (allen)
- [ ] A1 één requirement live end-to-end uitwerken voor demo (allen)
- [x] Repo-hygiëne D4 (Wassim)
- [ ] **Ieder individueel: Deel E doorwerken en hardop oefenen** — ADR-005 (multi-tenant), ADR-006 (provider-uitbreiding), testpiramide + FMEA-koppeling. Oefen met elkaar als examinator.

---

## Wat AL klaar is (niet meer aanraken)
- ✅ Werkende applicatie, alle FR/NFR geïmplementeerd
- ✅ Traceerbaarheidsmatrix (23 req → ADR → code → test)
- ✅ 10 ADR's (was 4) incl. apart-component + observability
- ✅ FMEA 11 modes met W×I + ADR/code/test-koppeling
- ✅ C4 L1/L2/L3 + procesvisualisatie
- ✅ 109 JUnit-tests + load/chaos-scripts + performance-rapport
- ✅ Realisatielogboek D4a/D4b

---

_Laatst bijgewerkt: 2026-06-25. Werk bij naarmate punten worden afgerond._
