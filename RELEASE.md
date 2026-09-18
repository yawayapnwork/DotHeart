# DotHeart — Release Guide

Two independent procedures, meant to be done in this order for a first
release: deploy the backend, then point a release APK build at it.
Everything below was actually run against this repository while writing
this guide (Gradle build, `keytool`, `apksigner verify`, the whole
signing chain) — not just described.

---

## Part 1 — Deploy the backend to Render

### 1a. Push the repo to a Git host Render can see

Render deploys from a Git repository (GitHub, GitLab, or a generic Git
URL). If this repo isn't already pushed somewhere Render can reach, push
it to a **private** GitHub repository first — DotHeart is explicitly a
private, self-hosted tool (see `PLAN.md` §1.4), so keep the source
repository private even though the backend URL itself won't be secret.

### 1b. Deploy via Blueprint (recommended — uses the committed `render.yaml`)

1. Go to <https://dashboard.render.com/blueprints> and click **New Blueprint Instance**.
2. Connect the GitHub repository. Render reads `render.yaml` from the repo
   root automatically and proposes the `dotheart-backend` web service
   exactly as configured there (Docker runtime, `./Dockerfile`, free plan,
   `/health` health check path).
3. Click **Apply**. Render builds the Docker image and deploys it.
4. **Set the real secret** — `render.yaml` intentionally leaves
   `WIDGET_TOKEN` unset (`sync: false`) so it's never committed. In the
   Render dashboard: the `dotheart-backend` service → **Environment** →
   add `WIDGET_TOKEN` with a strong generated value:
   ```bash
   python -c "import secrets; print(secrets.token_urlsafe(32))"
   ```
   Save. Render redeploys automatically when an env var changes.
5. Once deployed, note the service URL Render assigns
   (`https://dotheart-backend-XXXX.onrender.com` or your own custom name) —
   this is what goes into `DOTHEART_BASE_URL` for the release APK (Part 2)
   and `DOTHEART_SERVER_URL` for `scripts/push_art`.

### 1c. Deploy via the dashboard UI instead (equivalent, no `render.yaml` needed)

If you'd rather click through the UI (or Render's Blueprint flow ever
changes): **New** → **Web Service** → connect the repo, then set:

| Field | Value |
|---|---|
| Runtime | Docker |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context Directory | `.` |
| Instance Type | Free |
| Health Check Path | `/health` |

Environment variables (**Environment** tab):

| Key | Value |
|---|---|
| `WIDGET_TOKEN` | a generated secret (see step 1b.4 above) |
| `DATA_DIR` | `/var/data` |
| `LOG_LEVEL` | `INFO` |

Click **Create Web Service**. This produces the identical deployment
`render.yaml` describes — the file exists so this doesn't have to be
repeated by hand on a second machine or after an account change, not
because the UI path is wrong.

### 1d. Verify the deployment

```bash
curl -s https://<your-service>.onrender.com/health
# {"status":"ok"}

export DOTHEART_SERVER_URL=https://<your-service>.onrender.com
export DOTHEART_TOKEN=<the WIDGET_TOKEN you set in step 1b.4>
python scripts/push_art scripts/fixtures/heart_32.png "hello from render"
```

Or run the full automated check against it:
```bash
export DOTHEART_TOKEN=<the WIDGET_TOKEN you set in step 1b.4>
python scripts/test_pipeline.py --server-url https://<your-service>.onrender.com --no-start-server
```
(See `TESTING.md` §4 for what this verifies.)

### 1e. The free-tier disk caveat (read this before relying on it)

Render's **free** web service plan does not support the persistent disk
add-on. `DATA_DIR=/var/data` is ordinary container filesystem: it is wiped
on every deploy and on any instance recycle Render's free-tier
infrastructure performs on its own schedule. `render.yaml` documents this
inline with a commented-out `disk:` block to uncomment if you ever
upgrade to a paid plan.

Practical consequence: after a redeploy (including one triggered by a
`git push`, or by changing an env var as in step 1b.4), `GET
/api/v1/widget/current` will 404 until you push a fresh image+note again.
This is a conscious zero-cost tradeoff (`PLAN.md` §1.1), not a bug — just
re-push after any redeploy.

