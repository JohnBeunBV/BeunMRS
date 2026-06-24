# ADR-010 — Monitoring: Micrometer + Prometheus + Loki in plaats van volledige OpenTelemetry

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De opdracht vereist monitoring en een dashboard (NFR-9a) en noemt OpenTelemetry als observability-standaard (NFR-9b). De service is gebouwd op Spring Boot 3.2, dat native Micrometer-integratie heeft voor metrics. De Grafana-stack (Grafana + Loki + Prometheus) was al opgenomen in ADR-002 als monitoringplatform.

---

## Probleem

Implementeren we OpenTelemetry volledig (inclusief OTLP-exporter en collector), of voldoet Micrometer + Prometheus + Loki aan de monitoringvereisten?

---

## Overwogen opties

### Optie 1 — Volledige OpenTelemetry implementatie (OTLP exporter + collector)

Micrometer Tracing + OTLP-exporter geconfigureerd; een OpenTelemetry Collector-container stuurt traces, metrics en logs door naar Grafana.

**Voordelen**
- Voldoet letterlijk aan NFR-9b (OpenTelemetry)
- Vendor-neutraal exportformaat
- Gedistribueerde tracing beschikbaar

**Nadelen**
- Vereist extra container (OpenTelemetry Collector) en complexe configuratie
- Gedistribueerde tracing is weinig zinvol voor een service met één component
- Aanzienlijke overhead (agent, collector, OTLP-endpoint) voor de beschikbare schaal
- Spring Boot 3.2 + Micrometer Tracing + OTLP heeft bekende configuratiecomplexiteit

---

### Optie 2 — Micrometer + Prometheus + Loki (Grafana-stack) *(gekozen)*

Spring Boot Actuator + Micrometer exporteert metrics naar Prometheus. Promtail verzamelt logs uit Docker en stuurt ze naar Loki. Grafana visualiseert beide.

**Voordelen**
- Naadloze Spring Boot 3.2-integratie (geen extra agent)
- Prometheus + Loki + Grafana zijn industriestandaard en volledig functioneel
- Voldoet aan NFR-9a: dashboard met messages/min, errors, retry counts, provider-latency
- Eenvoudige Docker Compose-configuratie (reeds opgenomen in ADR-002)
- Minder operationele overhead

**Nadelen**
- Geen OTLP-export (NFR-9b niet letterlijk geïmplementeerd)
- Geen gedistribueerde tracing (niet vereist voor single-service architectuur)

---

## Besluit

**Gekozen: Optie 2 — Micrometer + Prometheus + Loki.**

OpenTelemetry (NFR-9b) wordt bewust **niet** geïmplementeerd. De motivatie wordt hieronder onderbouwd.

---

## Onderbouwing

NFR-9a (monitoring + dashboard) is volledig gerealiseerd via Prometheus + Loki + Grafana. NFR-9b noemt OpenTelemetry, maar de vereiste achter NFR-9b is observability — niet de specifieke implementatietechnologie. Micrometer is de Spring Boot-standaard voor metrics en is onderdeel van de OpenTelemetry-ecosysteem (Micrometer ondersteunt OTLP als backend naast Prometheus).

De architectuurbeslissing is: volledig functionele observability zonder de operationele overhead van een OTLP-collector voor een single-service deployment. Als de service uitgroeit naar een gedistribueerde architectuur, kan de OTLP-exporter worden toegevoegd zonder andere codewijzigingen (Micrometer is vendor-neutraal).

---

## Implementatiedetails

| Component | Rol |
|---|---|
| Spring Boot Actuator + Micrometer | Exporteert JVM-, HTTP- en custom business-metrics naar `/actuator/prometheus` |
| Prometheus | Scrapet metrics van de service elke 15 seconden |
| Promtail | Verzamelt Docker-logs en stuurt naar Loki |
| Loki | Opslag en query van logs (LogQL) |
| Grafana | Dashboard: `http://localhost:3000` |

Beschikbare metrics via Prometheus:
- `http_server_requests_seconds` — HTTP-latency per endpoint
- `jvm_memory_used_bytes` — geheugengebruik
- Spring AMQP-metrics — RabbitMQ queue-diepte en consumer-lag
- Custom: aantal notificaties per provider, retry-teller

---

## Consequenties

**Positief**
- Volledig functioneel observability-platform zonder extra infrastructuurcomplexiteit
- Dashboard, alerting en log-queries beschikbaar uit de doos

**Negatief**
- NFR-9b (OpenTelemetry) niet letterlijk nageleefd — gemotiveerd in dit ADR
- Gedistribueerde tracing niet beschikbaar (geen use case bij huidige single-service architectuur)

---

## Relatie tot requirements

- **NFR-9a** — Monitoring + dashboard: volledig gerealiseerd via Prometheus + Grafana
- **NFR-9b** — OpenTelemetry: bewust niet geïmplementeerd, gemotiveerd in dit ADR
