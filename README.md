# OpenMRS Communication Service

A resilient, event-driven notification service that runs alongside OpenMRS
and sends patients appointment reminders and cancellation notices via a
separate SaaS layer.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Docker host                          │
│                                                             │
│  ┌──────────────┐   MySQL    ┌──────────────┐              │
│  │   OpenMRS    │◄──────────►│  openmrs-db  │              │
│  │  :8080       │            └──────────────┘              │
│  │              │  AMQP                                     │
│  │  (Event      │──────────────────┐                        │
│  │   publisher) │                  ▼                        │
│  └──────────────┘          ┌──────────────┐                │
│                             │   RabbitMQ   │                │
│  ┌──────────────┐           │  :5672       │                │
│  │ notification │◄──────────│  :15672 (UI) │                │
│  │ -svc :3000   │  AMQP     └──────────────┘                │
│  │              │                                           │
│  │  • consume   │  REST poll (reconciliation / catch-up)    │
│  │    events    │◄────────────────────────────────────────  │
│  │  • outbox    │                    OpenMRS REST API        │
│  │  • send      │  PostgreSQL                               │
│  │    notifs    │◄──────────►┌──────────────┐              │
│  └──────────────┘            │ notification │              │
│                              │     -db      │              │
│                              └──────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

### Resilience strategy

| Risk | Mitigation |
|---|---|
| RabbitMQ event missed (OpenMRS crash) | **Polling reconciler** — notification-svc periodically queries the OpenMRS REST API using a watermark cursor stored in Postgres. Any appointments newer than the last-seen cursor are re-processed. |
| notification-svc crashes mid-send | **Transactional outbox** — events are written atomically to `outbox_events` before any external call. A relay loop retries until `published_at` is set. |
| Broker unavailable | Durable queues + DLX. Messages survive broker restarts; dead-lettered messages are retained for inspection and manual replay. |
| Network partition | Docker `restart: unless-stopped` on all services + healthcheck-gated `depends_on`. |

---

## Quick start (local dev)

```bash
cp .env.example .env
# Fill in all CHANGE_ME values

docker compose up -d

# OpenMRS UI
open http://localhost:8080/openmrs

# RabbitMQ management
open http://localhost:15672   # rabbit / <your password>
```

---

## Repository structure

```
.
├── docker-compose.yml           # Full stack definition
├── .env.example                 # Environment template
├── notification-service/
│   └── Dockerfile               # Service image (placeholder)
├── openmrs/
│   ├── modules/                 # Drop .omod files here (Event module etc.)
│   └── config/                  # OpenMRS runtime config overrides
├── infra/
│   ├── postgres/
│   │   └── init/
│   │       └── 00_schema.sql    # Outbox, watermark, notification_log tables
│   └── rabbitmq/
│       ├── config/
│       │   └── rabbitmq.conf
│       └── definitions/
│           └── topology.json    # Pre-seeded exchanges, queues, DLX bindings
└── .github/
    ├── workflows/
    │   ├── ci.yml               # Build & push image on push
    │   └── cd.yml               # Deploy to Portainer on merge
    └── scripts/
        ├── portainer-deploy.sh
        └── portainer-health-check.sh
```

---

## CI / CD

### How it works

1. **CI** (`ci.yml`) — triggers on every push to `main` or `develop`.
   - Validates `docker-compose.yml` syntax.
   - Builds the `notification-svc` image and pushes it to GHCR tagged
     with the Git SHA (`sha-<hash>`), branch name, and `latest` (main only).

2. **CD** (`cd.yml`) — triggers after a successful CI run.
   - `develop` branch → auto-deploys to **staging**.
   - `main` branch → deploys to **production** (requires manual approval
     — configure a required reviewer in _Settings → Environments → production_).
   - Uses the Portainer HTTP API to push the compose file + env vars to
     the target stack, then polls until all containers are healthy.

### Required GitHub secrets

| Secret | Description |
|---|---|
| `PORTAINER_URL` | `https://portainer.your-domain.com` |
| `PORTAINER_API_KEY` | Portainer user API key |
| `PORTAINER_STAGING_STACK_ID` | Numeric stack ID in Portainer (staging) |
| `PORTAINER_PRODUCTION_STACK_ID` | Numeric stack ID in Portainer (production) |
| `STAGING_OPENMRS_DB_PASSWORD` | … |
| `STAGING_OPENMRS_DB_ROOT_PASSWORD` | … |
| `STAGING_OPENMRS_API_PASSWORD` | … |
| `STAGING_RABBITMQ_PASSWORD` | … |
| `STAGING_NOTIFICATION_DB_PASSWORD` | … |
| `PROD_*` | Same set for production |

### First-time Portainer setup

1. In Portainer, create a new **Stack** (Stacks → Add stack → Upload).
   Upload `docker-compose.yml` and set all env vars manually for the
   first deploy.
2. Note the numeric stack ID from the URL
   (`/#!/N/docker/stacks/<ID>`).
3. Add the ID as a GitHub secret.
4. Subsequent deploys happen automatically via the CD workflow.

---

## OpenMRS event integration

The notification service is designed to consume events published by the
[OpenMRS Event Module](https://wiki.openmrs.org/display/docs/Event+Module).
Drop the `event-<version>.omod` file into `openmrs/modules/` and the
module will publish to RabbitMQ using the `openmrs.events` topic exchange
pre-seeded in `infra/rabbitmq/definitions/topology.json`.

Routing keys used:

| Routing key | Trigger |
|---|---|
| `appointment.scheduled` | New appointment booked |
| `appointment.updated` | Appointment rescheduled |
| `appointment.cancelled` | Appointment cancelled |

Because OpenMRS event delivery is best-effort, the notification service
also runs a **polling reconciler** that calls `/openmrs/ws/rest/v1/appointment`
filtered by `lastUpdated > watermark` on a configurable interval (e.g. every
5 minutes) to catch any events that were missed during downtime.

---

## Adding the notification service code

Replace `notification-service/Dockerfile` with your actual service.
The service needs to:

- Connect to RabbitMQ and consume from the `appointments` and
  `appointment.cancelled` queues.
- On every consumed message, write to the `outbox_events` table and
  then send the notification.
- Run a background reconciler that queries OpenMRS REST and compares
  against the `sync_watermarks` table.
- Expose `GET /health` → `200 OK` (required by the Docker healthcheck).
