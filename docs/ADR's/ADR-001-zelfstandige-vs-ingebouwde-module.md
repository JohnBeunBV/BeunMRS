# ADR-001 — Architectuurkeuze: zelfstandige module vs. ingebouwde module

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De communicatiemodule moet notificaties versturen voor meerdere organisaties die gebruikmaken van OpenMRS. De oplossing moet schaalbaar, uitbreidbaar en onafhankelijk inzetbaar zijn. Daarnaast vereist de opdracht een Software-as-a-Service (SaaS) benadering waarbij meerdere organisaties gebruik kunnen maken van dezelfde dienst.

## Probleem

Hoe positioneren we de communicatiemodule ten opzichte van OpenMRS?

---

## Overwogen opties

### Optie 1 — Ingebouwde module binnen OpenMRS

De communicatiemodule wordt direct geïntegreerd als onderdeel van OpenMRS.

**Voordelen**
- Directe toegang tot interne data en functionaliteiten
- Minder netwerkcommunicatie nodig
- Eenvoudigere interne koppeling met OpenMRS

**Nadelen**
- Sterke afhankelijkheid van OpenMRS-versies en updates
- Beperkte schaalbaarheid richting meerdere organisaties
- Moeilijk herbruikbaar buiten één OpenMRS-instantie
- Onderhoud wordt complexer bij toekomstige uitbreidingen

---

### Optie 2 — Zelfstandige SaaS communicatiemodule (Dockerized)

De communicatiemodule draait als een zelfstandige service in containers en communiceert via API's met OpenMRS.

**Voordelen**
- Ondersteunt meerdere OpenMRS-instanties (multi-tenant)
- Onafhankelijk deploybaar, schaalbaar en onderhoudbaar
- Sluit aan op de SaaS-doelstelling van de opdracht
- Eenvoudig uitbreidbaar met nieuwe messaging providers
- Minder afhankelijk van wijzigingen binnen OpenMRS

**Nadelen**
- Extra integratielaag via API's vereist
- Netwerkcommunicatie introduceert beperkte latency
- Meer aandacht nodig voor beveiliging en monitoring van externe communicatie

---

## Besluit

**Gekozen: Optie 2 — Zelfstandige, Dockerized SaaS communicatiemodule.**

## Onderbouwing

De zelfstandige SaaS-architectuur sluit het beste aan op de functionele en niet-functionele eisen van het project. Door de communicatiemodule los te koppelen van OpenMRS kan de oplossing onafhankelijk worden ontwikkeld, gedeployed en opgeschaald. Hierdoor wordt het mogelijk om meerdere organisaties tegelijk te ondersteunen zonder aparte installaties per organisatie.

Daarnaast biedt deze architectuur meer flexibiliteit voor toekomstige uitbreidingen, zoals ondersteuning voor extra messaging providers of integratie met andere systemen naast OpenMRS. Door gebruik te maken van containers wordt consistente deployment en eenvoudig beheer mogelijk gemaakt.

---

## Consequenties

**Positieve gevolgen**
- Betere schaalbaarheid en onderhoudbaarheid
- Ondersteuning voor multi-tenancy
- Onafhankelijke deployments en updates
- Toekomstbestendige architectuur

**Negatieve gevolgen**
- Extra complexiteit door netwerkcommunicatie
- Meer aandacht vereist voor API-beveiliging en foutafhandeling
- Afhankelijkheid van stabiele netwerkverbindingen tussen systemen
