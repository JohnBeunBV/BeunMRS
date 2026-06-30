# Samenvatting — alles voor de presentatie (bus-editie)

> Doorlezen onderweg. Alles op één plek: architectuur, terugval-componenten, begrippen, FMEA, tests, demo, lastige vragen en de cijfers om te onthouden.

---

## 1. Wat is het — in één minuut

Een **multi-tenant SaaS-notificatiemodule** die naast OpenMRS draait en patiënten automatisch herinnert aan afspraken via externe messaging providers (SMS). Elke ziekenhuisorganisatie (tenant) heeft een eigen OpenMRS-instantie, eigen sleutels en één gekozen provider.

**Stack:** Java 21 · Spring Boot 3.2 · PostgreSQL 16 · RabbitMQ · Docker Compose · Grafana/Loki/Prometheus · React (Vite).

---

## 2. De keten (architectuur) — hoe een afspraak een SMS wordt

```
OpenMRS  →  Poller  →  RabbitMQ  →  Consumer  →  Scheduler / Dispatcher  →  Provider  →  patiënt
(afspraak)  (elke 2m)   (buffer)    (verwerkt)    (plant 24h/1h + verstuurt)   (SMS)
```

1. **Poller** (`OpenMrsAppointmentPoller`) leest elke **2 min** per tenant de afspraken via REST v1 (`/ws/rest/v1/appointment/search`), venster = 30 dagen vooruit.
2. **Outbox + RabbitMQ**: event wordt eerst in de DB opgeslagen (`outbox_events`), dan op de queue gezet.
3. **Consumer** (`AppointmentEventConsumer`) verwerkt het event: verstuurt direct een bevestiging én plant herinneringen.
4. **Scheduler** (`ReminderScheduler`) plant **24h** + **1h** vóór de afspraak (rijen in `scheduled_notifications`).
5. **DispatchJob** (`ReminderDispatchJob`, elke ~60s) verstuurt herinneringen waarvan de tijd verstreken is.
6. **Dispatcher** (`NotificationDispatcher`) kiest de provider op basis van `tenant.providerName` en verstuurt.

---

## 3. Terugval- & veerkrachtcomponenten (resilience) — KEN DEZE

| Component | Wat het doet | Dekt |
|---|---|---|
| **Transactionele outbox** | Event eerst in DB (`outbox_events`), dán publiceren. Crash tussendoor → niets kwijt. | FM-9, NFR-7 |
| **Outbox relay** (`OutboxRelayJob`, 30s) | Publiceert ongepubliceerde events alsnog; max 5 pogingen → `failed_at`. | NFR-6e |
| **Circuit breaker** | 5 fouten → 2 min pauze per tenant. Beschermt tegen een platte OpenMRS. | FM-2, NFR-7 |
| **Retry-job** (`FailedNotificationRetryJob`) | Mislukte verzending: 3× opnieuw, backoff **5 → 15 min**, dan `permanently_failed`. | FM-5, NFR-6e |
| **Durable queues + DLX** | Queues overleven herstart; verlopen berichten → dead-letter-queue i.p.v. weg. | FM-2, FM-4 |
| **Duplicate guard** (`seen_appointments`) | Per `(appointment_uuid, tenant_id)` — nooit dubbel verwerken. | FM-3, FM-11 |
| **Watermark + reconciler** | `sync_watermarks` cursor per tenant; `AppointmentReconciler` als fallback-poller. | FM-11, NFR-7 |
| **DB-write retry** (`OutboxService`) | INSERT mislukt → 3× opnieuw met 500ms backoff. | FM-1 |
| **Data-retentie** (`DataRetentionJob`, 02:00) | PII na 14 dagen weg; PII-vrije audit 1 jaar. | NFR-10/11 |

---

## 4. De 4 providers (NFR-3)

