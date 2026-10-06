# Medical Claim Fraud & Inflated-Billing Detector

An AI/ML-style medical-insurance claim fraud detector delivered as a **pure-JDK**
backend. It validates and stores claims, runs a multi-layer fraud-scoring engine,
explains each score, supports manual investigations, and exposes dashboard
analytics, search/filtering, reports, audit logging, and in-app notifications
over a small HTTP/JSON API.

## Why pure JDK (no Spring, no Maven Central, no Python)

This project is built and run with **nothing but the Java standard library**,
compiled by `javac` and packaged by `jar` through a POSIX shell script
(`build.sh`). There is no Spring Boot, no Jackson/Gson, no JUnit, no database
driver, and no Python ML microservice.

The reason is the build/run environment: it is **network-isolated
(INTEGRATIONS_ONLY)**. No package registry is reachable (Maven Central, Gradle
Plugin Portal, PyPI, npm all return 403/000) and there is no local artifact
cache. Any approach that needs to download a dependency cannot build here. So the
PRD's "Option B - Java-only ML" path is taken to its dependency-free extreme:

- HTTP layer: `com.sun.net.httpserver` (JDK `jdk.httpserver` module) behind a
  hand-written `Router` / `HttpContext`.
- JSON: a hand-written serializer/parser (`com.frauddetector.http.Json`).
- Persistence: thread-safe in-memory repositories over `ConcurrentHashMap`.
- Security: `MessageDigest`/PBKDF2 password hashing and `Hmac`-signed tokens.
- Tests: a hand-rolled harness of plain `main()` classes (JUnit is unavailable).

A `pom.xml` is committed for documentation/portability of the intended Maven
coordinates, but it is **not** the sandbox build path and is not used by
`build.sh`.

## Architecture

Layered packages under `com.frauddetector`:

| Package        | Responsibility |
|----------------|----------------|
| `http`         | Micro-framework: `Router`, `Route`, `HttpContext`, `Json` codec, `ApiException`. |
| `security`     | `Role`, `PasswordHasher`, `TokenService`, `Principal`, `AuthFilter` (role checks), `AuditLog`. |
| `domain`       | Entities: `User`, `Patient`, `Provider`, `Claim`, `FraudAnalysis`, `RiskFactor`, `Investigation`, `Notification`. |
| `repository`   | Generic `InMemoryRepository<ID,T>` + per-entity repositories (claims, analyses, investigations, audit logs, notifications, ...). |
| `dto`          | Request/response views serialized through the Json codec. |
| `service`      | Business logic: `ValidationService`, `ClaimService`, `AuthService`, `FraudRiskService`, `InvestigationService`, `DashboardService`, `ReportService`, `AuditService`, `NotificationService`, `ProviderService`, and the ML scorers under `service.ml`. |
| `service.ml`   | Scoring layers: `FraudClassifier`, `AnomalyDetector`, `BillingAnomalyService`, `DuplicateDetector`, `ProviderRiskService`, `PatientRiskService`. |
| `controller`   | HTTP endpoints wiring services to routes. |
| `config`       | `ThresholdConfig` (risk bands), `Seed` (startup data). |

### Fraud-scoring engine

`FraudRiskService.analyze(claim)` runs each scoring layer, blends the supervised
fraud probability with an unsupervised anomaly score, and combines five
normalized layer scores into a single 0-100 **final risk score** using documented
weights (fraud 0.30, billing 0.25, duplicate 0.20, provider 0.15, patient 0.10).
The score is classified into a **risk level** via `ThresholdConfig`:

- `0-30` LOW, `31-60` MEDIUM, `61-80` HIGH, `81-100` CRITICAL (defaults;
  overridable via env vars).

Each analysis carries an ordered list of Explainable-AI `RiskFactor`s derived
from the actual feature values and scorer outputs.

## Endpoints

All API responses are JSON. Authenticated endpoints require an
`Authorization: Bearer <token>` header obtained from `POST /api/auth/login`.

### Auth
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| POST | `/api/auth/login` | public | Returns a signed bearer token. Audited (`LOGIN`). |
| POST | `/api/auth/logout` | public | No-op for stateless tokens. |

### Claims
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| POST | `/api/claims` | PROVIDER, CLAIM_OFFICER, ADMIN | Submit a claim; runs the fraud engine and stores the analysis. Audited (`CLAIM_SUBMIT`). CRITICAL/HIGH results create an investigator notification. |
| GET | `/api/claims/{id}` | any authenticated | Read a claim + its analysis. Audited (`CLAIM_VIEW`). |
| GET | `/api/claims` | any authenticated | List with search/filter + pagination (see below). |
| GET | `/api/claims/high-risk` | any authenticated | Claims whose latest analysis is HIGH or CRITICAL. |
| GET | `/api/claims/{id}/analysis` | any authenticated | Scores + risk factors. |

**Search / filter query params on `GET /api/claims`:**
`claimId`, `patientId`, `providerId`, `hospital` (hospitalId), `diagnosis`
(substring), `procedure` or `treatment` (substring), `dateFrom`, `dateTo`
(inclusive, ISO `yyyy-MM-dd`, on claim date), `riskLevel`
(`LOW|MEDIUM|HIGH|CRITICAL`), `investigationStatus` (`OPEN|CLOSED|NONE`),
`page` (1-based, default 1), `size` (default 50). Example:
`GET /api/claims?riskLevel=HIGH&providerId=PRV-001&page=1&size=20`.

