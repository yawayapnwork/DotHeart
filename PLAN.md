# DotHeart — Project Plan

A zero-cost, self-hosted, private pipeline that pushes handcrafted pixel art
(`.png`) and short status notes from a terminal CLI to two Android home
screen widgets.

Status legend used throughout: `[ ]` not started, `[x]` done, `[~]` in
progress / partial.

---

## 1. Architecture Philosophy & Constraints

### 1.1 Non-negotiable constraints

| Constraint | Concrete rule | Enforcement |
|---|---|---|
| Zero infra cost | Backend runs entirely on Render's free web-service tier. No Firebase, Supabase, Redis Cloud, S3, or any metered third-party API. | Single Docker image, SQLite + local filesystem only (`app/storage.py`, already implemented). No `pip install` of any BaaS SDK. |
| No paid BaaS | No push notification service (FCM is free but adds Google Play Services coupling + a moving part we don't need — rejected in favor of polling, see §1.3). No analytics SaaS. | Reviewed at each PR: `grep -ri "firebase\|supabase\|aws\|gcp" app/ android/` must return nothing. |
| Minimal APK size | Release APK (per ABI, `arm64-v8a`) **< 15 MB**. | `./gradlew assembleRelease` + `unzip -l app-release.apk` size check in CI (Phase 5). Achieved by: no image-loading library (Glide/Coil) — hand-rolled `BitmapFactory` + nearest-neighbor scaler; no Firebase BOM; R8/ProGuard full mode; WebP not needed since we already control PNG size server-side (≤2 MB cap). |
| Strict battery awareness | All background work must be Doze- and App-Standby-compliant. No wakelocks held outside a WorkManager execution window. No exact alarms. | WorkManager only, `NetworkType.CONNECTED` constraint, `setBackoffCriteria` with `BackoffPolicy.EXPONENTIAL`, min periodic interval 15 min (Android floor) with jitter to avoid thundering-herd on Render free tier. |
| Crisp pixel art rendering | Widget images are hand-drawn low-res sprites (e.g. 32×32 or 64×64) that must render as sharp squares, never blurred. | `Bitmap` scaled with `Bitmap.createScaledBitmap(bmp, w, h, /*filter=*/false)` (nearest-neighbor — `filter=false` disables bilinear interpolation) before being written into the `RemoteViews` `ImageView`. Never use `ImageView.setImageBitmap` with `android:scaleType="fitCenter"` and filtering on — see §3.4. |
| Memory safety / IPC limits | `RemoteViews` is delivered to the launcher process via Binder IPC, capped at **1 MB per transaction** (`TransactionTooLargeException` above that, shared across *all* concurrently-updating widgets from the same process). | Hard cap: decoded+scaled bitmap pushed into `RemoteViews` must be ≤ **300 KB** raw ARGB_8888 budget per widget instance (leaves headroom for two simultaneous widget instances + Binder overhead). Concretely: max widget bitmap dimension after scaling is capped at 240×240 px (240×240×4 bytes = 230,400 bytes). Source PNG from the backend can be up to 2 MB on disk (compressed) — it is decoded, scaled down, and only the *scaled* bitmap goes into the `RemoteViews` payload. See §3.4 and §5. |

### 1.2 Why polling, not push

FCM would reduce latency but:
- Requires a Google-managed project + `google-services.json`, pulling in Play
  Services dependency (`com.google.android.gms:play-services-base`), which
  alone can add 1–3 MB and a dependency on Google Play Services being
  present/updated on the device — an unacceptable coupling for a "zero
  external dependency" private tool.
- Requires the backend to hold and rotate FCM server credentials — another
  secret surface for a project whose entire premise is "small attack
  surface, self-hosted."
- Render's free tier free web service **spins down after 15 minutes of
  inactivity** and cold-starts on the next request (~30–50s). A push-based
  design would need the backend to always be warm to call FCM, defeating the
  cost goal. Polling from the client naturally tolerates a cold start: the
  client just gets a slow first response and normal responses thereafter.

Conclusion: **WorkManager periodic polling** (Phase 3) is correct for this
project's constraints. Documented here so this decision isn't relitigated
mid-implementation.

### 1.3 Component diagram

```
┌─────────────────────┐        HTTPS (multipart POST, token auth)
│  dotheart CLI        │ ───────────────────────────────────────────┐
│  (Python, Phase 4)    │                                             │
└─────────────────────┘                                             ▼
                                                          ┌───────────────────────┐
                                                          │ Render Free Web Service │
                                                          │ FastAPI + Uvicorn        │
                                                          │ SQLite (WAL) + local FS  │
                                                          │  app/main.py             │
                                                          │  app/storage.py          │
                                                          │  app/security.py         │
                                                          └───────────┬─────────────┘
                                                                      │ HTTPS GET (ETag conditional)
                                       ┌──────────────────────────────┴───────────────────────────────┐
                                       ▼                                                                ▼
                          ┌─────────────────────────┐                                    ┌─────────────────────────┐
                          │ Android Widget A         │                                    │ Android Widget B         │
                          │ AppWidgetProvider         │                                    │ AppWidgetProvider         │
                          │ WorkManager periodic poll │                                    │ WorkManager periodic poll │
                          │ OkHttp + nearest-neighbor │                                    │ OkHttp + nearest-neighbor │
                          └─────────────────────────┘                                    └─────────────────────────┘
```

### 1.4 Threat model (informs Phase 1 hardening, already largely implemented)

- **Attacker knows the URL** (it will be, at best, obscure — not secret).
  Mitigation: bearer `token` on every mutating request, constant-time
  comparison (`secrets.compare_digest`, done in `app/security.py`).
- **Attacker uploads a malicious/oversized file** to exhaust disk or exploit
  an image parser on the Android side. Mitigation: server-side magic-byte
  sniffing + 2 MB cap (done); Android side never executes/parses anything
  beyond `BitmapFactory.decodeStream` with `inJustDecodeBounds` pre-check
  (Phase 2, §3.4).
- **Read endpoints are unauthenticated by design** (the two widgets have no
  secure secret storage worth the complexity — see §1.5) — acceptable
  because the data is a status note and a pixel art image, not sensitive
  credentials. This is a conscious scope decision, not an oversight.

### 1.5 Explicitly out of scope

- End-to-end encryption of the note/image content.
- Multi-user / multi-couple support (single shared secret, single current
  state — matches the existing `widget_state` single-row schema).
- iOS client.
- Push notifications (§1.2).
- Image history / gallery (only "current" state is retained, matching the
  already-built backend).

---

## 2. Phased Milestone Breakdown

Each phase has an explicit exit gate. Do not start the next phase until the
current phase's DoD checklist (§4) for that phase is fully checked.

### Phase 1 — Minimal Viable Backend
**Goal:** A deployable FastAPI service that accepts authenticated image+note
uploads and serves them back. **This phase is substantially already
implemented** in `app/` (see file references below); remaining checklist
items are what's left before calling it "done."

- [x] `POST /api/v1/widget/update` — multipart form (`token`, `file`,
      `message`) — `app/main.py::update_widget`
- [x] `GET /api/v1/widget/current` — JSON state — `app/main.py::get_current_widget`
- [x] `GET /static/{filename}` — image bytes, `Cache-Control: no-cache,
      max-age=0, must-revalidate` — `app/main.py::get_static_image`
- [x] Constant-time token check — `app/security.py::verify_token`
- [x] Magic-byte image validation (PNG/JPEG/GIF, 2 MB cap) —
      `app/security.py::validate_image_bytes`
- [x] Message sanitization (control-char/null strip, 140-char cap) —
      `app/security.py::sanitize_message`
- [x] Atomic writes (`tempfile` + `os.fsync` + `os.replace`) —
      `app/storage.py::WidgetStorage._atomic_write`
- [x] SQLite state row, WAL mode, `threading.RLock`-guarded —
      `app/storage.py::WidgetStorage`
- [x] Structured logging, no traceback leakage on 500 — `app/main.py`
      exception handlers
- [x] Dockerfile: multi-stage, non-root, single uvicorn worker
- [ ] **Add `ETag` response header on `GET /static/{filename}` and
      `GET /api/v1/widget/current`**, value = the stored `checksum`. Required
      by Phase 3's conditional-GET caching. Not yet in `app/main.py` — add
      before Phase 3 starts.
- [ ] **Add `GET /api/v1/widget/current` conditional-GET support**: honor
      `If-None-Match` request header, return `304 Not Modified` with no body
      when the client's ETag matches the current checksum.
- [ ] Add a `/api/v1/widget/health` → already exists as `/health`; rename or
      alias to `/api/v1/widget/health` for API-surface consistency, or
      document `/health` as the canonical liveness probe in Render's health
      check config (decide in Phase 5 deployment config, keep `/health` —
      simpler, no code change needed).
- [ ] Deploy to Render free tier, confirm cold-start behavior end-to-end
      (first request after 15 min idle takes 30–50s — document this in the
      Android client's timeout configuration, §3.5).
- [ ] Rotate the placeholder `WIDGET_TOKEN` in `.env.example` for a real
      secret generated with `python -c "import secrets;
      print(secrets.token_urlsafe(32))"`, store only in Render's
      environment variable dashboard (never committed).

**Exit gate:** `curl` against the deployed Render URL round-trips an upload
and a fetch, `ETag`/`If-None-Match` verified with two consecutive `curl -I`
calls returning `200` then `304`.

### Phase 2 — Native Android Core
**Goal:** A widget that renders the *current* server state on manual
refresh. No background polling yet (that's Phase 3) — this phase proves the
rendering pipeline end-to-end with a tap-to-refresh button, which is also
the permanent user-facing manual override once Phase 3 lands.

- [ ] Android Studio project scaffold: `minSdk 26`, `targetSdk 34`,
      `compileSdk 34`, Kotlin, no Compose (Compose adds APK weight and
      `RemoteViews`/AppWidget rendering is View-system-based regardless —
      Compose Glance is considered and rejected for size, see §5 risk
      matrix).
- [ ] `AndroidManifest.xml`: declare `DotHeartWidgetProvider` with
      `android:resource="@xml/dotheart_widget_info"`, single widget
      provider class reused for both home-screen instances (Android widgets
      are instances of one provider, not one provider per instance — "two
      widgets" means the user places the same provider twice; confirm this
      matches the product intent, since it means both widgets always show
      the *same* image/note, matching the single-state backend schema).
- [ ] `res/xml/dotheart_widget_info.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
      android:minWidth="110dp"
      android:minHeight="110dp"
      android:targetCellWidth="2"
      android:targetCellHeight="2"
      android:updatePeriodMillis="0"
      android:initialLayout="@layout/widget_dotheart"
      android:resizeMode="horizontal|vertical"
      android:widgetCategory="home_screen"
      android:previewImage="@drawable/widget_preview" />
  ```
  `updatePeriodMillis="0"` is deliberate: the OS-native periodic update
  mechanism is unreliable (throttled, no backoff, no network-constraint
  awareness) — all refresh is driven by WorkManager (Phase 3), matching the
  battery-awareness constraint in §1.1.
- [ ] `res/layout/widget_dotheart.xml`: `ImageView` (`android:scaleType="fitCenter"`,
      `android:src="@drawable/placeholder_heart"`) + `TextView` for the note
      (max 3 lines, `android:ellipsize="end"`) + a small tap target
      `ImageButton` (refresh icon) — the whole widget root is also
      clickable via `setOnClickPendingIntent` for tap-to-refresh (§2.2
      below), with the small button as a secondary explicit affordance for
      discoverability.
- [ ] `DotHeartWidgetProvider : AppWidgetProvider`:
  - `onUpdate()`: for each `appWidgetId`, render last-known-good state from
    `SharedPreferences` cache immediately (never show a blank widget), then
    enqueue a one-off `WidgetRefreshWorker` (Phase 3 class, stubbed here as
    a direct synchronous OkHttp call for Phase 2's manual-refresh-only
    scope).
  - `onReceive()`: handle a custom `ACTION_DOTHEART_REFRESH` intent from the
    tap target, calling the same refresh path as `onUpdate`.
  - `onDeleted(context, appWidgetIds)`: clear any per-widget cache entries.
  - `onDisabled(context)`: if this was the last widget instance, cancel any
    WorkManager work (Phase 3) tied to widget refresh — prevents orphaned
    periodic work continuing to drain battery after widget removal.
- [ ] `NearestNeighborScaler` utility (§3.4) — implemented and unit-tested
      against a known 8×8 checkerboard PNG fixture, asserting output pixels
      are exact multiples with no interpolated/blended pixel values.
- [ ] `WidgetHttpClient` (OkHttp): single shared `OkHttpClient` instance
      (never construct per-call — connection pool reuse matters on mobile
      radio wake cost), `connectTimeout=10s`, `readTimeout=20s` (accounts
      for Render cold start), `callTimeout=25s` hard ceiling.
- [ ] Manual tap-to-refresh: tapping the widget triggers an immediate fetch
      + `RemoteViews` update, with a transient "Refreshing…" state (swap in
      a small spinner drawable) reverted on completion or error.
- [ ] Error state rendering: on fetch failure, keep the last successfully
      rendered image/note (never blank the widget) and show a small error
      glyph overlay (e.g. a corner badge) rather than replacing content —
      users should never lose the last-good pixel art because the network
      hiccuped.
- [ ] `local.properties`-based config for the backend base URL during dev
      (never hardcode the Render URL in a committed file — see Phase 4
      secret management parity, §2.4).

**Exit gate:** Install on a physical device (or emulator with network
access to the deployed Render backend), place two widget instances, tap to
refresh on each independently, confirm both render the same image/note
(single shared state), confirm nearest-neighbor sharpness visually (no
blur) at both default and a resized (user-dragged) widget size.

### Phase 3 — Resilient Background Polling
**Goal:** Widgets stay fresh without user interaction, without violating
Doze/App Standby, and without wasting radio/battery on unchanged content.

- [ ] Add `androidx.work:work-runtime-ktx` dependency (this is the *only*
      new runtime dependency beyond OkHttp added in this phase — keep the
      dependency graph minimal per §1.1 APK size constraint).
- [ ] `WidgetRefreshWorker : CoroutineWorker`:
  - Reads the last-known `ETag`/checksum from `SharedPreferences`.
  - `GET /api/v1/widget/current` with `If-None-Match: <last-checksum>`.
  - `304` → no-op, `Result.success()`, no bitmap re-decode, no
    `RemoteViews` update (saves both network and CPU/battery — this is the
    entire point of the ETag work done in Phase 1).
  - `200` → parse JSON, if `image_url` changed fetch `GET /static/{filename}`
    (also conditional via the same ETag/checksum — the checksum is shared
    between the JSON and the image per the existing backend contract, so a
    single conditional check covers both), decode+scale
    (`NearestNeighborScaler`), persist to `SharedPreferences` cache +
    internal storage (`context.filesDir`, not `getExternalFilesDir` — no
    external storage permission needed, keeps manifest permission surface
    minimal), then call `AppWidgetManager.updateAppWidget` for all current
    widget IDs.
  - Any `IOException`/timeout → `Result.retry()` (bounded — see backoff
    below); any unexpected exception → caught, logged via `Log.w` (never
    crash a background worker), `Result.failure()` to avoid infinite silent
    retry loops on a permanent error (e.g. 401 from a rotated token —
    surfaced to the user via a persistent notification only if failures
    exceed a threshold, §2.3 below).
- [ ] `WorkManager` scheduling in `Application.onCreate()` (or a
      `WidgetProvider.onEnabled()` — prefer `onEnabled` so work is only
      scheduled while at least one widget instance exists, and cancelled in
      `onDisabled` per Phase 2's note):
  ```kotlin
  val constraints = Constraints.Builder()
      .setRequiredNetworkType(NetworkType.CONNECTED)
      .setRequiresBatteryNotLow(true)
      .build()

  val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
          repeatInterval = 30, repeatIntervalTimeUnit = TimeUnit.MINUTES
      )
      .setConstraints(constraints)
      .setBackoffCriteria(
          BackoffPolicy.EXPONENTIAL,
          WorkRequest.MIN_BACKOFF_MILLIS, // 10s floor enforced by platform
          TimeUnit.MILLISECONDS
      )
      .addTag(WIDGET_REFRESH_WORK_TAG)
      .build()

  WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      WIDGET_REFRESH_WORK_NAME,
      ExistingPeriodicWorkPolicy.KEEP, // don't reset the schedule/backoff state on every onEnabled re-entry
      request
  )
  ```
  30-minute interval chosen as the balance point: Android's periodic-work
  floor is 15 minutes; going to the floor doubles radio wake frequency for
  a use case (a couple's status widget) where near-real-time freshness is
  not the goal. Documented as a tunable constant
  (`WidgetRefreshWorker.REFRESH_INTERVAL_MINUTES`), not a magic number
  buried in scheduling code.
- [ ] `setRequiresBatteryNotLow(true)` — explicit acknowledgment of §1.1's
      battery-awareness requirement: skip refresh entirely when the device
      is in a low-battery state, rather than relying on Doze alone.
- [ ] Failure-threshold notification: track consecutive failure count in
      `SharedPreferences`; after **5 consecutive failures** (~2.5 hours at
      the 30-min interval, accounting for backoff meaning it could be
      longer), post a single low-priority `NotificationChannel` alert
      ("DotHeart hasn't updated in a while — tap to check") rather than
      failing silently forever. Reset the counter to zero on any successful
      run.
- [ ] Manual tap-to-refresh (Phase 2) continues to work identically,
      implemented as `WorkManager.enqueue()` of a one-off
      `OneTimeWorkRequestBuilder<WidgetRefreshWorker>()` (same worker class,
      reused — no duplicated fetch/render logic between "manual" and
      "background" paths).
- [ ] Doze-mode manual test (see §4.3 acceptance criteria) using
      `adb shell dumpsys deviceidle force-idle` / `unforce`.

**Exit gate:** Device placed in Doze via `adb`, periodic work confirmed to
defer and then run in the next maintenance window (via `adb shell dumpsys
jobscheduler` showing the WorkManager-backed job), ETag `304` path confirmed
via backend access logs showing no image re-transfer when content is
unchanged, failure-threshold notification manually triggered by pointing
the client at an unreachable host for the test duration.

### Phase 4 — CLI & Automation Tooling
**Goal:** A single command turns a LibreSprite export + a short note into a
live widget update, with local secret management that never touches git.

- [ ] `dotheart` CLI entry point (Python, `argparse` or `click` — prefer
      stdlib `argparse` to keep the CLI's own footprint zero-dependency
      beyond `requests`, matching the project's minimalism ethos):
  ```
  dotheart push <image.png> -m "status message" [--server-url URL] [--token TOKEN]
  dotheart config set-token
  dotheart config set-server-url <URL>
  dotheart status        # GET current state, pretty-print, useful for verifying a push landed
  ```
- [ ] Local secret management: `~/.config/dotheart/config.toml`
      (`platformdirs`-style path, but hand-rolled with `pathlib.Path.home()`
      to avoid a dependency), file permissions forced to `0600` on
      write (`os.chmod`), containing:
  ```toml
  [server]
  url = "https://dotheart.onrender.com"

  [auth]
  token = "•••"
  ```
  `dotheart config set-token` prompts via `getpass.getpass` (never accepts
  the token as a bare CLI arg by default, to avoid it landing in shell
  history) but `--token` remains available as an explicit opt-in override
  for scripted/CI use, documented as "leaks to shell history, prefer
  config file."
- [ ] Pre-upload image sanity validation **client-side**, mirroring (not
      replacing) the server-side checks in `app/security.py`, so the artist
      gets instant feedback instead of a round trip to a cold-started
      Render service:
  - File exists, is readable, magic bytes match PNG (`89 50 4E 47 0D 0A 1A
    0A`) — reuse the exact byte sequence the backend checks in
    `app/security.py::_PNG_SIGNATURE` (kept in sync via a shared constants
    comment referencing that file's line).
  - Size ≤ 2 MB (match `MAX_IMAGE_BYTES` — read from `--server-url` via a
    `GET /health` capability probe is overkill; instead hardcode the same
    default and allow `--max-bytes` override for local pre-check only, the
    server remains the source of truth and will reject regardless).
  - Dimensions check: warn (not block) if not a "pixel art friendly" size —
    i.e. not a power-of-two-ish small dimension (e.g. warn if width or
    height > 512px, since this is meant for hand-drawn low-res sprites, and
    a huge PNG will just get crushed by the Android nearest-neighbor
    scaler anyway, wasting the artist's detail).
  - Message length ≤ 140 chars, reject with a clear CLI error before making
    any network call (fail fast, matching backend's own cap).
- [ ] `dotheart push` output: on success print the returned `checksum` and
      `timestamp` (human-formatted), on failure print the server's `detail`
      field verbatim (already sanitized/safe per Phase 1's exception
      handlers) plus the HTTP status code, exit code `1`.
- [ ] `dotheart status` command: `GET /api/v1/widget/current`, pretty-print
      message/timestamp/checksum, useful both for humans and for a
      pre-push CI-style check ("did the last push actually land").
- [ ] Shell completion (optional, nice-to-have, not gating): `argparse`
      supports this poorly — skip unless time allows; not a DoD item.
- [ ] `README.md` CLI usage section with copy-pasteable examples (already
      have a stub `README.md` at repo root — expand it here).

**Exit gate:** From a clean checkout with no prior config, running
`dotheart config set-token`, `dotheart config set-server-url`, then
`dotheart push art.png -m "hello"` succeeds against the deployed Phase 1
backend, and `dotheart status` reflects the pushed state. A deliberately
corrupted (non-PNG bytes renamed `.png`) file is rejected locally with a
clear error and *zero* network calls made (verify via a quick local proxy
or just confirm no server log entry appears for that invocation).

### Phase 5 — Hardening & Packaging
**Goal:** Ship-ready artifacts: a signed release APK under the size budget,
a documented Render deployment, and a user-facing guide for the one Android
OS obstacle that *will* break background polling if skipped: vendor battery
killers.

- [ ] Gradle release build config: `minifyEnabled true`, `shrinkResources
      true`, R8 full mode (`android.enableR8.fullMode=true` in
      `gradle.properties`), ProGuard/R8 rules covering OkHttp + WorkManager
      (`-keep` rules only where R8's default library consumer rules don't
      already cover it — verify via a release-build smoke test, not blanket
      `-keep class **`).
- [ ] APK size CI gate: build script fails if `app-release.apk` (per-ABI,
      `arm64-v8a`) exceeds 15 MB; report actual size in build output.
      Concretely:
  ```bash
  APK_PATH="app/build/outputs/apk/release/app-arm64-v8a-release.apk"
  SIZE_BYTES=$(stat -c%s "$APK_PATH")
  MAX_BYTES=$((15 * 1024 * 1024))
  if [ "$SIZE_BYTES" -gt "$MAX_BYTES" ]; then
    echo "APK size ${SIZE_BYTES} exceeds ${MAX_BYTES} byte budget" >&2
    exit 1
  fi
  ```
- [ ] App signing: generate a release keystore (`keytool -genkeypair
      -v -keystore dotheart-release.jks -keyalg RSA -keysize 2048
      -validity 10000 -alias dotheart`), store **outside** the repo, wire
      via `~/.gradle/gradle.properties` (not committed) or CI secret store
      — never commit `dotheart-release.jks` or its passwords. Add both to
      `.gitignore` explicitly by name, not just by extension, to avoid a
      future accidental broad-glob miss.
- [ ] Render deployment config: commit a `render.yaml` (Infrastructure as
      Code, so the free-tier service is reproducible without manual
      dashboard clicking):
  ```yaml
  services:
    - type: web
      name: dotheart-backend
      runtime: docker
      dockerfilePath: ./Dockerfile
      plan: free
      envVars:
        - key: WIDGET_TOKEN
          sync: false   # set manually in Render dashboard, never in git
        - key: DATA_DIR
          value: /var/data
        - key: LOG_LEVEL
          value: INFO
      healthCheckPath: /health
      disk:
        name: dotheart-data
        mountPath: /var/data
        sizeGB: 1
  ```
  **Explicit caveat to document in the same file as a comment and in
  README**: Render's free tier does **not** include a persistent disk add-on
  — the `disk:` block above requires a paid plan. On free tier, `/var/data`
  is ephemeral container storage that is wiped on every deploy and on
  periodic instance recycling. Two honest options, both documented for the
  user to choose:
  1. Accept state loss on redeploy/recycle (acceptable for a low-stakes
     couple widget — just re-push after a redeploy).
  2. Upgrade to Render's cheapest paid tier with a persistent disk if state
     durability matters more than the zero-cost constraint.
- [ ] Battery-optimization whitelisting guide (`docs/BATTERY_SETUP.md`):
      step-by-step, per-OEM, for the manufacturers with documented
      aggressive background-kill behavior (Xiaomi/MIUI, Huawei, Oppo/ColorOS,
      Vivo, Samsung's "Sleeping apps" list, OnePlus). Include:
  - The stock Android path: Settings → Apps → DotHeart → Battery → "Don't
    optimize" / "Unrestricted", and the `ACTION_IGNORE_BATTERY_
    OPTIMIZATIONS_SETTINGS` intent the app can fire to deep-link there
    directly.
  - A `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission usage note: this
    permission is Google Play policy-sensitive for apps distributed on
    Play Store (requires a declared-use justification); since DotHeart is
    **sideloaded, not Play-distributed** (private couple tool), this
    restriction doesn't apply, but document the decision explicitly so a
    future contributor doesn't blindly assume Play distribution.
  - Per-OEM autostart/background-permission toggles, gathered from
    `dontkillmyapp.com`-style community documentation, written into the doc
    with the OS version they apply to (this changes over OEM software
    versions — mark the doc "verify current OEM menu wording at install
    time," don't over-promise permanence).
- [ ] End-to-end release checklist (manual, run once before each tagged
      release): fresh device, sideload signed APK, place both widgets,
      confirm Phase 2–4 exit gates all still pass against the *deployed*
      (not local) backend, leave device idle overnight, confirm widgets
      updated at least once via the periodic worker (check via
      `SharedPreferences`-logged last-success timestamp surfaced in an
      in-app debug screen, §3.5).

**Exit gate:** Signed release APK ≤ 15 MB produced from a clean build,
installed via `adb install` on a physical device from a different OEM than
the primary dev/test device (to catch at least one battery-killer
variant), backend deployed and reachable at a stable Render URL, full
device-idle-overnight soak test passes.

---

## 3. Component-by-Component Specifications & Data Contracts

### 3.1 API contract — `POST /api/v1/widget/update`

Request: `multipart/form-data`

| Field | Type | Constraints |
|---|---|---|
| `token` | text field | Required. Compared via `secrets.compare_digest` against `WIDGET_TOKEN` env var. |
| `message` | text field | Required. Sanitized: Unicode control chars (`unicodedata.category` starting `C`) and null bytes stripped, then truncated to 140 chars (`MAX_MESSAGE_LENGTH`). Empty-after-sanitization is rejected. |
| `file` | file field | Required. Max 2,097,152 bytes (`MAX_IMAGE_BYTES`). Format determined by magic bytes only (PNG/JPEG/GIF); client-supplied filename/extension/Content-Type header ignored for validation purposes. |

Success response — `200 OK`:
```json
{
  "message": "string, ≤140 chars, sanitized",
  "image_url": "/static/widget_image.png",
  "timestamp": 1789737566,
  "checksum": "431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460"
}
```
Field notes:
- `timestamp`: Unix epoch seconds, `int`, server clock at the moment the
  SQLite row was committed.
- `checksum`: lowercase hex SHA-256 of the raw uploaded image bytes (not of
  any re-encoded form — the bytes on disk are byte-identical to what was
  uploaded). This value **is** the ETag value (Phase 1 remaining work).
- `image_url`: always `/static/{filename}` where `{filename}` is
  `widget_image.<ext>` with `<ext>` derived from the sniffed format
  (`png`/`jpg`/`gif`), **never** the client-supplied filename.

Error responses:

| Status | Condition | Body shape |
|---|---|---|
| `401` | Token missing or mismatched | `{"detail": "Invalid token."}` |
| `400` | Message empty after sanitization, image fails magic-byte check, or image exceeds size cap | `{"detail": "<specific reason>"}` |
| `422` | Missing required form field (FastAPI/Pydantic validation) | FastAPI default validation error shape (`{"detail": [{"type": ..., "loc": ..., "msg": ..., "input": ...}]}`) |
| `500` | Unhandled server error | `{"detail": "Internal server error."}` — traceback logged server-side only, never in the response body. |

### 3.2 API contract — `GET /api/v1/widget/current`

No auth required (§1.4 threat model — read-only, non-sensitive).

Request headers (Phase 1 remaining work, required before Phase 3):
- `If-None-Match: "<checksum>"` (optional) → server returns `304 Not
  Modified`, empty body, if the current state's checksum matches.

Success response — `200 OK`, `ETag: "<checksum>"` header:
```json
{
  "message": "string",
  "image_url": "/static/widget_image.png",
  "timestamp": 1789737566,
  "checksum": "431ced6916a2a21a156e38701afe55bbd7f88969fbbfc56d7fe099d47f265460"
}
```

Error response:

| Status | Condition | Body |
|---|---|---|
| `404` | No state has ever been uploaded (fresh deploy) | `{"detail": "No widget state has been uploaded yet."}` |

### 3.3 API contract — `GET /static/{filename}`

- `{filename}` must exactly match the currently active image filename
  (`storage.py::image_path_for` already enforces this — any other value,
  including path-traversal payloads, returns `404`, verified in testing).
- Response headers: `Cache-Control: no-cache, max-age=0, must-revalidate`,
  `Content-Type` set from the file extension (`image/png` / `image/jpeg` /
  `image/gif`), and (Phase 1 remaining work) `ETag: "<checksum>"` to allow
  the Android client to conditionally skip re-downloading image bytes it
  already has cached even when it must re-fetch the JSON (e.g. only the
  message changed).

### 3.4 On-disk storage schema

SQLite (`app/storage.py`, table `widget_state`, single-row by `CHECK (id =
1)` constraint — this is intentional: the product has exactly one "current"
state, not a history):

```sql
CREATE TABLE IF NOT EXISTS widget_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    message TEXT NOT NULL,
    image_filename TEXT NOT NULL,
    checksum TEXT NOT NULL,
    timestamp INTEGER NOT NULL
);
```

Filesystem layout under `DATA_DIR` (default `./data` locally, `/var/data`
in the Render container per `Dockerfile`):
```
DATA_DIR/
├── widget.db            # SQLite, WAL mode
├── widget.db-wal
├── widget.db-shm
└── images/
    └── widget_image.<ext>   # exactly one file; stale-format file removed on format change
```

Invariant enforced by `WidgetStorage.save_update`: at any point in time,
`images/` contains exactly the file named by the current DB row's
`image_filename` — no orphans, no missing file (verified by the atomic
write + stale-cleanup logic; a crash between the file write and the DB
commit is impossible to observe as inconsistent because the file write
happens first and is a no-op if the process never gets to the DB commit —
worst case is one harmless orphaned temp `.part` file, cleaned by the
`_atomic_write` exception handler or safely ignorable on next write since it
uses a fresh `tempfile.mkstemp` name each time).

### 3.5 Android lifecycle contracts

**`AppWidgetProvider` callback contract:**

| Callback | When | DotHeart obligation |
|---|---|---|
| `onUpdate(context, appWidgetManager, appWidgetIds)` | Widget added, or OS-triggered update (suppressed here via `updatePeriodMillis="0"`, so effectively: widget added only) | Render cached state immediately (never blank), enqueue refresh. |
| `onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)` | User resizes the widget | Re-render existing cached bitmap at the new target size via `NearestNeighborScaler` — **no network call**, this must be instant and offline since it's a pure re-scale of already-downloaded pixels. |
| `onDeleted(context, appWidgetIds)` | One widget instance removed | Clear per-`appWidgetId` `SharedPreferences` entries (if any per-instance state exists — the current design is mostly global/shared state, so this is mostly a no-op, documented as such rather than left unimplemented-and-unexplained). |
| `onEnabled(context)` | First widget instance placed | Schedule the Phase 3 periodic `WorkManager` job (`enqueueUniquePeriodicWork` with `KEEP` policy). |
| `onDisabled(context)` | Last widget instance removed | Cancel the periodic job (`WorkManager.getInstance(context).cancelUniqueWork(WIDGET_REFRESH_WORK_NAME)`) — mandatory, not optional, per §1.1 battery constraint (an orphaned periodic job with zero widgets to update is pure battery waste). |

**`WorkManager` constraint contract** (Phase 3, `WidgetRefreshWorker`):

| Constraint | Value | Rationale |
|---|---|---|
| `NetworkType` | `CONNECTED` | No point attempting on airplane mode / no network — avoids a guaranteed-fail wakeup. |
| `RequiresBatteryNotLow` | `true` | Explicit battery-awareness per §1.1; a status widget refresh is not critical enough to run during a low-battery state. |
| `BackoffPolicy` | `EXPONENTIAL`, platform floor (10s) initial | Transient failures (Render cold start, brief network blip) shouldn't hammer the service or drain battery retrying aggressively. |
| Repeat interval | 30 minutes | See Phase 3 rationale. |
| Existing-work policy | `KEEP` | Re-running `onEnabled` (e.g. after a device reboot re-triggers widget lifecycle) must not reset accumulated backoff state or cause duplicate concurrent jobs. |

**Bitmap / IPC payload contract** (ties directly to §1.1's memory-safety
row):

```kotlin
object NearestNeighborScaler {
    // Hard ceiling derived from the RemoteViews/Binder 1MB transaction
    // budget, shared across concurrently-updating widget instances.
    // 240x240 ARGB_8888 = 230,400 bytes raw, leaving headroom for two
    // simultaneous widget instances plus Binder/Parcel overhead.
    const val MAX_WIDGET_BITMAP_DIMENSION_PX = 240

    fun scaleForWidget(source: Bitmap, targetWidthPx: Int, targetHeightPx: Int): Bitmap {
        val clampedW = targetWidthPx.coerceAtMost(MAX_WIDGET_BITMAP_DIMENSION_PX)
        val clampedH = targetHeightPx.coerceAtMost(MAX_WIDGET_BITMAP_DIMENSION_PX)
        // filter=false is the entire contract here: nearest-neighbor, no
        // bilinear blur, so hand-drawn pixel art stays crisp at any
        // integer or non-integer scale factor.
        return Bitmap.createScaledBitmap(source, clampedW, clampedH, /* filter = */ false)
    }
}
```
Decode contract: never call `BitmapFactory.decodeStream` directly on an
unbounded stream. Always two-pass: first with
`BitmapFactory.Options().apply { inJustDecodeBounds = true }` to read
dimensions without allocating pixel memory, reject (log + keep last-good
state) if reported dimensions exceed a sanity ceiling (e.g. 4096×4096 — the
backend already caps file size at 2 MB but a pathological PNG could
still decompress to a huge dimension at small file size — a decompression-
bomb concern worth guarding even in a "trusted, self-hosted" system,
since defense in depth costs one `if` check here), then decode for real and
immediately downscale via `NearestNeighborScaler` before the source bitmap
is ever handed to a `RemoteViews`.

---

## 4. Definition of Done (DoD) & Acceptance Criteria

Each item below is a concrete, executable test — not a vague assertion.
Organize as a manual QA checklist for Phase 5's release gate, and as
automated tests where marked `[auto]`.

### 4.1 Backend (Phase 1)

- [ ] `[auto]` `pytest` covering: valid upload round-trip; wrong token → 401
      (using `secrets.compare_digest` — assert via timing-insensitive
      functional test, not an actual timing measurement, which is flaky in
      CI); oversized file → 400; non-image bytes with `.png` extension →
      400; message with embedded null bytes and ANSI escape sequences →
      sanitized and truncated correctly; message with only whitespace/
      control chars after stripping → 400; path traversal on
      `/static/{filename}` (`../../etc/passwd`, URL-encoded variants) → 404;
      `304` returned when `If-None-Match` matches current checksum (once
      Phase 1's remaining ETag work lands).
- [ ] Concurrency test: two near-simultaneous `POST /update` calls (e.g.
      via `asyncio.gather` against a `TestClient` or two threads against a
      live instance) — assert the final state is exactly one of the two
      uploads, fully consistent (no interleaved message/image/checksum
      mismatch), and the `images/` directory contains exactly one file
      afterward (no orphan from the loser of the race).
- [ ] Crash-recovery test (manual or scripted): kill the process
      (`SIGKILL`, not graceful) mid-upload (simulate via a debug hook that
      sleeps after the temp-file write but before `os.replace`), restart,
      confirm `GET /current` returns the *previous* consistent state (never
      a half-written one) — this validates the atomic-write invariant
      under real crash conditions, not just code inspection.

### 4.2 Android rendering (Phase 2)

- [ ] `[auto]` Unit test: feed `NearestNeighborScaler` an 8×8 checkerboard
      fixture bitmap (alternating fully-opaque black/white pixels), scale
      to 240×240, assert every output pixel is exactly `Color.BLACK` or
      `Color.WHITE` — zero intermediate/blended gray values. This is the
      concrete, automatable proof of "crisp pixel art rendering" from
      §1.1, replacing a purely visual/subjective check.
- [ ] Manual: place widget at minimum size (2×2 cells) and maximum
      reasonable size (4×4 cells / resized to near full-width), visually
      confirm no blur at either extreme, on both an `mdpi`-class emulator
      and a real high-DPI (`xxhdpi`+) physical device — DPI bucket
      differences in how the OS itself scales the widget's *container* are
      a separate concern from our nearest-neighbor bitmap scaling, and both
      must be checked since a launcher can apply its own scaling on top of
      ours.
- [ ] Manual: resize the widget (drag handles) — confirm
      `onAppWidgetOptionsChanged` fires and re-renders instantly from cache
      with no network spinner/flicker.

### 4.3 Background resilience (Phase 3)

- [ ] Network dropout test: `adb shell svc wifi disable && adb shell svc
      data disable`, trigger manual refresh — confirm the widget keeps
      showing last-known-good content with the error-glyph overlay (§2.2),
      no crash, no blank widget. Re-enable network, confirm next refresh
      (manual or periodic) clears the error state.
- [ ] Corrupted upload test: point the client at a backend instance
      returning a `200` with a truncated/corrupted JSON body (simulate via
      a local mock server, e.g. `python -m http.server` serving a hand-
      crafted malformed response, or a small test-only FastAPI route) —
      confirm the client's JSON parse failure is caught, logged, treated as
      a fetch failure (counts toward the consecutive-failure notification
      threshold), and does **not** crash the widget process or corrupt the
      cached last-good state.
- [ ] Doze-mode recovery test:
  1. `adb shell dumpsys battery unplug`
  2. `adb shell dumpsys deviceidle force-idle`
  3. Push a new image+message via the CLI (`dotheart push`).
  4. Confirm via `adb shell dumpsys jobscheduler | grep -A5
     dotheart` that the WorkManager-backed job is deferred (not
     immediately executed) while idle.
  5. `adb shell dumpsys deviceidle unforce` (exit Doze, or wait for a
     natural maintenance window if testing the "eventually runs" path).
  6. Confirm the widget updates to the new content within one maintenance
     window / on Doze exit, without requiring the user to open the app or
     tap anything.
  7. `adb shell dumpsys battery reset` to restore normal battery state
     after the test.
- [ ] App-standby-bucket test: `adb shell am set-standby-bucket
      <package> rare`, confirm periodic work still eventually executes
      (possibly at reduced frequency per the `rare` bucket's OS-imposed
      throttling — this is expected OS behavior, not a bug; the test
      confirms the app doesn't crash or misbehave under it, not that it
      bypasses the throttle).
- [ ] `[auto]` `WorkManager` `TestListenableWorkerBuilder` unit test for
      `WidgetRefreshWorker`: mock OkHttp responses for `200`+new-checksum,
      `304`, `401`, `500`, and a timeout — assert correct `Result`
      (`success`/`retry`/`failure`) and correct `SharedPreferences`
      mutation (or lack thereof) for each case.

### 4.4 CLI (Phase 4)

- [ ] `[auto]` Reject a file that isn't a PNG (rename a `.txt` to `.png`)
      with zero network calls made (assert via a mocked `requests.post`
      that it's never called).
- [ ] `[auto]` Reject a message over 140 characters locally, matching the
      server's own cap, before any network call.
- [ ] Manual: fresh machine, no `~/.config/dotheart/` directory — confirm
      `dotheart push` without prior `config set-token`/`set-server-url`
      fails with a clear, actionable error (not a stack trace), pointing
      the user at the setup commands.
- [ ] Manual: confirm `~/.config/dotheart/config.toml` file permissions are
      `600` after `dotheart config set-token` (`stat -c%a` on Linux/macOS;
      document Windows as "best-effort, NTFS ACLs differ" since `os.chmod`
      semantics differ there — not a blocking issue for this project's
      primary dev/deploy environment but worth documenting).

### 4.5 Packaging (Phase 5)

- [ ] `[auto]` CI (or a documented local script) fails the build if the
      release APK exceeds 15 MB (script in §2, Phase 5).
- [ ] Manual: install the signed release APK (not a debug build) on a
      physical device from a different manufacturer than the primary dev
      device, run the full Phase 2–4 exit-gate checks against it.
- [ ] Manual: overnight soak test — leave the device idle (screen off, not
      touched) for 8+ hours with Wi-Fi/data on, confirm at least one
      successful periodic refresh occurred (check an in-app debug screen
      or `adb shell dumpsys jobscheduler` history, or a `Log.i` line
      grepped from `adb logcat -d` captured at the end of the soak period).

---

## 5. Risk & Mitigation Matrix

| # | Risk | Likelihood | Impact | Mitigation | Residual risk / notes |
|---|---|---|---|---|---|
| 1 | `TransactionTooLargeException` from `RemoteViews`/Binder IPC when pushing a bitmap into the widget | Medium (default if the scaling cap in §3.4 is skipped or loosened) | High — widget crashes/fails to update, potentially affects *other* widgets from the same app process sharing the transaction budget | Hard 240×240px scale-down cap before any `RemoteViews.setImageViewBitmap` call (§3.4); unit test asserting the scaler never returns a bitmap exceeding the byte budget; code review checklist item requiring any change to `NearestNeighborScaler`'s constants to re-justify the budget math in a PR comment. | If a future feature wants a larger/higher-fidelity widget image, the cap must be revisited with fresh Binder-budget math, not silently raised. |
| 2 | Vendor battery-killer daemons (MIUI, ColorOS, EMUI, etc.) silently kill the process or block WorkManager execution regardless of standard `Doze`/`AppStandby` APIs | High on affected OEMs | High — the core "stays fresh automatically" value proposition breaks silently, with no exception or log the app itself can observe (the process is just never woken) | `docs/BATTERY_SETUP.md` per-OEM whitelisting guide (Phase 5); failure-threshold in-app notification (§2.3) as a *detection* mechanism — even if we can't prevent OEM killing, the user gets told when it's clearly not working, prompting them to check the settings doc; manual tap-to-refresh remains a permanent, always-available fallback that bypasses background scheduling entirely. | Cannot be fully solved from app code alone — this is a fundamental Android ecosystem fragmentation issue. Mitigation is detection + user education, not elimination. |
| 3 | Memory leaks from repeated `Bitmap` allocation across many refresh cycles (widget process is long-lived, potentially days between OS process death) | Medium | Medium — gradual memory growth, eventual `OutOfMemoryError` or system-triggered process kill | Explicit `Bitmap.recycle()` on the *previous* cached bitmap only after the new one is successfully rendered and the old one is no longer referenced by any `RemoteViews` in flight (recycle-too-early is a worse bug than a leak — causes a crash rendering a half-updated widget, so order of operations matters: render new → confirm success → recycle old); avoid holding a `Bitmap` reference in any long-lived singleton beyond the minimum (`WidgetRefreshWorker` is short-lived by design, doesn't hold references across invocations). | LeakCanary integration considered for debug builds only (never shipped in release — adds APK size) as a Phase 5 nice-to-have if manual review isn't catching issues. |
| 4 | Render free-tier cold start (30–50s) mistaken for a network failure by the Android client's timeout config | Medium | Low-Medium — spurious failure-threshold notifications, unnecessary retry/backoff churn | OkHttp `callTimeout=25s` was set deliberately conservative relative to the worst-case cold start in Phase 2 — **this is actually a known gap**: 25s may be shorter than a true worst-case 50s cold start. Documented here explicitly as a tuning risk to revisit after Phase 1's real-deployment measurement (Phase 1 exit gate includes measuring actual cold-start latency against the deployed instance) — adjust `callTimeout` upward if measured cold starts regularly exceed it, rather than guessing further in this document. | Open item, not resolved by design alone — requires empirical measurement against the real Render deployment before Phase 2's timeout constants are finalized. |
| 5 | Render free-tier instance spin-down (15 min idle) means the *first* periodic poll after a long idle period always pays the cold-start cost, potentially causing a `retry()` → backoff → user-visible staleness even when nothing is actually broken | Medium | Low | Same `callTimeout` headroom as risk #4; additionally, `BackoffPolicy.EXPONENTIAL` with a low floor means a single cold-start-induced timeout self-corrects on the next attempt within minutes, not hours — document this as expected, benign behavior in `docs/BATTERY_SETUP.md` or a `docs/KNOWN_BEHAVIOR.md` so a future contributor doesn't "fix" it into an aggressive retry loop that would itself violate the battery-awareness constraint. | Fully acceptable given the zero-cost constraint (§1.1) — the alternative (a paid always-on tier) is explicitly out of scope. |
| 6 | SQLite `WAL` mode file growth / lack of periodic checkpointing on a long-lived, rarely-restarted process | Low | Low (single-row table, tiny data volume) | `widget.db-wal` growth is self-limiting here because writes are infrequent (one couple, manual pushes) — no dedicated checkpoint cron needed. Documented as a *non-issue for this workload* rather than silently ignored, so it isn't mistaken for an oversight. | Would need revisiting only if the write pattern changed dramatically (e.g. automated high-frequency pushes), which is out of scope (§1.5). |
| 7 | Attacker discovers the Render URL and brute-forces `WIDGET_TOKEN` via repeated `POST /update` attempts (no rate limiting currently implemented) | Low (obscure URL, but not secret-by-design per §1.4) | Medium — a successful brute force lets an attacker overwrite the couple's private image/note | `secrets.compare_digest` prevents timing-based token recovery, but does **not** prevent raw brute force of a weak token. Mitigation: enforce a strong generated token (`secrets.token_urlsafe(32)`, documented in Phase 1's remaining checklist and `.env.example`), which makes brute force computationally infeasible even without rate limiting. Rate limiting itself is deliberately deferred (not added in Phase 1) since it adds complexity disproportionate to the actual risk once the token is sufficiently strong — flagged here as a conscious scope decision, revisit if the project ever exposes a more sensitive write endpoint. | If Render logs show repeated `401`s at volume, that's the signal to revisit and add basic rate limiting (e.g. `slowapi` or a simple in-memory sliding window) — not needed pre-emptively. |
| 8 | LibreSprite PNG export settings produce an image with an alpha channel or color profile that Android's `BitmapFactory` decodes differently than expected (e.g. unexpected premultiplied-alpha edge artifacts on pixel art with hard transparency edges) | Low-Medium | Low (cosmetic) | Document a required LibreSprite export preset in `docs/ART_EXPORT_GUIDE.md` (Phase 4 deliverable, paired with the CLI docs): export as PNG, no color profile embedding, indexed or RGBA8888 (not RGBA4444), verify via the CLI's local sanity check (§2.4) which can optionally warn on unexpected PNG color-type bytes (byte offset 25 in the IHDR chunk) if this becomes a recurring issue — not built by default, added only if risk #8 materializes in practice. | Low priority — add the IHDR color-type check to the CLI only if an actual rendering artifact is observed during Phase 2/4 testing, not speculatively. |

---

## Appendix A — Glossary of project-specific terms

- **Current state**: the single (message, image, checksum, timestamp)
  tuple the backend holds — there is no history, only "now" (§1.5, §3.4).
- **Checksum**: SHA-256 hex of the raw uploaded image bytes; doubles as the
  HTTP `ETag` value across both the JSON and image endpoints.
- **Widget instance**: one placed copy of the `DotHeartWidgetProvider` on a
  home screen; "two widgets" in the project brief means two instances of
  the same provider, both reflecting the same shared backend state (§2,
  Phase 2 note).

## Appendix B — File-to-phase cross-reference (backend, already built)

| File | Phase | Role |
|---|---|---|
| `app/main.py` | 1 | FastAPI routes, exception handlers |
| `app/security.py` | 1 | Token verification, image/message validation |
| `app/storage.py` | 1 | SQLite state + atomic file writes |
| `app/config.py` | 1 | Env-var-driven settings |
| `Dockerfile` | 1, 5 | Multi-stage, non-root, single-worker container |
| `.env.example` | 1, 5 | Documents required/optional env vars |
| `render.yaml` | 5 | Not yet created — Phase 5 deliverable |
| `docs/BATTERY_SETUP.md` | 5 | Not yet created — Phase 5 deliverable |
| `docs/ART_EXPORT_GUIDE.md` | 4 | Not yet created — Phase 4 deliverable |

**Naming note**: Phase 2's prose above (and the code snippets it contains)
sketches Android class names — `DotHeartWidgetProvider`,
`WidgetRefreshWorker`, `NearestNeighborScaler` — from before implementation.
The Android module has since been built under `android/` with equivalent
classes named `CoupleWidgetProvider`, `WidgetSyncWorker`, and
`PixelArtRenderer` respectively (same responsibilities, same mechanics
described here). `WORKFLOW.md`'s directory map is the authoritative,
kept-current file/symbol listing for the Android client; treat this
document's Phase 2/3 prose as the design rationale, not the symbol source
of truth.