| Provider | Type | Authenticatie | Bijzonder |
|---|---|---|---|
| **SwiftSend** | REST | `X-API-KEY` header | Rate-limit (429) → failure → retry |
| **SecurePost** | REST | **JWT** (apart token-endpoint) | Token-cache + refresh 30s vóór verloop + 401-vangnet |
| **LegacyLink** | **SOAP/XML** | Basic Auth | Timeout 10s; XML-envelope opbouwen |
| **AsyncFlow** | Async REST | `X-API-KEY` | Twee fasen: command indienen → status pollen |

> Nieuwe provider toevoegen = **één klasse** (Strategy-pattern, ADR-006), afgedwongen door de contract-test. Dat is je Open-Closed-verhaal.

---

## 5. Begrippen (jargon, kort uitgelegd)

- **Multi-tenancy** — één systeem bedient meerdere organisaties. Een **tenant = een OpenMRS-instantie** (eigen `openmrs_host`). In productie heeft elk ziekenhuis zijn eigen OpenMRS.
- **Idempotentie** — een operatie twee keer uitvoeren = hetzelfde effect als één keer. Hier: `ON CONFLICT DO NOTHING` + unique index → nooit dubbel verzonden.
- **Outbox-patroon** — schrijf event + data in **één** DB-transactie, publiceer daarna. Lost het "dual-write"-probleem op (data opgeslagen maar event kwijt bij crash).
- **At-least-once delivery** — elk bericht komt minstens één keer aan (liever dubbel dan kwijt) — vandaar de duplicate guard.
- **Circuit breaker** — na X fouten "open" je het circuit en stop je tijdelijk met proberen, zodat je een kapot systeem niet platbelt.
- **Exponentiële backoff** — wachttijd tussen retries loopt op (5 → 15 min) i.p.v. meteen weer hameren.
- **AES-256-GCM** — sterke symmetrische versleuteling; GCM geeft óók integriteit (een controle-tag → manipulatie wordt gedetecteerd).
- **IV (initialization vector)** — 12 willekeurige bytes per encryptie; zorgt dat dezelfde invoer elke keer andere ciphertext geeft.
- **JWT** — een tijdelijk token (verloopt na ~3 min); we cachen het en vernieuwen proactief.
- **TLS 1.3** — versleuteling van het transport (NGINX); de nieuwste, veiligste versie.
- **Percentielen (p50/p95/p99)** — p50 = mediaan (typisch), p95/p99 = de traagste 5%/1% (de "staart"). Tonen uitschieters die een gemiddelde verbergt.
- **Mock / ArgumentCaptor** — in tests vervang je de DB door een nep-object (mock); een captor "vangt" de waarden die de code aan de mock geeft, zodat je ze kunt controleren.
- **HL7 / FHIR / REST v1** — HL7 = de zorgstandaard voor berichten; FHIR is de moderne variant. Wij gebruiken **REST v1** omdat de FHIR2-module geen Appointment ondersteunt (`HAPI-0302`) — uitgelegd in ADR-003.
- **Poller / watermark** — wij "vragen" OpenMRS elke 2 min om afspraken (polling) i.p.v. webhooks; de watermark onthoudt tot waar we waren.
- **DLX (Dead Letter Exchange)** — RabbitMQ-bestemming voor berichten die verlopen of mislukken, zodat ze niet stilletjes verdwijnen.

---

## 6. FR & NFR — sneloverzicht

**Functioneel:** FR-1 patiënt krijgt afspraakdetails (a: 24h, b: 1h, c: datum/tijd, d: locatie, e: instructies, f: skip reeds-aangevangen, g: annulering stopt reminders, h: wijziging past aan) · FR-2 logging voor factuur · FR-3 één provider per organisatie.

**Niet-functioneel (highlights):** NFR-1 multi-tenant/multi-OpenMRS · NFR-3 4 providers · NFR-4 OpenMRS 2.7.x (REST v1) · NFR-5 AES-256 + TLS 1.3 + geen secrets in code + PII-masking · NFR-6 HL7 (validatie/ack/logging/transformatie/queueing+retry) · NFR-7 zelfstandig + fallback · NFR-8 UTF-8 · NFR-9 monitoring (Micrometer+Prometheus+Loki; **bewust geen OpenTelemetry**, ADR-010) · NFR-10/11 retentie · NFR-13 tijdzones.

