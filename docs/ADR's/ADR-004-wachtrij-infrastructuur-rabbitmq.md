# ADR-004 — Wachtrij-infrastructuur: RabbitMQ

**Status:** Geaccepteerd  
**Datum:** 2026-05-26  
**Auteurs:** Wassim Balouda, Storm Kroonen, Nick de Rooij, Thijs van de Veen

---

## Context

De communicatiemodule moet afspraaknotificaties en annuleringsberichten betrouwbaar bij patiënten bezorgen. Berichten worden verstuurd via een externe FakeComWorld-container die vier verschillende messaging-providers simuleert. Deze container is geen onderdeel van onze eigen stack en wordt als extern systeem beschouwd.

### Beschikbare providers (FakeComWorld)

| Provider | API-type | Authenticatie | Bijzonderheden |
|----------|----------|---------------|----------------|
| ⚡ SwiftSend | REST | X-API-KEY header | Rate limiting, willekeurige fouten, gesimuleerde vertraging |
| 🔒 SecurePost | REST | JWT (via apart login-endpoint) | Token verloopt na 3 minuten, aparte rate limits voor auth en berichten |
| ☎ LegacyLink | SOAP | BASIC auth | XML-contract, legacy protocol, variabele vertraging (100–3000 ms) |
| 🔃 AsyncFlow | REST | X-API-KEY header | Asynchroon: commando indienen → status pollen → resultaat verwerken |

Elke provider heeft zijn eigen foutgedrag, rate limits en authenticatiemechanisme. De notificatieservice moet alle vier ondersteunen zonder dat het kernsysteem hiervoor aangepast hoeft te worden.

### Twee bronnen van events

- **OpenMRS event push** — de OpenMRS Event Module publiceert domeingebeurtenissen wanneer afspraken worden aangemaakt, gewijzigd of geannuleerd.
- **Polling reconciler** — een periodieke inhaalsessie die de OpenMRS REST API bevraagt om gemiste events op te vangen tijdens uitval.

Beide paden komen samen in dezelfde notificatiepipeline. De pipeline moet omgaan met:

- **Temporele ontkoppeling** — de notificatieservice moet events kunnen blijven ontvangen, ook als een provider traag of onbereikbaar is.
- **Gedragsmatige ontkoppeling** — OpenMRS mag niet weten welke providers actief zijn en mag nooit geblokkeerd worden door een trage of falende provider.
- **At-least-once delivery** — een notificatie mag nooit stil worden weggegooid door een tijdelijke fout.
- **Dead-letter afhandeling** — berichten die herhaaldelijk falen mogen de wachtrij niet blokkeren.
- **Controleerbaarheid** — elke verzendpoging en het resultaat daarvan moeten worden vastgelegd.

## Probleem

Welke wachtrij-infrastructuur gebruiken we om events van OpenMRS betrouwbaar en ontkoppeld te verwerken naar de vier messaging providers?

---

## Besluit

**Gekozen: RabbitMQ als message broker tussen OpenMRS en de notificatieservice.**

RabbitMQ fungeert als buffer en betrouwbaarheidsmechanisme. De notificatieservice consumeert berichten uit RabbitMQ en stuurt ze vervolgens door naar de juiste FakeComWorld-provider via de bijbehorende provider-adapter.

```
OpenMRS
  └──(AMQP)──► RabbitMQ ──(AMQP)──► Notification Service
                                           │
                              ┌────────────┼────────────┐──────────────┐
                              ▼            ▼            ▼              ▼
                         SwiftSend    SecurePost   LegacyLink      AsyncFlow
                         (REST/Key)  (REST/JWT)    (SOAP/BASIC)   (REST/Async)
                              └────────────┴────────────┴──────────────┘
                                          FakeComWorld (extern)
```

---

## Topologie

```
openmrs.events (topic exchange)
│
├── routing key: appointment.scheduled ──► appointments (queue)
├── routing key: appointment.updated   ──► appointments (queue)
└── routing key: appointment.cancelled ──► appointment.cancelled (queue)

Dead-letter exchange: openmrs.events.dlx (direct)
├── appointments.dead              ← gefaalde afspraakmeldingen
└── appointment.cancelled.dead     ← gefaalde annuleringsmeldingen
```

Alle queues, exchanges en bindings worden vooraf aangemaakt via `infra/rabbitmq/definitions/topology.json`, zodat de broker volledig geconfigureerd is voordat een service verbinding maakt.

---

## Wachtrij-configuratie