### 1f. Cold start and the Android client's timeouts

Render's free plan spins the container down after ~15 minutes with no
inbound traffic. The **next** request pays a cold-start penalty — the
platform's routing layer accepts the TCP connection immediately but holds
the HTTP response while the container boots, observed here in the ~30–50s
range. `healthCheckPath: /health` in `render.yaml` is used by Render
*during a deploy* to confirm the new instance is ready before cutting
traffic over to it — it does **not** keep a free-tier instance perpetually
warm or prevent the 15-minute idle spindown; there is no free-tier
configuration option that does.

The Android client is already built around this (this is a description of
what's implemented, not a proposal): `HttpClientProvider`
(`android/app/src/main/java/com/dotheart/widget/net/HttpClientProvider.kt`)
sets `readTimeout`/`callTimeout` to **60s/65s**, specifically sized to let
a single request survive a full cold start rather than misreport it as a
failure — well above the "15s+" floor, because 15s alone would time out on
almost every post-idle first request. `connectTimeout` stays at 15s, since
Render's edge accepts the TCP connection promptly regardless of container
state — a slow *connect* there means an actual network problem, not a cold
start, and should still fail fast. `WidgetRepository`'s in-call retry is
correspondingly set to 2 attempts (not 3): a single attempt can already
absorb the cold start on its own, so a second attempt exists only to catch
a genuine transient blip, not to re-wait for the same cold start — 3
attempts at 65s each would let a single background sync run for nearly 3
minutes worst case, which is excessive.

If you want to avoid cold starts entirely (optional, adds an external
moving part this project otherwise avoids — not set up by default): a
scheduled external ping to `/health` every ~10 minutes (e.g. a GitHub
Actions cron workflow, or Render's own paid "Starter" plan which doesn't
spin down) keeps the free instance warm. Not required for correctness —
the client already tolerates cold starts — only for lower perceived
latency on the first poll after a gap.

---

## Part 2 — Build a signed release APK

### 2a. Generate a release keystore

Do this **once**, ever, for this app (a lost or rotated key means every
future release is a different signer, and existing installs can't upgrade
in place — Android refuses to install a differently-signed APK over an
existing one). Store the resulting `.jks` file **outside** this repository
entirely, and back it up somewhere durable.

```bash
keytool -genkeypair -v \
  -keystore ~/dotheart-release.jks \
  -alias dotheart \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name, OU=DotHeart, O=Personal, L=Unknown, ST=Unknown, C=US"
```

`keytool` (bundled with every JDK) prompts for a keystore password and a
key password interactively — use a real password manager, not a throwaway
value; treat these exactly like the `WIDGET_TOKEN` secret. `-validity
10000` is ~27 years, so you don't have to think about certificate
expiry again. Confirm it was created correctly:

```bash
keytool -list -v -keystore ~/dotheart-release.jks -alias dotheart
```

### 2b. Configure Gradle to sign with it

Already implemented in `android/app/build.gradle.kts` — the `release`
build type's `signingConfig` is populated from **either**:

1. `android/keystore.properties` (a local, gitignored file — copy
   `android/keystore.properties.example` and fill in real values), **or**
2. Four environment variables, if that file is absent: `DOTHEART_KEYSTORE_PATH`,
   `DOTHEART_KEYSTORE_PASSWORD`, `DOTHEART_KEY_ALIAS`, `DOTHEART_KEY_PASSWORD`
   (useful for CI, where no local file exists).

```bash
cp android/keystore.properties.example android/keystore.properties
```
then edit `android/keystore.properties`:
```properties
storeFile=/absolute/path/to/dotheart-release.jks
storePassword=<the keystore password from 2a>
keyAlias=dotheart
keyPassword=<the key password from 2a>
```

`storeFile` should be an **absolute path** — a relative path resolves
against `android/app/` (the Gradle module directory), which is easy to
get wrong; absolute removes the ambiguity entirely.

If neither the properties file nor the environment variables are present,
`assembleRelease` still succeeds (so a fresh clone or a compile-only CI
check isn't blocked) but produces an **unsigned** APK — Gradle prints an
explicit warning (`release signingConfig not set: ... assembleRelease
will produce an UNSIGNED APK`) at configuration time, and the output
filename itself reflects it: `app-release-unsigned.apk` vs.
`app-release.apk` when properly signed. Verified directly in this repo
while writing this guide — both paths build successfully, and the
filename difference is the reliable tell if you ever forget to check.

### 2c. Build

```bash
cd android
./gradlew assembleRelease
```

Requires **JDK 17** (AGP 8.5.2's floor) — see `TESTING.md`/the wrapper
setup notes if `java -version` shows something older. Output:

```
android/app/build/outputs/apk/release/app-release.apk
```

R8 full-mode minification + resource shrinking are already enabled
(`isMinifyEnabled = true`, `isShrinkResources = true`) — a real build run
against this exact codebase while writing this guide produced a **release
APK under 400KB**, far inside the `PLAN.md` §1.1 <15MB budget; that number
will grow somewhat as the app gains features, but confirms the minimal-
dependency design (no image-loading library, no JSON library beyond
`org.json`, no Play Services) is working as intended. Check your own build
before distributing:

```bash
ls -la android/app/build/outputs/apk/release/app-release.apk
```

### 2d. Verify the signature before distributing it

Never assume a successful build means a correctly signed artifact — check
directly, using `apksigner` from the Android SDK build-tools:

```bash
"$ANDROID_HOME/build-tools/<version>/apksigner" verify --verbose \
  android/app/build/outputs/apk/release/app-release.apk
```

Expect output ending in `Verifies` with `Verified using v2 scheme (APK
Signature Scheme v2): true` (v2/v3 signing is what matters for `minSdk
26`; a `false` on the legacy v1/JAR scheme alone is normal and not a
problem — v1 exists only for pre-Android-7 compatibility this app's
`minSdk` already exceeds).

### 2e. Distribute

The signed APK at `android/app/build/outputs/apk/release/app-release.apk`
is a normal file — send it exactly like any other file via WhatsApp,
Quick Share/Nearby Share, Google Drive, email, whatever's convenient. No
Play Store account, no App Bundle format, no additional packaging step.

On the receiving device, the recipient will see Android's standard
"install from unknown sources" / "unsafe app blocked" prompt the first
time — this is expected for any app not distributed through Play Store,
including this one by design (`PLAN.md` §1.4: DotHeart is explicitly
sideloaded, not Play-distributed). They'll need to allow installation from
whichever app they downloaded the file through (Files, WhatsApp, Chrome,
etc.) in Android's settings.

To install directly from your dev machine instead of sending the file:
```bash
adb install android/app/build/outputs/apk/release/app-release.apk
```
(fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` if a debug build with a
different signature — e.g. the auto-generated debug keystore, which is
`applicationIdSuffix = ".debug"` and so technically a different
`applicationId` anyway — is already installed; that's expected, not a
bug, since debug and release are different apps by design here.)

### 2f. Every subsequent release

Steps 2a and 2b are one-time setup. For every new version afterward:

1. Bump `versionCode` (integer, must strictly increase) and `versionName`
   (human-readable, e.g. `"1.1.0"`) in `android/app/build.gradle.kts`.
2. `cd android && ./gradlew assembleRelease` (keystore.properties from 2b
   is already in place — no need to regenerate anything).
3. Verify (2d), distribute (2e).

---

## Quick reference

| What | Where |
|---|---|
| Backend IaC config | `render.yaml` (repo root) |
| Backend secret (`WIDGET_TOKEN`) | Render dashboard → service → Environment — never committed |
| APK signing config | `android/app/build.gradle.kts` (`signingConfigs` block) |
| Local keystore credentials | `android/keystore.properties` (gitignored; template: `android/keystore.properties.example`) |
| CI keystore credentials | `DOTHEART_KEYSTORE_PATH` / `DOTHEART_KEYSTORE_PASSWORD` / `DOTHEART_KEY_ALIAS` / `DOTHEART_KEY_PASSWORD` env vars |
| Cold-start timeout tuning | `android/app/src/main/java/com/dotheart/widget/net/HttpClientProvider.kt` |
| Release APK output | `android/app/build/outputs/apk/release/app-release.apk` |
