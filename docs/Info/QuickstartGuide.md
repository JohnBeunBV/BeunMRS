# BeunMRS Hospital Quickstart & Onboarding Guide
### Multi-Tenant Client Integration and Gateway Routing Manual

Welcome to the BeunMRS Communication Platform. This guide outlines the precise steps required for Hospital IT Administrators and Clinical Operators to register their organization as an independent, isolated tenant on the platform.

---

## 1. Accessing the Tenant Registration System
To initialize your hospital workspace infrastructure, open a secure browser session and navigate to the client registration portal dashboard:
* Secure Client URL: https://localhost:3001
* Security Notice: The local development configuration installs self-signed TLS certificates for local encryption security compliance. You will need to click "Advanced -> Proceed" to clear your browser's initial handshake warning.

---

## 2. Organization Profile Setup & Authentication
Input your organization parameters to establish row-level isolation logic inside our shared multi-tenant engine:
1. Hospital Workspace Name: The human-readable designation of your medical facility (e.g., St. Jude General Hospital).
2. OpenMRS Target Host URL: The external, fully qualified domain name or internal network routing path of your hospital's OpenMRS API installation (e.g., http://gateway/openmrs or https://openmrs.hospital.org).
3. API Access Credentials: Input an administrative service user name and password with privileges to query the OpenMRS REST interface path /ws/rest/v1/appointment/search.

---

## 3. Selecting and Customizing Messaging Channels
The BeunMRS MVP architecture supports immediate validation through FakeComWorld, a dedicated simulator engine container mimicking four separate real-world telecom provider backends. Select one of the following integration channels from the provider dropdown menu:

| Provider Name | Protocol Engine | Authentication Method | Simulated Operational Constraints |
| :--- | :--- | :--- | :--- |
| SwiftSend | REST API | X-API-KEY Custom Header | Rate-limited to 20 requests/min; 10% fault injection rate. |
| SecurePost | REST API | JWT Token (3-Min Expiry) | Strict separate token endpoints; high authentication delay. |
| LegacyLink | SOAP WebSvc | HTTP Basic Access Auth | Heavy XML Payload payloads; strict schema; high variable latency. |
| AsyncFlow | REST (Polling) | X-API-KEY Custom Header | Asynchronous tracking loop; commands require status checks. |

Ensure that the corresponding credential keys in your local .env configurations match the access strings typed into the portal fields.

---

## 4. Operational Architecture & Synchronization Verification
Once registration completes, the core engine creates an automatic data poller loop tailored to your workspace identifier. The pipeline combines automated event consumption with background cron reconciliation to prevent missed appointments during unexpected network disruptions.

### Automated Data Catch-Up Strategy
The synchronization mechanism uses an auto-recovering delta query approach. If your regional database or the central SaaS platform suffers a temporary network disconnection, the OpenMrsAppointmentPoller recalculates missing timeline blocks using the following mathematical logic:

Delta T_sync = T_current - T_cursor

The background reconciliation process continuously sets queries to fetch records updated inside this window, ensuring that patient notifications are fully processed without duplicate message delivery.

---

## 5. System Diagnostics and Verification Procedures
To verify that your hospital integration is processing events normally, use the included Grafana telemetry stack to monitor your communication channels:
1. Navigate to the Grafana interface at http://localhost:3000` and log in using your configured administration credentials.
2. Open the BeunMRS Operational Dashboard to review message throughput charts, average latency graphs, and dispatch success rates.
3. To view live application logs, select the Explore tab and filter log outputs via the Loki data source using the following query string format:

   {container_name="notification-svc"} |= "tenant-id"