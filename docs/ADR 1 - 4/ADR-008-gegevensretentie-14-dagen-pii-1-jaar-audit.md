# ADR-008 — Gegevensretentie: 14 dagen PII, 1 jaar auditlog

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De service slaat notificatiegegevens op in de `notification_log`-tabel. Deze bevat persoonsgebonden informatie (PII): telefoonnummer, afspraakinformatie en tijdstip. De opdracht schrijft twee retentietermijnen voor (NFR-10, NFR-11):
- PII-houdende logregels: maximaal 14 dagen bewaren
- PII-vrije auditinformatie (tenant, provider, status, tijdstip): minimaal 1 jaar bewaren voor factuurcontrole (FR-2)

---

## Probleem

Hoe implementeren we automatische gegevensverwijdering die compliant is met de retentievereisten, zonder handmatige interventie en zonder productiedata per ongeluk te beschadigen?

---

## Overwogen opties

### Optie 1 — Handmatige cleanup door beheerder

Een beheerder voert periodiek SQL-queries uit om verouderde records te verwijderen.

**Voordelen**
- Geen extra code nodig

**Nadelen**
- Niet betrouwbaar: vergeten cleanup = compliance-overtreding
- Operationeel risico bij beheerderswisseling
- Niet schaalbaar bij meerdere tenants

---

### Optie 2 — PostgreSQL partitionering + auto-drop

`notification_log` wordt gepartitioneerd op `created_at` (maandpartities). Oude partities worden automatisch gedropped.

**Voordelen**
- Hoge performantie bij grote volumes

**Nadelen**
- Complexe tabelstructuur
- Partitioned tables vereisen aanpassingen in alle queries
- Overkill voor de huidige schaal (tientallen notificaties per dag)

---

### Optie 3 — Geplande Spring job (`DataRetentionJob`) *(gekozen)*

Een Spring `@Scheduled`-job draait dagelijks om 02:00. De job:
1. Kopieert PII-vrije velden van records ouder dan 14 dagen naar `notification_audit_log`
2. Verwijdert de originele records uit `notification_log`
3. Verwijdert `notification_audit_log`-records ouder dan 1 jaar

**Voordelen**
- Volledig geautomatiseerd, geen handmatige actie nodig
- Transparant en testbaar (Spring component)
- Audit trail blijft beschikbaar voor factuurcontrole (FR-2)
- 02:00 minimale belasting op de database tijdens piekuren

**Nadelen**
- Granulariteit: cleanup vindt 1× per dag plaats, niet continu
- Bij crash van de job wordt cleanup pas de volgende nacht opnieuw geprobeerd (max 1 dag vertraging — acceptabel)

---

## Besluit

**Gekozen: Optie 3 — `DataRetentionJob` als geplande Spring-job.**

---

## Onderbouwing

De dagelijkse granulariteit is ruim voldoende voor een 14-daagse retentietermijn. De eenvoud en testbaarheid van een Spring-job wegen zwaarder dan de complexiteit van partitionering. Het archiveren naar `notification_audit_log` zorgt dat factuurverantwoording (FR-2) gegarandeerd beschikbaar blijft.

---

## Implementatiedetails

```
DataRetentionJob (cron = "0 0 2 * * *"):
  archiveToAuditLog():
    INSERT INTO notification_audit_log
      (tenant_id, provider_name, status, retry_count, event_type, created_at)
    SELECT ... FROM notification_log
    WHERE created_at < NOW() - INTERVAL '14 days'
    AND NOT EXISTS (reeds gearchiveerd)

  deletePiiData():
    DELETE FROM notification_log
    WHERE created_at < NOW() - INTERVAL '14 days'

  purgeOldAuditLog():
    DELETE FROM notification_audit_log
    WHERE created_at < NOW() - INTERVAL '1 year'

notification_audit_log tabel (geen PII):
  id, tenant_id, provider_name, status, retry_count,
  event_type, created_at
```

---

## Consequenties

**Positief**
- Automatische compliance met NFR-10 en NFR-11
- Auditlog bevat geen PII — ook na 1 jaar veilig te bewaren
- Geen operationele actie nodig bij normale werking

**Negatief**
- `DataRetentionJob` heeft geen retry als de job zelf faalt — beheerder monitort via Grafana/Loki alerts

---

## Relatie tot requirements

- **NFR-10** — 14-dagen verwijdering PII: `deletePiiData()` implementeert dit
- **NFR-11** — 1-jaar meta-info retentie: `archiveToAuditLog()` + `purgeOldAuditLog()` implementeren dit
- **FR-2** — Logging voor factuurcontrole: `notification_audit_log` is de basis voor factuurverantwoording
