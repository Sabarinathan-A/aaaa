# Medical Claim Fraud & Inflated-Billing Detector

An AI/ML medical-insurance claim fraud detector with a browser UI. It validates
and stores claims, scores each one with a multi-layer fraud engine (trained
logistic-regression classifier, Isolation Forest, billing deviation, duplicate
detection, provider and patient behavior), explains every score, and supports
the full review workflow: claim-officer review, investigations with evidence,
investigator decisions that feed back into the model, dashboards, reports,
notifications, user management and an audit trail.

Everything uses only the Java standard library (no Spring, no JDBC driver, no
Python, no npm), so it builds and runs with nothing but a JDK.

## Quick start

Requirements: JDK 21 or newer.

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File run.ps1          # build + start on http://localhost:8080
powershell -ExecutionPolicy Bypass -File run.ps1 -Test    # build + run all tests
```

```sh
# macOS / Linux
./build.sh clean build run
./build.sh test
```

Open **http://localhost:8080** and use one of the demo logins (dev profile only):

| Role           | Email                      | Password         |
|----------------|----------------------------|------------------|
| ADMIN          | `admin@fraud.local`        | `admin123`       |
| CLAIM_OFFICER  | `officer@fraud.local`      | `officer123`     |
| INVESTIGATOR   | `investigator@fraud.local` | `investigate123` |
| PROVIDER       | `provider@fraud.local`     | `provider123`    |

The demo seed loads 15 patients, 3 providers and a year of 51 historical claims,
so dashboards, baselines and the anomaly model have data from the first request.

## Web UI

Single page served at `/` (`src/main/resources/web/index.html`, vanilla JS, no
CDN, works offline). Menus adapt to the logged-in role.

- **Dashboard**: KPI cards and charts (claims over time, fraud trend, provider risk, amount distribution, fraud vs legitimate, high-risk procedures, geography).
- **Claims**: search and filter on every PRD field plus pagination, high-risk queue, and a claim details page showing the score, layer bars, "why flagged" factors and billing breakdown, with Approve / Reject / Escalate / Open investigation / Correct actions.
- **Submit claim**: form with test presets (normal, inflated, long stay, invalid dates, duplicate of latest).
- **Investigations**: list and filter, case view, decisions, and evidence upload/download.
- **ML model**: recall, precision, F1, ROC-AUC, confusion matrix, learned weights, training history, and retrain with feedback (admin).
- **Patients and Providers**: registry, claim history, behavior risk; masked identity for providers.
- **Reports**: fraud analytics, claim report, provider report (JSON download or print).
- **Notifications, Audit log, Users, Profile** (password change), and an **API console** for testing any endpoint with your token.

## Fraud-scoring engine

`FraudRiskService.analyze(claim)` combines five layer scores into a 0-100
**final risk score** (weights: fraud 0.30, billing 0.25, duplicate 0.20,
provider 0.15, patient 0.10) and classifies it via `ThresholdConfig`:
`0-30` LOW, `31-60` MEDIUM, `61-80` HIGH, `81-100` CRITICAL (env-overridable).

| Layer | Implementation |
|-------|----------------|
| Supervised fraud model | `ml/LogisticModel` trained by `ml/ModelTrainer` (gradient descent, L2, class-balanced, 80/20 hold-out). |
| Anomaly detection | `ml/IsolationForest` (100 trees, psi 256) blended with a z-score detector. |
| Inflated billing | `ml/BillingAnomalyService`: deviation from a clean per-procedure baseline. |
| Duplicates | `ml/DuplicateDetector`: weighted similarity on patient, provider, dates, amount, diagnosis, procedure. |
| Provider / patient behavior | `ml/ProviderRiskService`, `ml/PatientRiskService`. |

**Training data.** No labeled medical-claims dataset can be bundled, so
`ml/TrainingDataset` generates a deterministic synthetic population (2,000
claims, 25% fraud) encoding the PRD fraud patterns: inflated charges,
duplicates, long stays with room padding, frequent claims and padded
components. It also includes "subtle" cases and 2% label noise. Typical hold-out
results are about 0.90 recall, 0.79 precision and 0.96 ROC-AUC.

**Feedback loop.** Investigator decisions become labels: REJECT and
MARK_SUSPICIOUS count as fraud, APPROVE and FALSE_POSITIVE as legitimate, each
weighted 5x. `POST /api/ml/retrain` retrains with them and can re-score all
claims. The model is retrained deterministically at startup, so feedback
persists through the stored investigations.

## Claim lifecycle

`SUBMITTED` -> (officer) `APPROVED` / `REJECTED` / `ESCALATED`, or
(investigator opens case) `UNDER_INVESTIGATION` -> decision:
APPROVE / FALSE_POSITIVE -> `APPROVED`, REJECT -> `REJECTED`,
MARK_SUSPICIOUS -> `SUSPICIOUS`, ESCALATE -> `ESCALATED`,
REQUEST_DOCUMENTS -> `DOCUMENTS_REQUESTED`.
Claims can be corrected (re-validated and re-scored) only while `SUBMITTED` or
`DOCUMENTS_REQUESTED`.

## API

All responses are JSON. Authenticated endpoints need `Authorization: Bearer <token>`
from `POST /api/auth/login`. Unknown path returns 404, wrong method 405, missing or invalid
token 401, wrong role 403.

| Method | Path | Roles |
|--------|------|-------|
| POST | `/api/auth/login`, `/api/auth/logout` | public |
| GET | `/api/health` | public |
| GET | `/api/me`; POST `/api/me/password` | any |
| GET, POST | `/api/users`; GET, PATCH `/api/users/{id}` | ADMIN |
| POST | `/api/claims` | PROVIDER, CLAIM_OFFICER, ADMIN |
| GET | `/api/claims` (filters below), `/api/claims/{id}`, `/api/claims/high-risk`, `/api/claims/{id}/analysis` | any |
| PUT | `/api/claims/{id}` (correction) | PROVIDER, CLAIM_OFFICER, ADMIN |
| POST | `/api/claims/{id}/review` `{action: APPROVE\|REJECT\|ESCALATE}` | CLAIM_OFFICER, ADMIN |
| GET | `/api/investigations` (`status`, `claimId`, `decision`), `/api/investigations/{id}` | INVESTIGATOR, ADMIN, CLAIM_OFFICER |
| POST | `/api/investigations`; PATCH `/api/investigations/{id}` | INVESTIGATOR, ADMIN |
| GET, POST | `/api/investigations/{id}/evidence`; GET `/api/evidence/{id}/download` | INVESTIGATOR, ADMIN |
| GET | `/api/patients?q=`, `/api/patients/{id}` (masked for PROVIDER) | any |
| POST | `/api/patients` | ADMIN, CLAIM_OFFICER |
| GET | `/api/providers`, `/api/providers/{id}` | any |
| POST, PUT | `/api/providers`, `/api/providers/{id}` | ADMIN |
| GET | `/api/dashboard` | any |
| GET | `/api/reports/claim/{id}`, `/api/reports/provider/{id}`, `/api/reports/fraud-analytics` | ADMIN, CLAIM_OFFICER |
| GET | `/api/notifications?unread=true`; POST `/api/notifications/{id}/read` | any |
| GET | `/api/ml/model` | ADMIN, CLAIM_OFFICER, INVESTIGATOR |
| POST | `/api/ml/retrain` `{rescore: true}` | ADMIN |
| GET | `/api/audit` | ADMIN |

`GET /api/claims` filters: `claimId`, `patientId`, `providerId`, `hospital`,
`diagnosis`, `procedure`/`treatment`, `dateFrom`, `dateTo`, `riskLevel`,
`investigationStatus` (`OPEN|CLOSED|NONE`), `status`, `page`, `size`.

## Security

- PBKDF2 password hashing with minimum length 8. HMAC-SHA256 signed tokens, with the signature compared in canonical form and in constant time.
- Role-based access on every endpoint. Disabled accounts cannot log in, and their existing tokens stop working immediately. Admins cannot lock themselves out.
- Sensitive-data masking: PROVIDER sees patient names and insurance IDs masked (`J*** D**`, `****1001`) and cannot search by name.
- Secure evidence upload accepts only PDF, PNG, JPEG and TXT up to 5 MB, and checks the file's magic bytes. Files are stored under server-generated names, so client file names cannot affect paths.
- The UI renders all data as text nodes (no `innerHTML`), so API data cannot inject markup.
- Audit trail records login, claim submit/view/modify/review, patient view, investigation open/decision, evidence upload, user create/update, provider update and model update.
- HTTPS is optional (see configuration). In production, either enable it or terminate TLS at a reverse proxy.

## Persistence

Data is stored in memory and written as a JSON snapshot to `data/snapshot.json`.
Writes are atomic (temp file plus move), the previous snapshot is kept as
`.bak`, saves happen within 2 s of a change and again on shutdown. Evidence
files go in `data/evidence/` and notification emails in `data/outbox/`. On
startup an existing snapshot is restored and the demo seed is skipped. Delete
the `data/` folder to reset. Repositories sit behind `InMemoryRepository`, so a
real database can replace this layer without touching the services.

## Configuration (environment variables)

| Variable | Purpose |
|----------|---------|
| `PORT` | Listen port (default 8080). |
| `APP_ENV` | `dev` (default) enables the demo seed and dev secret. Any other value requires `APP_SECRET` and starts empty. |
| `APP_SECRET`, `TOKEN_TTL_SECONDS` | Token signing secret and lifetime (default 3600). |
| `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD` | Create the first admin when no users exist (non-dev). |
| `DATA_DIR`, `PERSISTENCE=off` | Data folder (default `data`); disable persistence. |
| `HTTPS_KEYSTORE`, `HTTPS_KEYSTORE_PASSWORD` | Serve HTTPS (TLS 1.2/1.3) from a PKCS12 keystore, e.g. `keytool -genkeypair -alias fraud -keyalg RSA -keysize 2048 -validity 365 -storetype PKCS12 -keystore data/keystore.p12 -dname CN=localhost`. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`, `SMTP_STARTTLS` | Also send notification emails over SMTP (STARTTLS + AUTH LOGIN). Without these, emails are only written to the outbox. |
| `NOTIFY_WEBHOOK_URL` | POST each notification as JSON (Slack/Teams/SMS relay). |
| `RISK_LOW_MAX`, `RISK_MEDIUM_MAX`, `RISK_HIGH_MAX`, `BILLING_ANOMALY_THRESHOLD`, `DUPLICATE_THRESHOLD` | Risk thresholds. |