> Traceerbaarheid: **23 requirement-ID's → 33 detailregels**, allemaal gekoppeld aan ADR → code → test.

---

## 7. FMEA — 3 sterke voorbeelden om paraat te hebben

- **FM-9** (crash na DB-write, vóór publish) → transactionele outbox (ADR-007). Risico **10 → 1** (90%).
- **FM-6** (SecurePost JWT verloopt) → token-cache + 30s-refresh + 401-retry (ADR-006). Risico **12 → 1** (92%).
- **FM-5** (SwiftSend onbereikbaar/rate-limit 429) → retry met backoff. Risico **12 → 2** (83%).

> 11 failure modes totaal, elk met effect/oorzaak/maatregel + W×I vóór/ná. Volledig in `docs/FMEA/FMEA_Documentatie.md` + Excel.

---

## 8. Tests — de cijfers

**129 geautomatiseerde JUnit-tests, 0 failures** (JDK 24):
- **110** unit (JUnit 5 + Mockito)
- **9** security (Spring MockMvc — auth + cross-tenant isolatie)
- **10** architectuur/contract (provider-extension-point afgedwongen)
- **+ 3** integratie (Testcontainers + echte PostgreSQL) → **132 mét Docker**
- **+** operationeel: chaos-test (`circuitbreaker-test.ps1`) + loadtest (`loadtest.ps1`)

Rekensom: **110 + 9 + 10 = 129**; de 3 integratietests hebben Docker nodig (→ 132).
Coverage (niet op slide): ~80% regels op de kernlogica; eerlijk antwoord als ze vragen.

---

## 9. Demo — snelle volgorde (zie demo-runbook.md voor details)

1. Tenant registreren (portaal) → 2. Patiënt **mét telefoon** (SPA) → 3. Afspraak 2–3 dagen vooruit (SPA) → 4. Reminders `pending` (DBeaver) → **4b. 24h/1h live via "kort-lont"-afspraak** → 5. Annuleren (SPA) → 6. Reminders `cancelled` → 7. Grafana → (8. 2e provider) → (9. Retry bij falen).

**Belangrijk:** geen `down -v` vóór de demo (anders komen de OpenMRS demo-afspraken terug). Patiënt heeft een telefoonnummer nodig.

---

## 10. Lastige vragen + jouw antwoord

| Vraag | Antwoord |
|---|---|
| "Waarom geen OpenTelemetry?" (de opdracht noemt het) | Micrometer + Prometheus + Loki dekt NFR-9 volledig; OTLP-overhead niet gerechtvaardigd voor één service. **Bewuste keuze, ADR-010.** |
| "Waarom polling en geen webhooks?" | Webhooks verliezen events bij downtime; polling + outbox = fallback ingebouwd. AtomFeed vereist Bahmni, FHIR2 ondersteunt geen Appointment (HAPI-0302). ADR-003. |
| "FR-1g zegt geen verdere notificaties, maar jullie sturen een annuleringsbericht?" | We stoppen alle **herinneringen**; de annuleringsbevestiging is een bewuste keuze — anders verschijnt de patiënt voor niets. |
| "Hoe weet het systeem bij welke tenant een afspraak hoort?" | Per OpenMRS-instantie (`openmrs_host`). Elke tenant pollt zijn eigen OpenMRS. In productie = eigen OpenMRS per ziekenhuis. |
| "Is de FMEA echt getest of een belofte?" | Chaos-test (opname) + elke failure mode gekoppeld aan code + test. |
| "Wat is jullie coverage?" | Kernlogica ~80% regels; poller/reconciler/config via integratietest i.p.v. unit-test. Branch coverage = verbeterpunt. |
| "Hoe voeg je een nieuwe provider toe?" | Eén klasse + `@Component` + CHECK-constraint + validatie; contract-test dwingt het af. |

