# Enhancement: Synchronize Master Excel File with IBM Box After Data Modification

**Status:** Design / Analysis only — no code changes  
**Scope:** Post-synchronization upload of the local master Excel file to IBM Box after any successful Add Vacation or Delete Vacation operation

---

## Table of Contents

1. [Objective](#objective)
2. [Existing Functionality Analysis](#existing-functionality-analysis)
3. [IBM Box Integration Design](#ibm-box-integration-design)
4. [Upload Workflow Design](#upload-workflow-design)
5. [Failure Handling](#failure-handling)
6. [Logging Plan](#logging-plan)
7. [Validation and Regression Safety](#validation-and-regression-safety)
8. [New Artefacts Summary](#new-artefacts-summary)
9. [Architecture Diagram](#architecture-diagram)

---

## Objective

The application maintains a **master Excel file** as the authoritative source for all employee vacation data. Whenever a user successfully performs an **Add Vacation** or **Delete Vacation** operation, the updated master Excel file must be uploaded to **IBM Box**, ensuring the cloud copy always reflects the latest application data.

This document covers requirement analysis and implementation design only. No code changes are made at this stage.

---

## Existing Functionality Analysis

### Add Vacation — entry points

Both entry points produce identical side effects after a successful write:

| Entry point | File | Sequence |
|---|---|---|
| Chat wizard (user confirms) | `ChatController.handleAddWizard()` | `writer.addVacation(workingPath)` → `reader.evict(masterPath)` → `auditService.log()` → `syncService.triggerSync()` |
| REST `POST /api/vacations` | `VacationController.addVacation()` | identical |

### Delete Vacation — entry points

| Entry point | File | Sequence |
|---|---|---|
| Chat wizard (user confirms) | `ChatController.handleDeleteWizard()` | `writer.deleteVacation(workingPath)` → `reader.evict(masterPath)` → `auditService.log()` → `syncService.triggerSync()` |
| REST `DELETE /api/vacations` | `VacationController.deleteVacation()` | identical |

### Where the local master file is updated

The working → master promotion is performed inside `SyncService.syncAll()`. The critical section per file is:

```
Files.copy(workingFile → tmp)
Files.move(tmp → masterFile, ATOMIC_MOVE)   // master replaced atomically
reader.evict(masterPath)                     // reader cache invalidated
reloadCallback.run()                         // optional hook (nullable, currently unused)
```

The sync daemon wakes on a `triggerSync()` signal posted immediately after every write, or after a 5-second idle wait. In the normal case the master file is replaced within milliseconds of any write.

### Existing error handling

- **Working-copy write errors** (`IOException`) abort before `triggerSync()` is called — the master file is never touched.
- **Sync errors** per file are caught and logged; the daemon backs off exponentially after three consecutive failures (doubling the configured `SYNC_INTERVAL_SECONDS`).
- **No transactional rollback** exists: once `writer.addVacation()` or `writer.deleteVacation()` succeeds, the data is committed to the working copy permanently.
- **`reloadCallback`** in `SyncService` is nullable and currently unset by any caller.

---

## IBM Box Integration Design

### SDK selection

**Recommended: official [box-java-sdk](https://github.com/box/box-java-sdk)**

```xml
<!-- pom.xml — additive only -->
<dependency>
    <groupId>com.box</groupId>
    <artifactId>box-java-sdk</artifactId>
    <version>4.x.x</version>
</dependency>
```

### Authentication strategy

| Option | Suitability |
|---|---|
| OAuth 2 Developer Token | Development/testing only — 60-minute expiry, not suitable for production |
| **JWT (Service Account)** | ✅ Recommended — non-interactive, long-lived, private key based |
| **Client Credentials Grant (CCG)** | ✅ Also appropriate — simpler configuration, no private key file |

Credentials must **never** be stored in source code or in `application.properties` committed to version control. They are supplied exclusively via environment variables.

### New component: `BoxSyncService`

A new `@Service` class, entirely independent of all existing services:

```
com.holidayleave.assistant.service.BoxSyncService
```

**Responsibilities:**
- Hold an authenticated `BoxAPIConnection` (lazily initialised, reconnected on auth failure)
- Expose `boolean isEnabled()` — checks the `BOX_ENABLED` feature flag
- Expose `void submitUpload(File masterFile)` — queues an async upload task
- Log upload lifecycle events using SLF4J
- Write audit entries via the existing `AuditService` for full traceability

`BoxSyncService` is `@Autowired` into `SyncService` only. No controller or wizard service is modified.

### Configuration — new environment variables

These are added to `application.properties` and `.env.example` as additive bindings only:

| Environment variable | `application.properties` binding | Purpose |
|---|---|---|
| `BOX_ENABLED` | `app.box.enabled` | Feature flag — `false` by default; disables all Box logic when unset |
| `BOX_CLIENT_ID` | `app.box.client-id` | Box application client ID |
| `BOX_CLIENT_SECRET` | `app.box.client-secret` | Box application client secret |
| `BOX_ENTERPRISE_ID` | `app.box.enterprise-id` | Box enterprise ID (required for JWT / CCG) |
| `BOX_FOLDER_ID` | `app.box.folder-id` | Target Box folder ID where the file is uploaded |
| `BOX_JWT_PRIVATE_KEY` | `app.box.jwt-private-key` | Private key PEM string (JWT auth only; omit for CCG) |
| `BOX_JWT_PRIVATE_KEY_PASSPHRASE` | `app.box.jwt-private-key-passphrase` | Key passphrase (JWT only) |
| `BOX_JWT_PUBLIC_KEY_ID` | `app.box.jwt-public-key-id` | Key ID registered in the Box application (JWT only) |
| `BOX_RETRY_BACKOFF_SECONDS` | `app.box.retry-backoff-seconds` | Back-off base interval for upload retries (default: `60`) |

`BOX_ENABLED=false` is the **default** so that no Box credentials are required for local development or any existing deployment. The application behaves identically to its current state when Box is disabled.

A new `AppProperties.Box` inner class mirrors the pattern of the existing `AppProperties.Llm` inner class.

---

## Upload Workflow Design

### Insertion point in `SyncService.syncAll()`

The IBM Box upload is inserted immediately after the atomic master-file replacement succeeds, before the status counters are updated. The change to `syncAll()` is a single conditional call:

```java
// existing
Files.move(tmp, masterFile.toPath(), REPLACE_EXISTING, ATOMIC_MOVE);
synced.add(workingFile.getName());
log.info("Synced {} -> {}", workingFile.getName(), masterFile.getPath());
reader.evict(masterFile.getAbsolutePath());
if (reloadCallback != null) reloadCallback.run();

// NEW (additive only)
if (boxSyncService.isEnabled()) {
    boxSyncService.submitUpload(masterFile);
}
```

**Why this location:**
- The master file is atomically confirmed at this point — safe to read for upload.
- The call is inside the per-year `ReentrantLock`, ensuring no concurrent write occurs during the upload read.
- No controller, wizard, or writer code is modified.

### Synchronous vs. asynchronous upload

| Mode | Pros | Cons |
|---|---|---|
| Synchronous (blocking in sync daemon) | Simplest; Box state matches local state before `syncStatus` is set | A slow or unavailable Box stalls the entire local sync cycle |
| **Asynchronous (dedicated executor)** | ✅ Recommended — Box failure never delays or breaks local operations | Box result is not reflected in the existing `syncStatus` field |

**Recommended:** asynchronous upload via a dedicated single-thread executor inside `BoxSyncService`. The local sync completes and reports `success` regardless of Box outcome. Box-specific results are logged and audited independently.

### Detailed sequence

```
SyncService.syncAll() — per working file
│
├─ Guard: workingFile.lastModified > masterFile.lastModified?
│
├─ Acquire year lock (ReentrantLock)
│
├─ Files.copy(working → tmp)
├─ Files.move(tmp → master, ATOMIC_MOVE)    ← master promoted
├─ reader.evict(masterPath)
├─ reloadCallback (if set)
│
├─ [NEW] if boxSyncService.isEnabled()
│         └─ boxSyncService.submitUpload(masterFile)  ← async, non-blocking
│
├─ synced.add(filename)
└─ Release year lock
```

Inside the async upload task:

```
Upload task (runs on BoxSyncService executor)
│
├─ auditService.log("box_upload_started", "system", null, filename, "info", "box-sync")
├─ start = System.currentTimeMillis()
├─ Obtain BoxAPIConnection (reuse cached or reconnect if expired)
├─ Locate existing file in target folder, or prepare new upload
├─ Upload file bytes via Box SDK
├─ Receive Box file version ID from SDK response
├─ duration = System.currentTimeMillis() - start
├─ log.info("Box upload complete: {} in {}ms, versionId={}", filename, duration, versionId)
└─ auditService.log("box_upload_complete", "system", null,
       "file=" + filename + " duration=" + duration + "ms versionId=" + versionId,
       "success", "box-sync")
```

---

## Failure Handling

### Failure categories and responses

| Scenario | Detection | Response |
|---|---|---|
| `BOX_ENABLED=false` | Config flag checked before any call | No-op; no log noise |
| Box unreachable / timeout | `BoxAPIException`, `IOException` from SDK | Log `WARN`, audit `box_upload_failed`; **do not rethrow** — local sync is unaffected |
| Authentication failure (401 / 403) | `BoxAPIException` with HTTP 401 or 403 | Invalidate cached connection; log `ERROR`; audit `box_upload_failed` |
| Upload interrupted / partial | SDK throws; Box automatically discards incomplete uploads | Treated as upload failure — same handling as above |
| File version conflict | Box always accepts a new version of an existing file — no client-side conflict resolution needed | Not applicable |
| Repeated consecutive failures | Failure counter per file tracked in `BoxSyncService` | After 3 failures: log `ERROR` once, pause Box uploads for `BOX_RETRY_BACKOFF_SECONDS`, reset counter |

**Critical invariant:** A Box upload failure **must never propagate an exception into `SyncService.syncAll()`**. `BoxSyncService` catches all exceptions internally. Local data integrity is never contingent on Box availability.

### Retry strategy

The async executor in `BoxSyncService` maintains a simple in-memory retry queue (no persistent state). On failure, the task is re-queued with exponential back-off (1×, 2×, 4× of `BOX_RETRY_BACKOFF_SECONDS`), up to a maximum of 3 retries. After 3 exhausted retries the failure is permanently logged and the upload is not retried until the next sync cycle produces a fresh upload request for that file.

A persistent job queue (e.g. Spring Batch, a message broker) is intentionally out of scope for this integration.

---

## Logging Plan

All Box-related events are emitted by `BoxSyncService` using SLF4J, **and** written to the existing `data/audit.log` via `AuditService.log()`.

| Event | SLF4J level | `AuditService` event type | Key details recorded |
|---|---|---|---|
| Box sync disabled | `DEBUG` | — | "Box sync disabled, skipping {filename}" |
| Upload queued | `DEBUG` | — | filename, file size in bytes |
| Upload started | `INFO` | `box_upload_started` | filename, Box folder ID |
| Upload complete | `INFO` | `box_upload_complete` | filename, duration ms, Box version ID |
| Upload failed (retrying) | `WARN` | `box_upload_failed` | filename, attempt number, error message |
| Upload failed (exhausted) | `ERROR` | `box_upload_failed` | filename, error message, "no more retries" |
| Authentication failure | `ERROR` | `box_auth_failed` | error message |

`AuditService.log()` call signature used: `log(eventType, "system", null, details, status, "box-sync")`.

---

## Validation and Regression Safety

The following must be verified before and after implementation to confirm no regression is introduced:

| Area | What to confirm |
|---|---|
| Add Vacation — chat | `ChatController.handleAddWizard()` is not modified |
| Add Vacation — API | `VacationController.addVacation()` is not modified |
| Delete Vacation — chat | `ChatController.handleDeleteWizard()` is not modified |
| Delete Vacation — API | `VacationController.deleteVacation()` is not modified |
| `SyncService.syncAll()` | Only change: one conditional `boxSyncService.submitUpload()` call after the atomic move; all existing logic preserved |
| `SyncService.syncStatus` | Still reflects local sync result only — Box outcome does not affect `syncStatus` |
| Chat queries | `loadMasterRecords()` reads from `AppState.getLoadedFiles()` — unchanged |
| `PlannerExcelReader` cache eviction | Logic and timing unchanged |
| `WorkingExcelWriter` | Not touched |
| `AppProperties` | Only additive change: new `Box` inner class |
| `application.properties` | Only additive change: new `app.box.*` bindings |
| `BOX_ENABLED` defaults to `false` | All Box code paths are skipped; application behaviour is identical to current when Box is disabled |

---

## New Artefacts Summary

| Artefact | Change type | Existing file touched? |
|---|---|---|
| `BoxSyncService.java` | New `@Service` class | ❌ new file |
| `AppProperties.Box` inner class | Additive | ✅ `AppProperties.java` — additive only |
| `app.box.*` property bindings | Additive | ✅ `application.properties` — additive only |
| `BOX_*` environment variables | Additive | ✅ `.env.example` — additive only |
| `box-java-sdk` Maven dependency | Additive | ✅ `pom.xml` — additive only |
| `SyncService.syncAll()` | One `if` block inserted after atomic move | ✅ `SyncService.java` — minimal, isolated |

Every existing controller, wizard service, writer, reader, and audit service is **untouched**.

---

## Architecture Diagram

```
User Request
      │
      ▼
Add/Delete Vacation API  (ChatController or VacationController)
      │
      ▼
WorkingExcelWriter — writes working copy
      │
      ▼
reader.evict(masterPath)   ← eager cache invalidation
      │
      ▼
auditService.log(vacation_added / vacation_deleted)
      │
      ▼
syncService.triggerSync()
      │
      ▼
SyncService — sync daemon wakes
      │
      ▼
[Guard] workingFile newer than masterFile?
      │  Yes
      ▼
Acquire per-year ReentrantLock
      │
      ▼
Files.copy(working → tmp)
Files.move(tmp → master, ATOMIC_MOVE)   ← master updated
reader.evict(masterPath)
reloadCallback (if set)
      │
      ▼
[NEW] BOX_ENABLED?
      │ No ─────────────────────────────────────────────────────┐
      │ Yes                                                      │
      ▼                                                          │
BoxSyncService.submitUpload(masterFile)  ← async, non-blocking  │
      │                                                          │
      ▼  (async executor)                                        │
auditService.log(box_upload_started)                            │
      │                                                          │
      ▼                                                          │
Box SDK — upload master file to IBM Box                         │
      │                                                          │
      ├─ Success ──► auditService.log(box_upload_complete)      │
      │                                                          │
      └─ Failure ──► retry with back-off (max 3 attempts)       │
                     auditService.log(box_upload_failed)        │
                                                                 │
      ◄────────────────────────────────────────────────────────┘
      │
      ▼
synced.add(filename)
syncStatus = "success"   ← reflects local sync only
Release lock
      │
      ▼
Return API Response to user
```