| Eigenschap | Waarde | Reden |
|------------|--------|-------|
| Queue duurzaamheid | `durable: true` | Berichten overleven een broker-herstart |
| Message TTL | 24 uur | Voorkomt verouderde notificaties |
| Dead-letter exchange | `openmrs.events.dlx` | Gefaalde berichten worden bewaard, niet weggegooid |
| Exchange type | topic | Fijnmazige routing; nieuwe eventtypen vereisen alleen een nieuwe binding |
| Ack-modus | auto (Spring AMQP) | Met retry-interceptor; bericht wordt ge-nacked en opnieuw aangeboden bij fout |
| Max. pogingen | 5 (exponentiële back-off: 3 s → 30 s) | Opvang van tijdelijke provideruitval zonder overbelasting |

---

## Hoe de vier providers passen in dit model

De provider-adapter-laag in de notificatieservice verwerkt de complexiteit per provider achter de RabbitMQ-consumer. RabbitMQ zelf hoeft niets te weten van de providers.

| Provider | Aandachtspunt voor de adapter | Oplossing in de adapter |
|----------|-------------------------------|-------------------------|
| SwiftSend | Rate limiting en willekeurige fouten | Spring retry-mechanisme via RabbitMQ back-off vangt tijdelijke fouten op |
| SecurePost | JWT verloopt na 3 minuten | Adapter beheert token-cache; haalt nieuw token op bij 401-respons |
| LegacyLink | SOAP + BASIC auth, hoge variabele vertraging | Aparte adapter met SOAP-client; langere timeout geconfigureerd |
| AsyncFlow | Asynchroon: submit → poll → resultaat | Adapter slaat correlatie-ID op in database; aparte poll-scheduler controleert status |

---

## Niet-functionele eisen

| Eis | Hoe RabbitMQ + deze topologie hieraan voldoet |
|-----|-----------------------------------------------|
| Betrouwbaarheid | Duurzame queues, persistente berichten, dead-letter exchange |
| Veerkracht | Service kan herstarten zonder berichten te verliezen; Spring AMQP herverbindt automatisch |
| Ontkoppeling | OpenMRS publiceert en vergeet; notificatieservice consumeert op eigen tempo |
| Uitbreidbaarheid | Nieuwe provider-adapters worden toegevoegd zonder wijzigingen in bestaande code of RabbitMQ-configuratie |
| Observeerbaarheid | RabbitMQ management UI (`:15672`) toont queue-diepten, DLX-inhoud en consumentenstatussen |
| Back-pressure | Als een provider traag is (bijv. LegacyLink), buffert RabbitMQ de berichten; OpenMRS wordt nooit geblokkeerd |

---

## Overwogen alternatieven

| Optie | Reden afgewezen |
|-------|----------------|
| Kafka | Zwaarder operationeel beheer; partitiesplitsing voegt complexiteit toe die niet in verhouding staat tot ons eventvolume; TTL en DLX zijn eenvoudiger in RabbitMQ |
| Directe HTTP (OpenMRS → notificatieservice) | Creëert temporele en gedragsmatige koppeling — als de notificatieservice uitvalt, gaan events verloren; OpenMRS moet weten welk endpoint wordt gebruikt |
| Alleen database-polling (geen broker) | Hogere belasting op de OpenMRS-database; hogere latentie; de broker blijft het primaire snelle pad; de reconciler is de vangstoptie |
| ActiveMQ | Minder modern ecosysteem; slechtere Spring Boot-integratie vergeleken met RabbitMQ |

---

## Gevolgen

**Positief**
- OpenMRS en de notificatieservice zijn volledig ontkoppeld — beide kunnen onafhankelijk herstarten.
- Gefaalde notificaties gaan nooit stil verloren; de DLX maakt inspectie en handmatig opnieuw verzenden mogelijk.
- De topic exchange maakt het toevoegen van nieuwe eventtypen (bijv. `lab.uitslag.gereed`) een configuratiewijziging, geen codewijziging.
- Alle vier de FakeComWorld-providers kunnen worden ondersteund via losse adapters zonder wijzigingen in de broker-configuratie.

**Negatief / afwegingen**
- RabbitMQ is een extra infrastructuurcomponent die beheerd en gemonitord moet worden.
- At-least-once semantiek betekent dat de notificatieservice idempotent moet zijn (afgehandeld via deduplicatie in `notification_log`).
- AsyncFlow vereist een aparte poll-scheduler en statusopslag in de database — dit is complexer dan de synchrone providers.

---

## Referenties

- [RabbitMQ Dead Letter Exchanges](https://www.rabbitmq.com/dlx.html)
- [Spring AMQP Retry](https://docs.spring.io/spring-amqp/docs/current/reference/html/)
- [OpenMRS Event Module](https://wiki.openmrs.org/display/docs/Event+Module)
- FakeComWorld documentatie — externe messaging-providers
- `infra/rabbitmq/definitions/topology.json` — gezaghebbende queue/exchange-definities