---

## 11. Cijfers om te onthouden

- **166** notificaties/sec (piek) · gemiddeld ~950/min
- **100%** outbox-slagingspercentage (198/198, niets verloren)
- **~63 ms** gemiddelde latency · **1,95 s** opstarttijd
- **129** tests groen (132 mét Docker)
- **23** requirements / **33** detailregels — allemaal aangetoond
- **11** ADR's · **11** FMEA failure modes
- **4** providers · **2** reminders per afspraak (24h + 1h)
- Poller elke **2 min** · dispatch elke **60s** · retentie **14 dagen** PII / **1 jaar** audit

---

## 12. Jouw slides (Wassim): 4, 5, 7, 12, 13, 17

Architectuur (C4) · Functionele eisen · Beveiliging & privacy · Uitbreidbaarheid & idempotentie · **FMEA (FM-6, SecurePost JWT)** · Unit-tests. Details + scripts: `sprekersnotities.md`.

---

## 13. De basis (OOP) — klasse, object, methode, constructor, interface

- **Klasse** = een blauwdruk/sjabloon. Beschrijft welke gegevens (velden) en gedrag (methodes) iets heeft. Bv. `SwiftSendProvider`.
- **Object (instantie)** = een concreet exemplaar van een klasse, gemaakt met `new` (of door Spring). De blauwdruk is het recept, het object is de gebakken taart.
- **Veld / attribuut** = een gegeven dat een object onthoudt (bv. `private String baseUrl;`).
- **Methode** = een handeling/functie van een object (bv. `send(...)`). Heeft eventueel **parameters** (input) en een **return**-waarde (output).
- **Constructor** = een speciale methode met dezelfde naam als de klasse, die draait bij het **aanmaken** van een object. Hierin geef je de afhankelijkheden mee. Bv. `public SwiftSendProvider(RestTemplate rt) { this.restTemplate = rt; }`.
- **Interface** = een **contract**: een lijst methodes zónder uitwerking. Wie de interface "implementeert" móét die methodes invullen. Bv. `NotificationProvider` schrijft `send()`, `providerName()`, `isEnabled()` voor.
- **Abstracte klasse** = half-af klasse die je niet zelf instantieert; bedoeld om van te erven. (Interface óf abstracte klasse = "program to an interface".)
- **Implements / extends** = `SwiftSendProvider implements NotificationProvider` (vult het contract in) · `extends` = erven van een ouderklasse.
- **Encapsulatie** = velden `private` maken en alleen via methodes benaderen — bescherming van de interne staat.

> In één zin: je definieert een **klasse** met **velden** en **methodes**, je maakt er een **object** van via de **constructor**, en via een **interface** maak je klassen uitwisselbaar.

---

## 14. Design-principes & smells (SDK les 1)

**Design for change** — een goed ontwerp laat je makkelijk wijzigen. Lukt dat niet meer, dan "sterft" de software (wijzigen wordt te duur/traag).

**Design smells (alarmbellen):**
- **Rigidity** — één kleine wijziging veroorzaakt een lawine aan andere wijzigingen.
- **Fragility** — bij één aanpassing breekt het op allerlei andere plekken.
- **Complexity** — methodes/klassen te lang, te veel logica op één plek.
- **Opacity** — moeilijk te begrijpen.

**SOLID** (gekoppeld aan ons project):
- **S — Single Responsibility:** één klasse, één reden om te wijzigen. → poller / scheduler / dispatcher / retry-job doen elk één ding.
- **O — Open-Closed:** open voor uitbreiding, dicht voor wijziging. → nieuwe provider = nieuwe klasse, géén bestaande code aanpassen (`NotificationProvider`).
- **L — Liskov Substitution:** elke implementatie is inwisselbaar zonder de gebruiker te breken. → elke provider is uitwisselbaar voor de dispatcher.
- **I — Interface Segregation:** smalle, gerichte interfaces. → `NotificationProvider` heeft maar 3 methodes.
- **D — Dependency Inversion:** afhankelijk van abstracties, niet van concrete klassen. → dispatcher hangt af van de interface; Spring injecteert de implementatie.

