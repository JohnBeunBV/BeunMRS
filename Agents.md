# LU1 Applicatie Integratie — Opdracht & Rubrics

Groep: **Klas B 7** · Inleverdatum: **25 mei 2026 23:59**

---

## Inhoudsopgave

1. [Opdracht](#opdracht)
2. [Toetsopzet](#toetsopzet)
3. [Verplichte onderdelen (inlevering)](#verplichte-onderdelen-inlevering)
4. [Beroepsproduct rubric (groep)](#beroepsproduct-rubric-groep)
5. [CGI rubric (individueel)](#cgi-rubric-individueel)
6. [Presentatierichtlijnen](#presentatierichtlijnen)

---

## Opdracht

### Context

De platformen voor berichtenverkeer verschillen sterk per regio. In China worden andere systemen gebruikt (zoals Baidu) dan in Europa (zoals WhatsApp). Door het ontbreken van standaardisatie is voor elk platform een aparte koppeling nodig.

De opdrachtgever wil de communicatiemodule als zelfstandig product aanbieden aan OpenMRS-organisaties wereldwijd. Door het als SaaS te positioneren kunnen organisaties zich abonneren op de dienst zonder zelf infrastructuur te beheren.

### Doelstelling

Ontwerp en implementeer een Software-as-a-Service communicatiemodule die notificaties kan versturen voor OpenMRS-organisaties via externe messaging providers. De module dient te voldoen aan de HL7-standaarden volgens de FHIR-specificatie en configureerbaar en uitbreidbaar zijn.

### Functionele eisen

| # | Eis |
|---|-----|
| FR-1 | Patiënt ontvangt bericht met afspraakdetails (tijd, locatie, instructies) |
| FR-1a | Notificatie 24 uur voor de afspraak |
| FR-1b | Notificatie 1 uur voor de afspraak |
| FR-1c | Bevat datum en tijd |
| FR-1d | Bevat locatie (polikliniek/kamer) |
| FR-1e | Bevat specifieke instructies (nuchter, medicijnen) |
| FR-1f | Geen notificatie voor reeds aangevangen afspraken |
| FR-1g | Bij annulering: geen verdere notificaties |
| FR-1h | Bij wijziging: tijdstippen aanpassen |
| FR-2 | Logging per notificatie (organisatie, provider, status) voor factuurcontrole |
| FR-3 | Eén messaging provider per organisatie |

### Niet-functionele eisen

| # | Eis |
|---|-----|
| NFR-1 | Zelfstandig functioneren, integratie met meerdere OpenMRS-instanties (multi-tenant) |
| NFR-2 | Integratie passend bij doelstelling, gedocumenteerd voor beheerders, beveiligd |
| NFR-3 | Ondersteuning voor alle 4 providers: SwiftSend, LegacyLink, AsyncFlow, SecurePost |

### Messaging Providers

| Provider | Type | Authenticatie |
|----------|------|---------------|
| SwiftSend | REST API | `X-API-KEY` header |
| SecurePost | REST API | JWT (apart token-endpoint) |
| LegacyLink | SOAP API | Basic Auth |
| AsyncFlow | Async REST | Submit command, poll status |

**Lokale testomgeving:**
```
docker run -p 1337:8080 ghcr.io/avansict/in2.4-fakecomworld:main
```
Documentatie beschikbaar op `http://localhost:1337`.

---

## Toetsopzet

Alle **drie** onderdelen moeten minimaal **55/100** scoren voor een voldoende op de leeruitkomst.

| Onderdeel | Type | Wie |
|-----------|------|-----|
| Digitale inzichttoets | Schriftelijk (open + meerkeuzevragen) | Individueel |
| Beroepsproduct | Presentatie + demo (30 min) + vragen (15 min) | Groep |
| CGI (Criterium Gericht Interview) | Mondeling vraaggesprek | Individueel |

### Digitale inzichttoets — onderwerpen (elk 50%)

- **Software Design en Kwaliteit** — SDK workshops week 1-2
- **Software Architectuur & Applicatie Integratie** — systeemanalyse, messages & events; schrijf 2 ADR's (Nygard-formaat) voor een gegeven casus

---

## Verplichte onderdelen (inlevering)

> Niet aanwezig = geen toegang tot presentatie.

- [ ] Code repository ingeleverd (zonder libraries / temp files)
- [ ] `README.md` met opstartinstructies + voorbeeldrequest
- [ ] `ADR/` directory met markdown ADR's (incl. ADR apart component + ADR observability)
- [ ] Realisatietransparantie-logboek (gebruikte tools + AI-voorbeelden met prompts)
- [ ] Visualisaties: systeemarchitectuur, applicatiearchitectuur, bedrijfsproces

---

## Beroepsproduct rubric (groep)

**Max: 100 punten** — alle criteria worden als groep beoordeeld.

### 1. Architectuurbeschrijving & bedrijfsprocessen — max 20 pt

| Score | Beschrijving |
|-------|-------------|
| **0** | Kan onvoldoende toelichten hoe (non-)functionele requirements terugkomen in het ontwerp |
| **11** | Licht FR/NFR toe op basis van ontwerp; toont code-voorbeelden ter illustratie |
| **20** | + overwogen alternatieven toegelicht met criteria waarop ze zijn afgevallen |

### 2. Betrouwbaarheid — max 20 pt

| Score | Beschrijving |
|-------|-------------|
| **0** | Schaalbaarheid en robuustheid onvoldoende toegelicht of bewezen |
| **11** | FMEA overeenkomend met code + architectuur; performancerapportage + realtime monitoring |
| **20** | + aantonen welke test- en verbeterstappen hebben plaatsgevonden |

### 3. Duurzaam ontwerp — max 20 pt

| Score | Beschrijving |
|-------|-------------|
| **0** | Persistentie niet conform best practices; geen ruimte voor multi-tenancy of nieuwe processen |
| **11** | Versiebeheer toegepast; ontwerpprincipes impliciet gevolgd; multi-tenancy en uitbreiding mogelijk |
| **20** | + uitzonderingsscenario's; uitbreidbaarheid expliciet onderbouwd via ontwerpprincipes |

### 4. Testresultaten — max 20 pt

| Score | Beschrijving |
|-------|-------------|
| **0** | Geen unit-/integratie-/systeemtests, of kwaliteit onvoldoende |
| **11** | Unit-tests aanwezig; geautomatiseerde tests valideren werking en basisbetrouwbaarheid lokaal |
| **20** | + additionele testmethodieken (uitzonderingsscenario's, security, architectuur) geautomatiseerd |

### 5. Realisatieverantwoording — max 20 pt

| Score | Beschrijving |
|-------|-------------|
| **0** | Tools beschreven maar zonder reflectie op toegevoegde waarde of kosten |
| **11** | Tools + waarom + reflectie op waarde (tijdwinst, kwaliteit) en kosten (iteraties, debugtijd) |
| **20** | + concrete voorbeelden en verbeterpunten toepasbaar op toekomstige projecten |

---

## CGI rubric (individueel)

**Max: 100 punten** — individueel mondeling.

### 1. Duurzaamheid van het ontwerp — max 30 pt

**Vragen die gesteld worden:**
1. Vergelijk het aangereikte alternatieve ontwerp met jouw uitwerking — geef een voordeel en een nadeel van jouw ontwerp t.o.v. het alternatief voor een gegeven NFR.
2. Er wordt een toekomstige OpenMRS-ontwikkeling geschetst — leg uit hoe je dit uitwerkt vanuit het huidige ontwerp, inclusief eventueel herontwerp.

| Score | Beschrijving |
|-------|-------------|
| **0** | Kan geen voordelen/nadelen toelichten; geen inzicht in toekomstige ontwikkeling; nauwelijks vaktaal |
| **15** | Enkele voordelen/nadelen; toelichting op toekomstige ontwikkeling niet volledig sluitend maar toont inzicht; redelijk vaktaalgebruik |
| **30** | Diepgaand meerdere voordelen/nadelen; volledige toelichting inclusief herontwerp bij tekortkomingen; professionele vaktaal en ontwerpprincipes |

### 2. Representatie van het testen — max 30 pt

**Vragen die gesteld worden:** testaanpak toelichten, evalueren en koppelen aan de risicoanalyse (FMEA).

| Score | Beschrijving |
|-------|-------------|
| **0** | Onvoldoende toelichting op testaanpak |
| **15** | Testaanpak globaal toegelicht; herkenbare evaluatie met sterke/zwakke punten; verbeterpunten beperkt toegelicht; FMEA-relatie aangestipt; redelijk vaktaalgebruik |
| **30** | Testaanpak verantwoord met concrete voorbeelden uit beroepsproduct; kritische onderbouwde evaluatie; consistente verbeterpunten; FMEA goed gekoppeld; professionele vaktaal |

### 3. Reflectie op eigen handelen in SDLC + AI-tooling — max 40 pt

**Vragen die gesteld worden:** hoe heb je de SDLC-activiteiten in samenhang uitgevoerd en AI-tooling verantwoord ingezet met behoud van zelfredzaamheid?

| Score | Beschrijving |
|-------|-------------|
| **0** | Oppervlakkige of onduidelijke bijdrage; geen verband met SDLC-fasen; geen onderbouwing of leerpunten |
| **25** | Eigen bijdrage per SDLC-fase benoemd en globaal toegelicht; basisinzicht in samenhang; reflectie op AI-inzet; enkele leerpunten |
| **40** | Eigen bijdrage per SDLC-fase grondig toegelicht; diep inzicht in samenhang SDLC-activiteiten; onderbouwde reflectie op AI-inzet; weloverwogen leerpunten incl. (on)afhankelijkheid, zelfredzaamheid en verantwoordelijkheid |

---

## Presentatierichtlijnen

- **30 minuten** presentatie + demo (tijdsindeling vrij)
- **15 minuten** verdiepende vragen door docenten
- Niet alle groepsleden hoeven het woord te nemen
- Verantwoord dat het beroepsproduct voldoet aan de eisen **en** minimaal de "Voldoende"-kolom van de rubric
- Alle groepsleden aanwezig op het geplande tijdstip (zie nieuwsberichten voor lokaal en tijdstip)
