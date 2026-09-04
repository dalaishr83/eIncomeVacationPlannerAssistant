# Holiday Leave Assistant

AI-powered holiday leave management application built with **Java 8 + Spring Boot 2.7** (backend) and a vanilla **JavaScript SPA** (frontend).

Ask questions about employee leave data in plain English, add or delete vacation entries through a guided chat wizard, and generate polished HTML leave reports — all grounded exclusively on your own `.xlsx` data.

---

## Table of Contents

1. [Features](#features)
2. [Roles and Access Control](#roles-and-access-control)
3. [Architecture](#architecture)
4. [Quick Start — Local](#quick-start--local)
5. [Docker](#docker)
6. [Podman](#podman)
7. [Environment Variables](#environment-variables)
8. [Credential Management](#credential-management)
9. [LLM Provider Configuration](#llm-provider-configuration)
10. [API Reference](#api-reference)
11. [Key Design Notes](#key-design-notes)
12. [Troubleshooting](#troubleshooting)

---

## Features

- **Conversational leave queries** — ask anything about employee holidays; answers are grounded strictly on your Excel data (no hallucinations).
- **Add vacation wizard** — guided multi-step chat flow to book leave for any employee.
- **Delete vacation wizard** — guided multi-step chat flow to remove existing leave entries.
- **HTML report generation** — one-command generation of a full-featured leave dashboard per employee per year, including KPI cards, utilization progress bar, three Chart.js charts, and colour-coded leave-type badges.
- **Multi-file support** — upload multiple `.xlsx` planners and switch between them at runtime.
- **Role-based access** — separate **Admin** and **Employee** login accounts with distinct UIs and backend authorization.
- **Admin settings** — enable or disable vacation types at runtime; changes are immediately reflected in employee prompts and validated server-side.
- **Role management** — admin can promote employees to admin or demote admins back to employee role.
- **PC vacation approvals** — admin can review Personal Choice Holiday (PC) entries and approve them as Vacation (V) in the working file.
- **File management** — admin can upload new planner files and delete existing ones with immediate UI refresh.
- **Password management** — admin can reset any credential password at any time without restarting the application.
- **Auto-provisioned employee credentials** — when an Excel file is uploaded, login accounts are automatically created for every employee found in the roster. Default password: `test1234`.
- **File-backed credentials** — login credentials are stored in `data/secret/secret.json` (BCrypt-hashed, username-keyed); no database required.
- **Restricted vacation types** — admin-controlled list of disabled leave types stored in `data/restrictedVacationType/restricted-vacation-types.json`.
- **IBM Box sync** — optional background upload of the master Excel file to IBM Box after every successful write, using JWT or Client Credentials Grant (CCG) authentication.
- **OpenAI-compatible LLM** — works with Ollama (local), OpenAI, Groq, OpenRouter, IBM Watsonx, or any endpoint that speaks the OpenAI chat completions API.
- **Audit log** — every login, vacation change, file operation, and admin action is appended to `data/audit.log` in JSONL format.
- **Human-readable dates** — all dates in chat and reports are displayed as `dd MMM yyyy` (e.g. `01 Aug 2026`).
- **Docker & Podman ready** — single-command container startup with health checks.

---

## Roles and Access Control

The application has two built-in roles. Each role sees a tailored UI and is enforced at the backend — unauthorized API calls return `403 Forbidden`, not just hidden buttons.

| Capability | Admin | Employee |
|---|:---:|:---:|
| Chat with AI assistant | ✅ | ✅ |
| Ask leave questions | ✅ | ✅ |
| Add / delete their own leave via wizard | ✅ | ✅ |
| Add / delete leave for any employee | ✅ | ❌ |
| Generate HTML reports | ✅ | ✅ |
| View loaded employees | ✅ | ✅ |
| Quick Questions panel | ✅ | ✅ |
| Upload `.xlsx` planner files | ✅ | ❌ |
| View Available Files list | ✅ | ❌ |
| Delete files | ✅ | ❌ |
| PC vacation approvals (`/admin/approvals`) | ✅ | ❌ |
| Manage restricted vacation types (`/admin/settings`) | ✅ | ❌ |
| Promote / demote user roles | ✅ | ❌ |
| Reset any user's password | ✅ | ❌ |
| View audit log (`/admin/audit-log`) | ✅ | ❌ |
| Request a restricted vacation type | ❌ | ❌ |

### Default credentials

On first boot, the admin credential is bootstrapped into `data/secret/secret.json` from the `.env` values. When an Excel file is uploaded, employee credentials are auto-provisioned from the roster.

| Role | Default username | Default password |
|---|---|---|
| Admin | `admin` (or `LOGIN_USERNAME` from env) | Password matching `LOGIN_PASSWORD_HASH` in `.env`, or `admin` if unset |
| Employee | camelCase first name (e.g. `birgitte`) | `test1234` |

> **Change passwords immediately** after first login. Use the Admin Settings page (`/admin/settings`) or the password-reset API.

### Login flow

Both roles share the same `/login` page. After successful authentication the server sets a `role` session attribute (`"admin"` or `"employee"`) alongside `logged_in` and `employee_name`. The root URL (`/`) routes to `admin-page` or `employee-page` based on that session attribute. Employee users can only modify their own leave data — cross-employee modifications are blocked at the backend even if attempted via direct API calls.

---

## Architecture

```
holiday-leave-assistant/
├── backend/
│   └── src/main/
│       ├── java/com/holidayleave/assistant/
│       │   ├── HolidayLeaveAssistantApplication.java   ← Entry point; custom .env loader
│       │   ├── config/
│       │   │   ├── AppProperties.java                  ← Typed env-var bindings
│       │   │   ├── SecurityConfig.java                 ← Disables Spring Security defaults
│       │   │   └── WebConfig.java                      ← AuthInterceptor registration
│       │   ├── model/
│       │   │   ├── LeaveRecord.java                    ← Immutable leave span domain object
│       │   │   ├── VacationType.java                   ← Code + label + ARGB colour
│       │   │   ├── LeaveAnalysisResult.java             ← Analytics output (stateless)
│       │   │   ├── PendingVacation.java                ← Wizard session state machine
│       │   │   ├── AuditLogEntry.java
│       │   │   └── FileInfo.java
│       │   ├── analysis/
│       │   │   └── LeaveAnalysisService.java           ← Consumed/remaining/by-month/streak/trend
│       │   ├── excel/
│       │   │   ├── PlannerExcelReader.java             ← Auto-discovery parser for eIndkomst .xlsx
│       │   │   └── WorkingExcelWriter.java             ← Atomic cell-level writes to working copy
│       │   ├── llm/
│       │   │   ├── LLMService.java                     ← Interface
│       │   │   └── OpenAIAdapter.java                  ← OpenAI-compatible HTTP adapter (WebClient)
│       │   ├── service/
│       │   │   ├── HolidayAgent.java                   ← Intent routing + context building + LLM call
│       │   │   ├── VacationCreationService.java        ← Add-vacation multi-step wizard
│       │   │   ├── VacationDeletionService.java        ← Delete-vacation multi-step wizard
│       │   │   ├── VacationTypeService.java            ← Leave type CRUD (vacation_types.json)
│       │   │   ├── RestrictedVacationTypeService.java  ← Admin-controlled disabled types
│       │   │   ├── SecretService.java                  ← File-backed BCrypt credential store
│       │   │   ├── AuditService.java                   ← Append-only JSONL audit log
│       │   │   ├── SyncService.java                    ← Background working→master file sync
│       │   │   ├── BoxSyncService.java                 ← Optional IBM Box cloud upload
│       │   │   ├── AppState.java                       ← Singleton app state + session-keyed wizard state
│       │   │   ├── AuthInterceptor.java                ← Session auth + role enforcement on every request
│       │   │   └── ReportGenerator.java                ← Self-contained HTML report builder (Chart.js)
│       │   └── controller/
│       │       ├── AuthController.java                 ← GET/POST /login, GET /logout
│       │       ├── AdminController.java                ← Admin pages + /api/admin/** REST endpoints
│       │       ├── ChatController.java                 ← POST /api/chat + wizard dispatch
│       │       ├── FileController.java                 ← Upload / list / switch / employees / years
│       │       ├── VacationController.java             ← POST/DELETE /api/vacations + vacation-types
│       │       ├── ReportsController.java              ← Serve reports + sync-status
│       │       └── IndexController.java                ← Role-aware page routing at GET /
│       └── resources/
│           ├── templates/
│           │   ├── admin-page.html                     ← Admin SPA shell (upload, files, chat)
│           │   ├── employee-page.html                  ← Employee SPA shell (chat, quick questions)
│           │   ├── admin-settings.html                 ← Restricted types + password reset + role mgmt
│           │   ├── admin-approvals.html                ← PC vacation approval table
│           │   ├── admin-audit-log.html                ← Audit log viewer
│           │   └── login.html
│           ├── static/
│           │   ├── holiday_agent_admin.js              ← Admin SPA JS (upload, file delete, settings)
│           │   ├── holiday_agent.js                    ← Employee SPA JS (chat, quick questions)
│           │   └── holiday_agent.css                   ← Shared stylesheet
│           └── application.properties
├── data/                                               ← Runtime data (bind-mounted; gitignored)
│   ├── secret/
│   │   └── secret.json                                 ← BCrypt credentials (auto-created on first boot)
│   ├── restrictedVacationType/
│   │   └── restricted-vacation-types.json             ← Disabled type codes (auto-created)
│   ├── vacation_types.json                             ← Leave type definitions (auto-created)
│   ├── audit.log                                       ← Append-only JSONL audit trail
│   ├── working/                                        ← Write-ahead working copies (.xlsx)
│   └── uploads/                                        ← Upload staging copies (.xlsx)
├── reports/                                            ← Generated HTML leave reports (bind-mounted)
├── diagrams/
│   ├── use-case-diagram.drawio
│   └── component-diagram.drawio
├── Dockerfile
├── docker-compose.yml
├── podman-compose.yml
└── .env.example
```

### Tech stack

| Layer | Technology |
|---|---|
| Language | Java 8 (compiled target); JDK 21 used inside Docker image |
| Framework | Spring Boot 2.7.18 |
| Security | Spring Security + BCrypt (custom session-based auth via `AuthInterceptor`) |
| Templates | Thymeleaf 3 |
| Excel I/O | Apache POI 5.2.5 |
| HTTP client | Spring WebFlux (WebClient) for LLM calls |
| Frontend | Vanilla JavaScript (one IIFE per role) |
| Charts | Chart.js 4.4.3 (embedded in generated HTML reports) |
| Container | Docker / Podman |

### Request flow

```
Browser → AuthInterceptor (session check + role guard)
       → Controller (role-specific dispatch)
       → HolidayAgent (intent detection + context building)
       → OpenAIAdapter (WebClient → LLM endpoint)
       → LeaveAnalysisService (stateless analytics)
       → PlannerExcelReader (cached .xlsx parse)
       → WorkingExcelWriter (atomic cell write)
       → SyncService (working copy → master, then optionally IBM Box)
```

---

## Quick Start — Local

### Prerequisites

- Java 21+ (required by the `eclipse-temurin:21` builder image and recommended locally)
- Maven 3.6+
- An LLM endpoint — [Ollama](https://ollama.com) running locally is the default

### 1. Start Ollama (default LLM)

```bash
ollama serve
ollama pull llama3.2
```

Ollama must be running before the app starts. It is reached at `http://127.0.0.1:11434/v1` by default.

> **Note:** Use `127.0.0.1` rather than `localhost`. On machines with Docker Desktop installed,
> `localhost` may resolve to the Docker bridge IP (`172.17.0.2`) instead of the loopback interface,
> causing connection timeouts.

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env — set LOGIN_PASSWORD_HASH at minimum
```

The `.env.example` ships with `FLASK_PORT=8080`. If you omit the `.env` file entirely, the application falls back to the `application.properties` default of **port 8085**.

Generate a BCrypt password hash for the initial admin password:

```bash
# Python (bcrypt must be installed: pip install bcrypt)
python -c "import bcrypt; print(bcrypt.hashpw(b'yourpassword', bcrypt.gensalt()).decode())"

# Or htpasswd (Apache utils)
htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'
```

> **After first boot**, you can change passwords via the Admin Settings page (`/admin/settings`) or the API — no restart required.

### 3. Build and run

```bash
cd backend
mvn package -DskipTests
java -jar target/holiday-leave-assistant-1.0.0.jar
```

Or run directly with Maven (no JAR needed during development):

```bash
cd backend
mvn spring-boot:run
```

> ⚠️ After any change to `application.properties` you **must rebuild** the JAR.
> Spring Boot reads config from inside the packaged JAR, not from the source tree.

Open **http://localhost:8080** (when using the shipped `.env.example`) or **http://localhost:8085** (fallback default when no `.env` is present).

### 4. First login

| Role | Username | Password |
|---|---|---|
| Admin | Value of `LOGIN_USERNAME` (default: `admin`) | Password matching `LOGIN_PASSWORD_HASH` in `.env` |

1. Log in as **admin**.
2. Open the sidebar and upload your `eIndkomst vacation <year>.xlsx` file.
3. Employee credentials are auto-provisioned from the roster (default password: `test1234`).
4. Start chatting, or use the **Admin** nav links to manage settings, approvals, and the audit log.

---

## Docker

```bash
# Build image and start container
docker compose up --build

# Start in background
docker compose up -d --build

# Stop
docker compose down
```

The container is named `holiday-leave-assistant`, tagged as `holiday-leave-assistant:latest`, and exposes port **8080**. `FLASK_PORT=8080` is set automatically inside the container so the Spring Boot server binds on the same port that is exposed.

When Ollama runs on the host machine, set in `.env`:

```
LLM_BASE_URL=http://host.docker.internal:11434/v1
```

Data and reports are persisted in bind-mounted local directories:

| Host path | Container path | Contents |
|---|---|---|
| `./data` | `/app/data` | Master `.xlsx` files, uploads, working copies, `secret/`, `restrictedVacationType/`, `audit.log`, `vacation_types.json` |
| `./reports` | `/app/reports` | Generated HTML leave reports |

A health check polls `http://localhost:8080/login` every 30 seconds (10 s timeout, 3 retries, 40 s start-up grace period) and marks the container healthy once it passes.

---

## Podman

```bash
# Install podman-compose (once)
pip install podman-compose   # v1.0.6+

# Build image and start container
podman-compose -f podman-compose.yml up -d --build

# Stop
podman-compose -f podman-compose.yml down
```

The Podman compose file uses `restart: always` (more reliably honoured than `unless-stopped` across `podman-compose` versions) and defines an explicit `app-net` bridge network.

**Rootless Podman — Ollama on the host:**

`127.0.0.1` inside a rootless Podman container does **not** map to the host. Use the special DNS name instead:

```
LLM_BASE_URL=http://host.containers.internal:11434/v1
```

**SELinux hosts (Fedora / RHEL / CentOS):**

The `podman-compose.yml` appends `:Z` to volume mounts, which relabels them for SELinux. Remove `:Z` if your host does not enforce SELinux.

---

## Environment Variables

All variables can be set in `.env` (copy from `.env.example`) or passed directly as environment variables. Values in `.env` take precedence over `application.properties` defaults. OS-level environment variables take precedence over `.env`.

### Core variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `LOGIN_USERNAME` | | `admin` | Username for the **admin** account (bootstrapped into `secret.json` on first boot) |
| `LOGIN_PASSWORD_HASH` | ✅ | — | BCrypt hash of the **admin** password (bootstrapped into `secret.json` on first boot) |
| `OPENAI_API_KEY` | | `""` | API key for cloud LLM providers. Leave empty for local Ollama |
| `LLM_BASE_URL` | | `http://127.0.0.1:11434/v1` | Any OpenAI-compatible chat completions endpoint |
| `LLM_MODEL` | | `llama3.2` | Model name served by the endpoint |
| `LLM_TEMPERATURE` | | `0.0` | Sampling temperature (0 = deterministic) |
| `LLM_MAX_TOKENS` | | `1024` | Maximum response tokens |
| `WATSONX_PROJECT_ID` | | `""` | IBM Watsonx project ID (leave empty if not using Watsonx) |
| `DATA_DIR` | | `data` | Directory for master `.xlsx` files, uploads, `secret/`, `restrictedVacationType/`, and `audit.log` |
| `REPORT_OUTPUT_DIR` | | `reports` | Directory where generated HTML reports are saved |
| `FLASK_PORT` | | `8085` *(app default)* / `8080` *(.env.example & compose)* | Server port |
| `SYNC_INTERVAL_SECONDS` | | `300` *(app default)* / `30` *(.env.example)* | How often `SyncService` checks for working-copy changes |
| `PERMANENT_SESSION_LIFETIME` | | `3600` | Session timeout in seconds |
| `LOG_LEVEL` | | `INFO` | Logging level for `com.holidayleave.*` |

### IBM Box sync variables (optional)

Set `BOX_ENABLED=true` to upload the master Excel file to IBM Box after every successful write. All Box variables are ignored when `BOX_ENABLED` is `false` (default).

| Variable | Description |
|---|---|
| `BOX_ENABLED` | `true` to enable; `false` (default) to disable |
| `BOX_CLIENT_ID` | Box application client ID |
| `BOX_CLIENT_SECRET` | Box application client secret |
| `BOX_ENTERPRISE_ID` | Box enterprise ID |
| `BOX_FOLDER_ID` | Numeric Box folder ID where files are uploaded |
| `BOX_JWT_PRIVATE_KEY` | JWT private key (PEM). Leave empty to use CCG instead |
| `BOX_JWT_PRIVATE_KEY_PASSPHRASE` | Passphrase for the JWT private key |
| `BOX_JWT_PUBLIC_KEY_ID` | JWT key ID registered in the Box developer console |
| `BOX_RETRY_BACKOFF_SECONDS` | Base back-off interval for Box upload retries (default: `60`) |

> **Port note:** `application.properties` defaults `FLASK_PORT` to `8085`. The shipped `.env.example` and both compose files override it to `8080`. If you run `java -jar` without any `.env`, the server starts on **8085**.

---

## Credential Management

Credentials are stored in **`data/secret/secret.json`** — a BCrypt-hashed JSON file created automatically on first boot. This file is the sole source of truth for authentication; `.env` variables are only used to seed the admin entry on first boot.

### secret.json format

The file is keyed by username. Each entry stores the BCrypt hash, role, and the employee's full name as it appears in the Excel roster (or `null` for the admin account):

```json
{
  "admin": {
    "username": "admin",
    "hash": "$2b$12$...",
    "role": "admin",
    "employee_name": null
  },
  "birgitte": {
    "username": "birgitte",
    "hash": "$2a$10$...",
    "role": "employee",
    "employee_name": "Birgitte Dam Christensen"
  }
}
```

### Employee auto-provisioning

When an admin uploads an Excel file, `SecretService.provisionEmployee()` is called for every employee name found in the roster. Usernames are generated using camelCase progressive expansion:

1. Base: first name token, lower-cased (e.g. `birgitte`).
2. On collision: append next token capitalised (e.g. `birgitteDam`), then `birgitteDamChristensen`.
3. Last resort: append an incrementing numeric suffix.

Provisioning is idempotent — re-uploading the same file does not create duplicate accounts.

### Changing passwords

**Via Admin Settings UI** (recommended):

1. Log in as admin and navigate to **Admin → Settings** (`/admin/settings`).
2. Select the credential (username), enter the new password, and click **Reset Password**.
3. The new BCrypt hash is written to `secret.json` immediately — no restart required.

**Via API** (admin role required):

```bash
curl -X POST http://localhost:8080/api/admin/settings/password-reset \
  -H "Content-Type: application/json" \
  -b "SESSION=<your-session-cookie>" \
  -d '{"role": "birgitte", "new_password": "newpassword123"}'
```

**Manually** (e.g. in emergency):

1. Stop the application.
2. Generate a new hash: `htpasswd -bnBC 12 "" newpassword | tr -d ':\n'`
3. Edit `data/secret/secret.json` and replace the relevant `hash` value.
4. Restart the application.

### Role management

Admins can promote an employee to admin or demote an admin to employee:

```bash
# Promote
curl -X POST http://localhost:8080/api/admin/settings/promote \
  -H "Content-Type: application/json" \
  -b "SESSION=<cookie>" \
  -d '{"usernames": ["birgitte"]}'

# Demote
curl -X POST http://localhost:8080/api/admin/settings/demote \
  -H "Content-Type: application/json" \
  -b "SESSION=<cookie>" \
  -d '{"usernames": ["birgitte"]}'
```

### Restricted vacation types

The admin can disable any vacation type at runtime. Disabled types are:
- Hidden from employee wizard prompts and type lists.
- Rejected server-side with a `403` response if submitted via API.
- Stored in `data/restrictedVacationType/restricted-vacation-types.json` as a JSON array of type codes.

Manage via the **Admin Settings** page (`/admin/settings`) or the API:

```bash
# View current restricted types
curl http://localhost:8080/api/admin/settings/restricted-types -b "SESSION=<cookie>"

# Update restricted types
curl -X POST http://localhost:8080/api/admin/settings/restricted-types \
  -H "Content-Type: application/json" \
  -b "SESSION=<cookie>" \
  -d '{"restricted_types": ["PC"]}'
```

---

## LLM Provider Configuration

The app communicates with any **OpenAI-compatible** chat completions endpoint. Switch provider by setting `LLM_BASE_URL` and `LLM_MODEL` in `.env`.

| Provider | `LLM_BASE_URL` | Example `LLM_MODEL` |
|---|---|---|
| **Ollama** (default, local) | `http://127.0.0.1:11434/v1` | `llama3.2` |
| **Ollama in Docker** | `http://host.docker.internal:11434/v1` | `llama3.2` |
| **Ollama in rootless Podman** | `http://host.containers.internal:11434/v1` | `llama3.2` |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` |
| **Groq** | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| **OpenRouter** | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` |
| **IBM Watsonx** | `https://<region>.ml.cloud.ibm.com/ml/v1` | `meta-llama/llama-3-3-70b-instruct` |

For IBM Watsonx, also set `WATSONX_PROJECT_ID` to your project ID. The adapter appends `?project_id=<id>` to the `/chat/completions` endpoint path.

---

## API Reference

### Public endpoints (no auth required)

| Method | Path | Description |
|---|---|---|
| `GET` | `/login` | Login page |
| `POST` | `/login` | Authenticate (`username` + `password` form params) |
| `GET` | `/logout` | Sign out and invalidate session |

### Authenticated endpoints (any logged-in role)

| Method | Path | Description |
|---|---|---|
| `GET` | `/` | Main page (routes to admin or employee view based on session role) |
| `POST` | `/api/chat` | Chat — analytical query or wizard step |
| `POST` | `/api/clear-history` | Clear conversation history |
| `GET` | `/api/employees` | List employees in the active file |
| `GET` | `/api/years` | List available years in the active file |
| `GET` | `/api/files` | List all known Excel files |
| `POST` | `/api/switch-file` | Switch the active Excel file |
| `POST` | `/api/vacations` | Add a vacation entry (employee role: own data only; restricted types rejected 403) |
| `DELETE` | `/api/vacations` | Delete a vacation entry (employee role: own data only) |
| `GET` | `/api/vacation-types` | List all configured leave types |
| `POST` | `/api/vacation-types` | Add a new leave type (any authenticated user) |
| `PUT` | `/api/vacation-types/{code}` | Update an existing leave type |
| `GET` | `/reports/{filename}` | Serve a generated HTML report |
| `GET` | `/api/reports/{filename}` | Serve a generated HTML report (alias) |
| `GET` | `/api/sync-status` | Background sync-daemon status |

### Admin-only endpoints (role = `admin`)

| Method | Path | Description |
|---|---|---|
| `GET` | `/admin/settings` | Settings page (restricted types, password reset, role management) |
| `GET` | `/admin/approvals` | PC vacation approval page |
| `GET` | `/admin/audit-log` | Audit log viewer page |
| `POST` | `/api/upload` | Upload an `.xlsx` planner file (auto-provisions employee credentials) |
| `DELETE` | `/api/admin/files` | Delete a master file (also removes working + upload copies) |
| `GET` | `/api/admin/audit-log` | Return all audit log entries (most-recent first) |
| `GET` | `/api/admin/settings/restricted-types` | Get current restricted vacation type codes |
| `POST` | `/api/admin/settings/restricted-types` | Replace restricted types list |
| `GET` | `/api/admin/settings/employee-credentials` | List all employee-role credentials |
| `GET` | `/api/admin/settings/admin-credentials` | List all admin-role credentials with an employee_name |
| `POST` | `/api/admin/settings/promote` | Set role = `admin` for one or more usernames |
| `POST` | `/api/admin/settings/demote` | Set role = `employee` for one or more usernames |
| `POST` | `/api/admin/settings/password-reset` | Reset password for any credential by username |
| `GET` | `/api/admin/approvals/pc-records` | List all PC vacation entries in the loaded file |
| `POST` | `/api/admin/approvals/approve-pc` | Convert selected PC entries to Vacation (V) in working file |

> Non-admin access to admin-only API routes returns **`403 Forbidden`** (JSON body `{"error": "..."}`). Non-admin access to admin-only page routes redirects to `/`.

### Chat request / response

```json
// POST /api/chat
{ "message": "How many days has Alice taken in 2026?" }

// Response — analytical query
{ "reply": "Alice has taken 14 days in 2026 ...", "type": "text" }

// Response — wizard prompt (add or delete wizard step)
{ "reply": "Please confirm:\n* Employee: ...", "type": "vacation_prompt" }

// Response — report generated
{ "reply": "Report generated.\nreport-file: /app/reports/alice_2026_leave_report.html", "type": "report" }
```

### Add vacation request

```json
// POST /api/vacations
{
  "employee_name": "Alice Smith",
  "leave_type": "Vacation",
  "start_date": "2026-07-14",
  "end_date": "2026-07-18",
  "reason": "Summer holiday"
}

// 201 Created
{
  "message": "Vacation added successfully.",
  "days": 5.0,
  "record": { "employee_name": "Alice Smith", "leave_type": "Vacation", "leave_code": "V", ... }
}
```

### PC approval request

```json
// POST /api/admin/approvals/approve-pc
{
  "approvals": [
    { "employee_name": "Alice Smith", "start_date": "2026-03-15", "end_date": "2026-03-15" }
  ]
}

// Response
{ "approved": 1, "errors": [], "message": "1 PC vacation(s) approved as Vacation (V)." }
```

---

## Key Design Notes

| Aspect | Detail |
|---|---|
| **Custom `.env` loader** | `HolidayLeaveAssistantApplication.loadDotEnvSafe()` reads `.env` line-by-line without variable substitution, which prevents BCrypt hash corruption caused by `$` expansion in standard dotenv libraries |
| **File-backed credentials** | `data/secret/secret.json` is the sole auth source; `.env` variables only seed the admin entry on first boot |
| **Backend authorization** | `AuthInterceptor` enforces role checks on every request — `/admin/**` and `/api/admin/**` return 403/redirect if `role ≠ "admin"`; not just hidden UI |
| **Employee ownership guard** | Employees can only add/delete their own leave; cross-employee writes are blocked with `403` at both the wizard layer and the direct `POST/DELETE /api/vacations` endpoints |
| **Restricted types enforced server-side** | `POST /api/vacations` returns `403` for restricted codes even if the client bypasses the UI |
| **Working copy / master separation** | Writes go to `data/working/`, synced back to `data/` by `SyncService`. The sync daemon wakes on explicit trigger (after each write) and also polls every 5 s |
| **Atomic writes** | All file writes (credentials, restricted types, vacation types, Excel) use temp file + atomic rename (`ATOMIC_MOVE`) to prevent corruption |
| **Per-year Excel locks** | `WorkingExcelWriter` maintains a `ReentrantLock` per calendar year, preventing concurrent write corruption |
| **PlannerExcelReader cache** | Records are cached by (absolute path, `lastModified`). Cache is eagerly evicted by controllers after a write and confirmed-evicted by `SyncService` after the master file is atomically replaced |
| **Role-aware page routing** | `GET /` serves `admin-page` or `employee-page` depending on session role |
| **PC approvals write to working file only** | Converted PC→V entries follow the existing working→master sync boundary |
| **IBM Box sync** | `BoxSyncService` uploads the master file asynchronously on a single background thread after each sync. Supports JWT and CCG authentication. Has retry with exponential back-off and a backoff window after repeated failures |
| **Single-tenant** | One agent singleton; session-keyed wizard state per session ID via `AppState.pendingVacations` |
| **Conversation history** | Last 10 turns (20 messages) kept in `AppState`; cleared on new chat |
| **3-pass fuzzy name matching** | Employee names resolved via: (1) exact substring → (2) token overlap → (3) multi-token fuzzy score |
| **Context shapes** | LLM receives one of three JSON context shapes (A: single employee, B: all employees, C: generic) depending on the question type, to keep context small and grounding tight |
| **Weekend exclusion** | Saturday/Sunday cells are never written or counted in day totals |
| **Date display format** | All dates shown in chat and reports use `dd MMM yyyy` (e.g. `01 Aug 2026`) |

---

## Troubleshooting

**`localhost/172.17.0.2:11434 — Connection refused`**

This is Java printing the DNS resolution result alongside the hostname. `172.17.0.2` is the Docker bridge IP that `localhost` resolves to when Docker Desktop is installed. Fix: use `127.0.0.1` instead of `localhost` in `LLM_BASE_URL`.

---

**App starts but all chat requests return 502**

The LLM endpoint is unreachable. Verify:
- Ollama is running: `ollama list`
- `LLM_BASE_URL` in `.env` is correct and reachable from where the app is running
- If running in Docker, use `host.docker.internal` instead of `127.0.0.1`
- If running in rootless Podman, use `host.containers.internal` instead of `127.0.0.1`

---

**Port conflict on startup**

The effective port depends on how you run the app:

| Run method | Effective port |
|---|---|
| `java -jar` with no `.env` | **8085** (`application.properties` default) |
| `java -jar` with `.env.example` copied to `.env` | **8080** (`FLASK_PORT=8080` in `.env.example`) |
| `docker compose up` | **8080** (set by `docker-compose.yml` environment block) |
| `podman-compose up` | **8080** (set by `podman-compose.yml` environment block) |

To change the port, update `FLASK_PORT` in `.env` and update the `ports` mapping in `docker-compose.yml` / `podman-compose.yml` to match.

---

**Employee login fails with default password**

Employee accounts are auto-provisioned with default password `test1234` when an Excel file is uploaded. If the password has since been changed and you have lost it, reset it via the Admin Settings page (`/admin/settings`) or by editing `data/secret/secret.json` directly (generate a new BCrypt hash with `htpasswd -bnBC 12 "" newpassword | tr -d ':\n'`).

---

**`LOGIN_PASSWORD_HASH` — admin authentication always fails**

Ensure there are no leading/trailing spaces around the hash in `.env`. The hash must start with `$2b$` or `$2a$`. Re-generate with:

```bash
htpasswd -bnBC 12 "" yourpassword | tr -d ':\n'
```

Note: once `data/secret/secret.json` has been created, changes to `LOGIN_PASSWORD_HASH` in `.env` have **no effect** — the file is only read on first boot. Use the Admin Settings page or the password-reset API to change credentials at runtime.

---

**Admin routes return 403 / redirect to `/`**

You are logged in as the `employee` role. Admin pages (`/admin/settings`, `/admin/approvals`, `/admin/audit-log`) and admin API routes (`/api/admin/**`) require the `admin` role. Log out and sign in with the admin credentials.

---

**Vacation type is "disabled by administrator"**

The admin has added that type code to the restricted list in `data/restrictedVacationType/restricted-vacation-types.json`. An admin can remove it via **Admin → Settings → Restricted Vacation Types**.

---

**Excel file not parsed / zero employees shown**

The reader expects the eIndkomst vacation planner format: a sheet with a month-name row, a day-number row (≥ 20 cells containing integers 1–31), and employee rows below. Column headers and sheet names must match this layout. Check the application log (`LOG_LEVEL=DEBUG`) for parse diagnostics.

---

**Changes to `application.properties` have no effect**

Spring Boot reads configuration from inside the packaged JAR. After editing `application.properties`, rebuild:

```bash
cd backend && mvn package -DskipTests
```

---

**`java.lang.UnsupportedClassVersionError` on startup**

The Docker image uses JDK 21 (`eclipse-temurin:21`). Running `java -jar` locally requires **Java 21 or later**. Check your version with `java -version` and install [Eclipse Temurin 21](https://adoptium.net) if needed.