**Program to an interface, not an implementation** — werk met het contract (interface), niet met een concrete klasse. Maakt onderdelen uitwisselbaar.

**Design goals** (kunnen tegenstrijdig zijn): modifiability, extensibility, reusability, testability, security, efficiency.

---

## 15. Lagenarchitectuur (SDK les 2 — referentiearchitectuur)

De lessen gebruiken **4 lagen**, elk met een eigen verantwoordelijkheid:

| Laag | Verantwoordelijkheid | In de les | In ons project |
|---|---|---|---|
| **Presentation** | GUI / interactie, zo "dom" mogelijk | Swing-GUI | React-frontend + REST-controllers (`TenantRegistrationController`) |
| **Application Logic** (service/manager) | Coördineert taken, logische checks | Managers | `NotificationDispatcher`, `ReminderScheduler`, services |
| **Domain** | Essentiële info + regels van de context | Entiteiten | model: `AppointmentEvent`, `Tenant`, `NotificationResult` |
| **Data Storage** | Alle kennis over persistentie | DAO's per entiteit | `JdbcTemplate`-queries in `OutboxService` / `TenantService` |

**Verschil dat je eerlijk benoemt:** de les schrijft een aparte **DAO-klasse per entiteit** voor; wij gebruiken **`JdbcTemplate` direct** in de service-klassen (pragmatisch, minder boilerplate). Hetzelfde idee (data-toegang gescheiden), andere uitwerking. De main-klasse die alles handmatig koppelt is bij ons **Spring** (de IoC-container wiret de objecten).

**Ontwerpvolgorde uit de les:** eerst domain → dan application logic → dan presentation/data storage, maar **iteratief** (telkens een werkende verticale slice).

---

## 16. Patterns (SDK les 3 & 4) — uitgelegd + in ons project

- **Tight vs loose coupling** — tight = klasse A maakt zelf `new ConcreteB()` (sterk verbonden). Loose = A kent alleen een interface, de concrete klasse komt van buiten. Loose = makkelijker wijzigen/testen.
- **Dependency Injection (DI)** — een object krijgt zijn afhankelijkheden via de **constructor** aangereikt i.p.v. ze zelf te maken. → wij doen dit overal via Spring constructor-injectie. Dit ís dependency inversion in de praktijk.
- **Factory pattern** — verantwoordelijk voor het **aanmaken** van objecten (creational pattern), schermt af welke concrete klasse het is, retourneert als interface. Een factory zelf is "strongly coupled" (kent de concrete klassen) — maar je beperkt dat tot één plek.
  - **Simple Factory:** één klasse met een `switch` op een parameter die het juiste object maakt.
  - **Abstract Factory:** een factory-interface met meerdere concrete factories (bv. `SQLDAOFactory` vs `TestDataDAOFactory`) — voldoet aan Open-Closed (nieuwe bron = nieuwe factory, geen `switch` aanpassen).
  - In ons project: `RestTemplateFactory` maakt een per-tenant HTTP-client. En **Spring's container is in feite één grote factory** die alle beans maakt en koppelt.
- **Strategy pattern** (ons hoofdpattern) — meerdere uitwisselbare algoritmes achter één interface; je kiest er op runtime één. → `NotificationProvider` met 4 implementaties; de dispatcher kiest op basis van `tenant.providerName`. (De les focust op Factory; wij combineren Strategy + Spring-als-factory.)
- **DAO / Repository** — een klasse die alle database-toegang voor één entiteit afhandelt (met de SQL erin). ORM-tools (JPA/Hibernate) automatiseren dit; een DAO heet dan een CRUD-repository.