### Investigations
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| POST | `/api/investigations` | INVESTIGATOR, ADMIN | Open an investigation for a claim. |
| PATCH | `/api/investigations/{id}` | INVESTIGATOR, ADMIN | Record a decision. Audited (`INVESTIGATION_DECISION`). |

### Dashboard
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| GET | `/api/dashboard` | any authenticated | KPI cards (`totalClaims`, `flaggedClaims`, `highRiskClaims`, `potentialSavings`) + chart datasets (claims over time by day/month, fraud trend, provider risk ranking, claim-amount distribution, fraud vs legitimate, high-risk procedures, geographic distribution). |

### Reports
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| GET | `/api/reports/claim/{id}` | ADMIN, CLAIM_OFFICER | Single-claim report (patient, provider, treatment, amount, risk score, risk factors, investigation status/decision). |
| GET | `/api/reports/provider/{id}` | ADMIN, CLAIM_OFFICER | Provider report: total claims, average claim, flagged claims, `fraudRate = flaggedClaims/totalClaims`, risk score. |
| GET | `/api/reports/fraud-analytics` | ADMIN, CLAIM_OFFICER | Totals, suspicious count, potential billing inflation, top risk providers, top risk treatments, monthly trends. |

### Notifications
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| GET | `/api/notifications` | any authenticated | Notifications for the caller's role (newest-first). `?unread=true` returns only unread. |
| POST | `/api/notifications/{id}/read` | any authenticated | Mark one read. |

### Providers
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| GET | `/api/providers` | any authenticated | All providers with a recomputed risk score + claim stats. |
| GET | `/api/providers/{id}` | any authenticated | A single provider with risk score + stats. |

### Audit & health
| Method | Path | Access | Notes |
|--------|------|--------|-------|
| GET | `/api/audit` | ADMIN | Recent audit entries (login, claim submission, claim view, investigation decision, user creation). |
| GET | `/api/health` | public | Liveness probe. |

## Seeded credentials

Printed to stdout on startup; for manual testing only (dev defaults):

| Role           | Email                      | Password        |
|----------------|----------------------------|-----------------|
| ADMIN          | `admin@fraud.local`        | `admin123`      |
| CLAIM_OFFICER  | `officer@fraud.local`      | `officer123`    |
| INVESTIGATOR   | `investigator@fraud.local` | `investigate123`|
| PROVIDER       | `provider@fraud.local`     | `provider123`   |

The seed also loads a few patients, providers, and baseline historical claims so
the ML layers have reference data from the first request.

## Build / test / run

Everything goes through `build.sh` (never `mvn`/`gradle` in this environment):

```sh
./build.sh clean build   # compile src/main -> out/, package out/app.jar
./build.sh test          # compile src/main + src/test, run every *Test main()
./build.sh run           # start the server (builds first if needed)
```

Requirements: a JDK on `PATH` (developed against OpenJDK 25). No downloads.

### Running and calling the API

The default port is `8080`; override with the `PORT` env var. Other env
overrides: `APP_SECRET`, `TOKEN_TTL_SECONDS`, and the `ThresholdConfig` vars
(`RISK_LOW_MAX`, `RISK_MEDIUM_MAX`, `RISK_HIGH_MAX`, `BILLING_ANOMALY_THRESHOLD`,
`DUPLICATE_THRESHOLD`).

```sh
PORT=18080 ./build.sh run &

# 1) Log in
TOKEN=$(curl -s --noproxy '*' -X POST http://127.0.0.1:18080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"officer@fraud.local","password":"officer123"}' \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 2) Dashboard KPIs + charts
curl -s --noproxy '*' http://127.0.0.1:18080/api/dashboard \
  -H "Authorization: Bearer $TOKEN"

# 3) Filtered claim search
curl -s --noproxy '*' "http://127.0.0.1:18080/api/claims?riskLevel=HIGH&page=1&size=10" \
  -H "Authorization: Bearer $TOKEN"

# 4) Fraud-analytics report
curl -s --noproxy '*' http://127.0.0.1:18080/api/reports/fraud-analytics \
  -H "Authorization: Bearer $TOKEN"
```

(When calling a locally bound server behind an HTTP proxy, use
`curl --noproxy '*'` or set `NO_PROXY=127.0.0.1,localhost` so the loopback call
is not routed through the proxy.)

## Deferred / not built (and why)

The following were intentionally **not** implemented because the sandbox is
network-isolated (INTEGRATIONS_ONLY): no registry, image pull, SMTP, or external
HTTP is reachable. Each has a clean in-code seam so it can be added later without
reworking the core.

- **JavaScript SPA frontend** - the backend already returns chart-ready JSON for
  every dashboard/report dataset, but no React/Vue/npm app is built because the
  JS toolchain and packages cannot be downloaded. The API is the deliverable; a
  SPA would consume `/api/dashboard` and the report endpoints as-is.
- **External database (Postgres/MySQL/H2)** - persistence is in-memory because no
  JDBC driver JAR can be fetched. Repositories are isolated behind
  `InMemoryRepository<ID,T>`, so a real datastore is a drop-in replacement.
- **Python ML microservice (scikit-learn, etc.)** - PyPI is unreachable, so the
  scoring runs as pure-Java heuristics/statistics in `service.ml` instead of an
  out-of-process Python model server.
- **Real email / SMS notifications** - no SMTP endpoint or network egress is
  available. Notifications are **in-app records only**; `NotificationService`
  is the single integration point where an external notifier (email/SMS/webhook)
  would hook in.
- **HTTPS/TLS termination** - the embedded `com.sun.net.httpserver` serves plain
  HTTP; TLS would normally be terminated by a reverse proxy/load balancer, which
  is out of scope for this sandbox.
