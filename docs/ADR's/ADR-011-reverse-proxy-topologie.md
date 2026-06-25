# ADR-011 — Reverse-proxy-topologie: één ingress vs. gesplitste proxies

**Status:** Voorgesteld (huidige opzet geaccepteerd voor deze oplevering; consolidatie als herontwerp)
**Datum:** 2026-06-25
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De communicatiemodule wordt extern benaderd via twee kanalen:

1. De **registratie-UI** (`notification-frontend`, poort 3001) waarmee een ziekenhuisbeheerder een tenant aanmaakt.
2. **Technische API-toegang** (`notification-nginx`, poort 4000) voor directe API-calls (curl/Postman), health checks en de TLS 1.3-transportbeveiliging (NFR-5b).

Tijdens het verifiëren van de C4-diagrammen bleek dat dit feitelijk **twee onafhankelijke NGINX-reverse-proxies** zijn die allebei naar dezelfde backend (`notification-svc:8080`) wijzen:

- `notification-frontend` bevat een **eigen** NGINX die de React-app serveert én `/api/`-calls direct doorzet naar `notification-svc` (`frontend/nginx.conf`, `location /api/ { proxy_pass http://notification-svc:8080; }`).
- `notification-nginx` is een **losse** container die als TLS-reverse-proxy óók naar `notification-svc` proxyt (`infra/nginx/`).

De React-code post naar de relatieve URL `/api/register`, dus UI-API-verkeer loopt via de frontend-NGINX en **niet** via `notification-nginx`.

## Probleem

Het UI-API-verkeer omzeilt de dedicated API-gateway (`notification-nginx`). Daardoor worden security-policies die op die gateway worden geconfigureerd — TLS-instellingen, security headers (HSTS, `X-Content-Type-Options`), en eventuele rate-limiting — **niet uniform afgedwongen** over al het API-verkeer. Er zijn twee TLS-ingangen met elk een eigen certificaat en eigen configuratie, wat het beheer en de security-consistentie bemoeilijkt (raakt NFR-2c en NFR-5b).

---

## Overwogen opties

### Optie 1 — Huidige opzet: twee gescheiden proxies

`notification-frontend` serveert de SPA + proxyt zijn eigen `/api/`; `notification-nginx` is een aparte API-gateway.

**Voordelen**
- Elke container is zelfstandig deploybaar (de frontend serveert en proxyt zichzelf).
- Werkt aantoonbaar; lokaal end-to-end getest.

**Nadelen**
- Security-policies worden niet uniform afgedwongen — UI-API-verkeer mist de gateway.
- Twee TLS-ingangen, twee certificaten, twee configuraties → meer beheer en grotere kans op drift.
- Verwarrend in de architectuurplaat (twee proxies naar één backend).

### Optie 2 — Eén NGINX serveert alles

Eén NGINX serveert de gebouwde React-bestanden (`/`) én proxyt `/api/` naar `notification-svc`. De aparte frontend-NGINX vervalt; de frontend wordt een build-stap die statische bestanden in deze ene ingress plaatst.

**Voordelen**
- Eén ingress, één certificaat, één plek voor TLS, security headers en rate-limiting → uniforme afdwinging (NFR-2c, NFR-5b).
- Eenvoudiger beheer en een schonere C4-containerplaat.

**Nadelen**
- Frontend en API-gateway zijn niet langer afzonderlijk deploybaar.
- Vereist herinrichting van de build/Docker-opzet.

### Optie 3 — `notification-nginx` als enige publieke ingress

`notification-nginx` wordt de enige extern blootgestelde poort en routeert `/` → frontend-container en `/api/` → `notification-svc`. De frontend-NGINX blijft bestaan maar wordt intern.

**Voordelen**
- Eén publieke TLS-ingang met uniforme policy-afdwinging.
- Containers blijven gescheiden (frontend zelfstandig).

**Nadelen**
- Iets meer netwerk-hops (gateway → frontend → svc voor de SPA).
- Nog steeds twee NGINX-processen, maar slechts één publiek.

---

## Besluit

**Voor deze oplevering: Optie 1 (huidige opzet) geaccepteerd.** De stack werkt en is end-to-end getest; de splitsing is functioneel onschadelijk en het herinrichten van de proxy-/Docker-laag vlak vóór inlevering weegt niet op tegen het risico op regressie in een werkend systeem.

**Als herontwerp: Optie 2** — consolidatie naar één ingress. Dit dwingt TLS, security headers en rate-limiting op één plek af (NFR-2c, NFR-5b), vereenvoudigt het beheer en levert een schonere architectuur. Optie 3 is het alternatief wanneer afzonderlijke deploybaarheid van de frontend zwaarder weegt dan minimale netwerk-hops.

---

## Gevolgen

- **Nu:** twee TLS-ingangen blijven bestaan; security-policies op `notification-nginx` dekken het UI-API-verkeer niet. Dit is een bewust geaccepteerde, gedocumenteerde beperking — geen functioneel defect.
- **Bij consolidatie:** alle API-toegang loopt door één gateway, waardoor security-eisen uniform afdwingbaar worden en de C4-containerplaat één externe ingang toont.
- Dit punt is opgenomen als bekend verbeterpunt in `docs/OPENSTAANDE-PUNTEN.md` en dient als concreet voorbeeld voor de CGI-vraag over duurzaamheid en herontwerp.

**Gerelateerde requirements:** NFR-2c (beveiliging best practices), NFR-5b (TLS 1.3 transport).