> **Designpatterns-categorieën:** Creational (objectcreatie, bv. Factory) · Structural (klassen samenstellen) · Behavioral (gedrag/communicatie, bv. Strategy).

---

## 17. SDLC — hoe we dit project hebben aangepakt

Iteratief/agile, in **3 sprints**. Per SDLC-fase:

| Fase | Wat we deden | Artefact |
|---|---|---|
| **Analyse / Requirements** | Opdracht ontleed naar FR/NFR, gemapt naar implementatie | Compliance matrix (CLAUDE.md), traceerbaarheidsmatrix |
| **Ontwerp** | Architectuurkeuzes met alternatieven; faalanalyse | ADR-001…011, C4 L1/L2/L3, FMEA |
| **Implementatie** | Spring Boot, in fases (poller → outbox → reminders → multi-tenant → hardening) | Codebase, git-historie |
| **Testen** | Testpiramide: unit → security → contract → integratie → operationeel | 129 tests, testrapport |
| **Deployment** | Volledige stack reproduceerbaar | `docker compose up` |
| **Onderhoud / evolutie** | Uitbreidbaarheid (nieuwe provider), retentie, monitoring | Strategy-pattern, `DataRetentionJob`, Grafana |

**Reflectie (voor de CGI):** de fases liepen niet strikt na elkaar maar **iteratief** — elke sprint leverde een werkende verticale slice (bv. "afspraak → reminder → verzonden"). AI-tooling (Claude) versnelde boilerplate/opmaak; de **ontwerpkeuzes (ADR's) en risicoanalyse (FMEA) deden we zelf**, met handmatige correcties op security en domeinlogica. Leerpunt: behoud van zelfredzaamheid door AI-output altijd te toetsen aan de eisen.

---

## 18. Een ánder ontwerp beoordelen (examen-/CGI-skill)

De docent toont een alternatieve uitwerking; jij benoemt **één voordeel en één nadeel** van jouw ontwerp t.o.v. dat alternatief, voor een gegeven NFR.

**Aanpak in 3 stappen:**
1. Kies het **kwaliteitsattribuut** dat telt (betrouwbaarheid, schaalbaarheid, modifiability, security…).
2. Benoem **één concreet voordeel** van jouw keuze op dat attribuut.
3. Benoem **één eerlijk nadeel** (dat scoort juist punten — toont inzicht).

**Concrete vergelijkingen die je paraat hebt:**

| Alternatief | Voordeel van ons ontwerp | Nadeel van ons ontwerp |
|---|---|---|
| **Webhooks** i.p.v. polling | Geen events kwijt bij downtime (poller haalt achterstand op) | Tot 2 min vertraging; meer load op OpenMRS |
| **Synchroon versturen** i.p.v. outbox + queue | Geen berichtverlies bij crash; ontkoppeld van provider-storingen | Complexer; bericht komt iets later aan (eventual) |
| **Database-per-tenant** i.p.v. shared-schema + `tenant_id` | Eenvoudiger beheer/kosten bij veel tenants | Minder harde fysieke isolatie (afhankelijk van correcte scoping) |
| **Ingebouwde OpenMRS-module** i.p.v. zelfstandige service | Multi-tenant SaaS, los van OpenMRS-release-cyclus | Extra infra (eigen service, queue, DB) om te beheren |
| **`if/else` op provider** i.p.v. Strategy-interface | Nieuwe provider zonder bestaande code te raken (Open-Closed) | Iets meer klassen/abstractie vooraf |
| **OpenTelemetry** i.p.v. Micrometer+Prometheus | Lichter, minder overhead voor één service | Mist gedistribueerde tracing (niet nodig hier) |

**Toekomstige ontwikkeling** (de docent kan vragen hoe je iets nieuws inpast): door de **event-driven opzet + Strategy-pattern** voeg je een nieuw kanaal (bv. WhatsApp) of een nieuwe module toe met een nieuwe provider-klasse of een extra RabbitMQ-routing-key — zonder de kern te herontwerpen. Dát is je "design for change"-bewijs.

---

## 19. Hoe de 4 providers werken (in detail)

**Gemeenschappelijk:** alle vier implementeren `NotificationProvider` (`send()`, `providerName()`, `channel()`), bouwen dezelfde berichttekst via `MessageHelper` (per eventtype: bevestiging / wijziging / annulering / 24h / 1h), en geven een `NotificationResult` (ok of failure) terug. Dat is het Strategy-pattern: vier verschillende uitwerkingen achter één contract.

### SwiftSend — REST, het simpelst
- **Auth:** `X-API-KEY`-header (de sleutel van de tenant) + `X-STUDENT-GROUP`.
- **Flow:** één POST naar `/swiftsend` met JSON `{type:"SMS", recipients:[telefoon], content:bericht}` → bij 2xx krijg je een `messageId` terug → `ok`.
- **Resilient:** vangt **HTTP 429 (rate limit)** apart op → `failure` → de retry-job pakt het later op (FMEA **FM-5**).
- **Eén zin:** synchroon versturen met een API-sleutel; netjes omgaan met te-veel-verzoeken.

### SecurePost — REST met JWT (twee stappen)
- **Auth:** eerst een **token** halen: POST `/securepost/auth` met `{clientId, clientSecret}` → `{accessToken, expiresIn}`. Daarna pas het bericht: POST `/securepost/message` met `Bearer <token>`.
- **Slim:** token wordt **gecachet per clientId** en **30s vóór verloop** vernieuwd; krijg je tóch een **401**, dan token weggooien + één keer opnieuw (FMEA **FM-6**).
- **Eén zin:** authenticatie met een verlopend token dat we proactief vernieuwen — provider beheert z'n eigen auth.

### LegacyLink — SOAP (ouderwets), Basic Auth
- **Auth:** **Basic Auth** (gebruikersnaam:wachtwoord, Base64) in de `Authorization`-header.
- **Flow:** POST naar `/LegacyLink/SendSms` met een **XML SOAP-envelope** (`<SendSmsRequest>` met `<PhoneNumber>`, `<MessageText>`, `<SenderIdentification>`), Content-Type `text/xml`. Het antwoord is XML; we lezen `<MessageReference>` eruit als message-id.
- **Detail:** de XML wordt met de hand opgebouwd, inclusief `xmlEscape` (`&`, `<`, `>`) tegen kapotte XML.
- **Eén zin:** een ouder XML/SOAP-systeem — bewijst dat onze adapterstructuur ook niet-JSON-protocollen aankan (NFR-6d, berichttransformatie).

### AsyncFlow — asynchroon, twee fasen (submit + poll)
- **Auth:** `X-API-KEY`.
- **Fase 1 (indienen):** POST `/asyncflow` met `{destination, content, priority}` → je krijgt een **`trackingId` (commandId)** terug. Dit ID wordt **eerst in `async_flow_commands` opgeslagen** (status `pending`) — zo gaat niets verloren bij een crash. De `send()` geeft direct `pending:<id>` terug; het is dus nog níet bevestigd.
- **Fase 2 (pollen):** een aparte `@Scheduled`-job (elke **10s**) haalt alle `pending` commands op en doet GET `/asyncflow/{commandId}` → status `completed` / `failed` / `pending`. Bij `completed` → `notification_log` naar `sent`; bij `failed` → `failed`.
- **Eén zin:** "vuur en vergeet, check later" — het ID wordt persistent bewaard en periodiek gepolld tot er een eindstatus is (FMEA **FM-8**).

> **Mooi punt voor je verhaal:** deze vier dekken bewust **vier verschillende integratiestijlen** — simpele REST, REST met token-auth, ouderwets SOAP/XML, en asynchroon submit-poll. Dat ze allemaal achter dezelfde interface zitten, bewijst de uitbreidbaarheid (Open-Closed, Strategy).