## Project layout

| Package | Responsibility |
|---------|----------------|
| `http` | Router, HttpContext, JSON codec, ApiException. |
| `security` | Roles, password hashing, tokens, AuthFilter, DataMasker, AuditLog. |
| `domain` | User, Patient, Provider, Claim, FraudAnalysis, RiskFactor, Investigation, Evidence, Notification. |
| `repository` | `InMemoryRepository` plus per-entity repositories. |
| `persistence` | `SnapshotStore` (durable JSON snapshot). |
| `service` | Claim, validation, fraud risk, investigation, evidence, patient, provider, user, dashboard, report, audit, notification and ML lifecycle services. |
| `service.ml` | Classifier, trainer, metrics, dataset, Isolation Forest, billing, duplicate, provider and patient scorers. |
| `notify` | Email (outbox + SMTP) and webhook notifiers. |
| `controller` | HTTP endpoints and the web UI. |
| `config` | Thresholds and demo seed. |

## Tests

There are 21 test classes in a zero-dependency harness, covering the JSON codec,
router, hashing, tokens, validation, scoring layers, explainability,
repeat-inflation regression, dashboard, search, reports, audit, notifications,
the claim workflow, evidence upload security, user management and masking,
snapshot persistence, and model training and metrics.

## Known limitations

- The fraud model is trained on synthetic labels plus investigator feedback; its metrics describe the synthetic hold-out set, not real-world performance. Replace `TrainingDataset.synthetic` with a real labeled dataset when one is available.
- The JSON snapshot suits a single server instance and demo-to-medium data volumes. Use a real database for multi-instance or large deployments.
- SMTP delivery is implemented but has not been tested against a live mail server here. The outbox file is always written.
