# ADR-006 — Uitbreidbaarheid messaging providers: Strategy pattern + Spring auto-discovery

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De opdracht vereist ondersteuning van vier externe messaging providers: SwiftSend, LegacyLink, AsyncFlow en SecurePost (NFR-3). Elke provider heeft een eigen authenticatiemechanisme, berichtformaat en foutafhandeling. Daarnaast eist de opdracht dat de module uitbreidbaar is naar nieuwe providers zonder grootschalige herstructurering (NFR-12).

Elke ziekenhuisorganisatie (tenant) kiest precies één provider. De service moet bij runtime weten welke provider-implementatie voor een tenant actief is.

---

## Probleem

Hoe structureren we de provider-integraties zodat:
1. Nieuwe providers eenvoudig kunnen worden toegevoegd zonder bestaande code te wijzigen?
2. De routering naar de juiste provider betrouwbaar en testbaar is?
3. Het contract (interface) afdwingbaar is voor alle providers?

---

## Overwogen opties

### Optie 1 — If-else / switch in de dispatcher

`NotificationDispatcher` bevat een `switch` op `providerName` en roept de juiste adapter direct aan.

**Voordelen**
- Eenvoudig te begrijpen

**Nadelen**
- Open/Closed Principle geschonden: elke nieuwe provider vereist aanpassing van `NotificationDispatcher`
- Slecht testbaar: testscenario's voor één provider laden ook alle andere adapters
- Niet uitbreidbaar zonder codewijziging — in strijd met NFR-12

---

### Optie 2 — Factory registry (Map geregistreerd bij startup)

Een centrale factory beheert een `Map<String, NotificationProvider>` die bij applicatiestart wordt gevuld.

**Voordelen**
- Expliciete registratie zichtbaar op één plek

**Nadelen**
- Nog steeds handmatige registratie bij elke nieuwe provider
- Extra boilerplate zonder toegevoegde waarde ten opzichte van Spring-native aanpak

---

### Optie 3 — Strategy pattern + Spring `@Component` auto-discovery *(gekozen)*

`NotificationProvider` is een interface met één methode `send(AppointmentEvent, ProviderCredentials)`. Elke provider-implementatie is een Spring `@Component` met een unieke `getName()`. `NotificationDispatcher` ontvangt alle providers via `@Autowired List<NotificationProvider>` en zoekt de juiste op basis van `tenant.providerName`.

**Voordelen**
- Nieuwe provider = nieuwe klasse, nul andere codewijzigingen (Open/Closed Principle)
- Spring-discoverability: geen handmatige registratie
- Contract afdwingbaar via `NotificationProviderContractTest` op build-time
- Eenvoudig te mocken per test

**Nadelen**
- Provider-namen moeten overeenkomen met `tenants.provider_name` CHECK-constraint in de database — risico op typefout bij nieuwe provider (mitigatie: contract test valideert dit)

---

## Besluit

**Gekozen: Optie 3 — Strategy pattern + Spring `@Component` auto-discovery.**

---

## Onderbouwing

Het Strategy pattern is de standaardoplossing voor uitwisselbare algoritmen met een gemeenschappelijk interface. De Spring-auto-discovery elimineert handmatige registratie volledig. De `NotificationProviderContractTest` garandeert op build-time dat alle providers aan het contract voldoen en dat hun namen consistent zijn met de database CHECK-constraint.

Dit ontwerp bewijst NFR-12 (uitbreidbaarheid) direct: een nieuwe provider vereist uitsluitend één nieuwe klasse die `NotificationProvider` implementeert en `@Component` is.

---

## Implementatiedetails

```
NotificationProvider (interface)
  └─ send(AppointmentEvent event, ProviderCredentials credentials): NotificationResult
  └─ getName(): String

Implementaties (adapter/ package):
  SwiftSendProvider     (@Component, getName() = "SwiftSend")
  SecurePostProvider    (@Component, getName() = "SecurePost")
  LegacyLinkProvider    (@Component, getName() = "LegacyLink")
  AsyncFlowProvider     (@Component, getName() = "AsyncFlow")
  MockMessagingProvider (@Component, getName() = "Mock" — alleen voor tests)

NotificationDispatcher:
  @Autowired List<NotificationProvider> providers
  → Map<String, NotificationProvider> providerMap (bij startup opgebouwd)
  → dispatch(): zoekt providerMap.get(tenant.providerName)
```

Database CHECK-constraint in `tenants` tabel:
```sql
CONSTRAINT tenants_provider_name_check
  CHECK (provider_name IN ('SwiftSend','LegacyLink','AsyncFlow','SecurePost'))
```

De `NotificationProviderContractTest` valideert op build-time:
- Alle vier productie-providers aanwezig in Spring-context
- Elk provider-naam uniek
- Provider-namen matchen de database CHECK-constraint waarden
- Elke provider is een Spring `@Component`

---

## Consequenties

**Positief**
- Toevoegen van een vijfde provider vereist: één nieuwe klasse, aanpassing CHECK-constraint, geen wijzigingen in dispatcher of tests
- Contract wordt automatisch gecontroleerd bij elke build

**Negatief**
- Provider-naam in code moet exact overeenkomen met `tenants.provider_name` in de database — dit is een impliciete koppeling die door de contract-test bewaakt wordt

---

## Relatie tot requirements

- **FR-3** — Één provider per organisatie: de routering op `tenant.providerName` implementeert dit
- **NFR-3** — Vier providers ondersteund: alle vier geïmplementeerd als `@Component`
- **NFR-12** — Uitbreidbaar naar andere modules: nieuwe provider = nieuwe klasse
