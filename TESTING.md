# DotHeart — Local Testing Guide

A zero-headache path to verifying the whole pipeline — backend, CLI, and
Android client — on your own machine before deploying to Render or
installing the APK on a second phone. Every command below is copy-pasteable
and has been run against this exact repository while writing this guide.

Three ways to use this doc:
- **Fastest**: skip to [§4](#4-one-command-smoke-test) and run
  `python scripts/test_pipeline.py`. It starts its own backend, pushes a
  generated test sprite, verifies everything, and cleans up after itself.
- **Manual, step-by-step**: follow §1–3 to run each piece yourself and watch
  it work.
- **Android-specific**: jump to [§3](#3-android-network-bridge) once the
  backend is running, to point an emulator or physical device at it.

---

## 1. Backend: venv, install, run

```bash
# From the repo root.
python -m venv .venv

# macOS/Linux:
source .venv/bin/activate
# Windows (PowerShell):
.venv\Scripts\Activate.ps1
# Windows (Git Bash, used throughout this doc):
source .venv/Scripts/activate

pip install -r requirements.txt
```

There is no database migration step — `app/storage.py` creates
`widget.db` (SQLite, `CREATE TABLE IF NOT EXISTS`) and the `images/`
directory automatically on first startup. "Storage setup" is just picking a
`DATA_DIR` and a `WIDGET_TOKEN`:

```bash
export WIDGET_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
export DATA_DIR=./data
echo "Generated WIDGET_TOKEN=$WIDGET_TOKEN"   # you'll need this value again below
```

Start the server with hot reload for local development:

```bash
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

Verify it's up in a second terminal:

```bash
curl -s http://127.0.0.1:8000/health
# {"status":"ok"}
```

`--reload` watches `app/*.py` and restarts the worker on save — the normal
edit/save/retest loop while working on the backend. Do **not** use
`--reload` for anything resembling production (it forks a file-watcher
process and disables the `Dockerfile`'s single-worker guarantee).

---

## 2. Test sprite: push something without opening LibreSprite

Two ways to get a transparent pixel-art heart PNG with zero setup:

### 2a. Use the committed fixtures

`scripts/fixtures/heart_16.png` and `scripts/fixtures/heart_32.png` are
checked into the repo — small (118 and 168 bytes), procedurally generated,
genuinely transparent-background pixel art. Push one immediately:

```bash
export DOTHEART_SERVER_URL=http://127.0.0.1:8000
export DOTHEART_TOKEN="$WIDGET_TOKEN"   # from §1

python scripts/push_art scripts/fixtures/heart_32.png "local test"
```

Expected output:
```
Server:  http://127.0.0.1:8000
Image:   scripts/fixtures/heart_32.png (168 bytes, image/png)
Message: local test
Success!
  message:   local test
  image_url: http://127.0.0.1:8000/static/widget_image.png
  checksum:  <sha256 hex>
  pushed at: <UTC timestamp>
```

### 2b. Regenerate them (or make a different size)

`scripts/heart_sprite.py` is a standalone, dependency-free PNG generator
(stdlib `zlib`/`struct` only — no Pillow). It rasterizes the classic
implicit heart curve `(x²+y²−1)³ − x²y³ ≤ 0` at hard pixel-art edges (no
antialiasing), matching the same nearest-neighbor, no-blur philosophy the
Android client's `PixelArtRenderer` uses:

```bash
python scripts/heart_sprite.py 32 scripts/fixtures/heart_32.png
python scripts/heart_sprite.py 16 scripts/fixtures/heart_16.png
# or any other size, any other output path:
python scripts/heart_sprite.py 64 /tmp/big_heart.png
```

It's also importable — `scripts/test_pipeline.py` (§4) uses
`heart_sprite.generate_heart_png(size)` directly rather than shelling out.

### Verify the push landed

```bash
curl -s http://127.0.0.1:8000/api/v1/widget/current | python -m json.tool

# Headers only, without downloading the body: use -o /dev/null -D -, NOT
# curl -I. The /static route only implements GET (app/main.py), so a HEAD
# request (what -I sends) gets 405 Method Not Allowed.
curl -s -o /dev/null -D - http://127.0.0.1:8000/static/widget_image.png
```

---

## 3. Android network bridge

The Android client's backend URL is `BuildConfig.DOTHEART_BASE_URL`
(`android/app/build.gradle.kts`), which already defaults per build type:

| Build type | Default `DOTHEART_BASE_URL` |
|---|---|
| `debug` | `http://10.0.2.2:8000` |
| `release` | `https://dotheart.onrender.com` |

Override either without editing the file:
```bash
cd android
./gradlew assembleDebug -PdotheartBaseUrl=http://192.168.1.20:8000
```

### 3a. Android Emulator — no extra setup needed

`10.0.2.2` is the emulator's built-in alias for the host machine's
loopback address — it already resolves to wherever `127.0.0.1:8000` on
your dev machine is listening, with **no** `adb` command and no manifest
change required. This is the debug build type's default for exactly this
reason. Just:

1. Start the backend (§1), listening on `127.0.0.1:8000` (or pass
   `--host 0.0.0.0` if you also want it reachable from a physical device
   on the same interface).
2. Build and install the debug APK from Android Studio, or:
   ```bash
   cd android
   ./gradlew installDebug
   ```
3. Place the widget. It should sync within a few seconds (manual
   tap-to-refresh doesn't wait for the periodic 30-minute schedule).

Cleartext (plain HTTP, no TLS) is required for this to work at all — the
debug build only permits it to `10.0.2.2`, `localhost`, and `127.0.0.1` (see
`android/app/src/debug/res/xml/network_security_config_debug.xml`); a
release build stays HTTPS-only. If the widget shows the error badge, check
`adb logcat` for `CLEARTEXT communication ... not permitted` — that means
`DOTHEART_BASE_URL` is pointed somewhere not on that allow-list (see §3b).

### 3b. Physical device — two options

**Option 1 (recommended): `adb reverse`, no manifest edit needed**

```bash
adb reverse tcp:8000 tcp:8000
```

This maps the *device's* `127.0.0.1:8000` to your dev machine's
`127.0.0.1:8000` over the existing USB/ADB connection — no Wi-Fi, no
firewall rule, no LAN at all. Since `127.0.0.1` is already in the debug
network security config's cleartext allow-list, you don't need to edit
anything: just build the debug APK with
`DOTHEART_BASE_URL=http://127.0.0.1:8000`, which is already the case if
you leave the Gradle property at its default emulator value — `10.0.2.2`
does **not** work with `adb reverse`, so override it:

```bash
cd android
./gradlew installDebug -PdotheartBaseUrl=http://127.0.0.1:8000
```

Re-run `adb reverse tcp:8000 tcp:8000` after every device reconnect/reboot
(the mapping doesn't persist); `adb reverse --list` shows active mappings,
`adb reverse --remove-all` clears them.

**Option 2: LAN IP**

Find your dev machine's LAN IP:
```bash
# macOS/Linux:
ipconfig getifaddr en0   # or: ip addr show
# Windows:
ipconfig   # look for "IPv4 Address" under your active adapter
```

Start the backend listening on all interfaces, not just loopback:
```bash
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Both devices must be on the **same** Wi-Fi network, and your dev machine's
firewall must allow inbound connections on port 8000 (macOS/Windows will
usually prompt on first connection; allow it).

This LAN IP is **not** in `network_security_config_debug.xml`'s cleartext
allow-list by default (it can't be — the config is static and the IP
changes per network). Add it before building:

```xml
<!-- android/app/src/debug/res/xml/network_security_config_debug.xml -->
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">10.0.2.2</domain>
    <domain includeSubdomains="false">localhost</domain>
    <domain includeSubdomains="false">127.0.0.1</domain>
    <domain includeSubdomains="false">192.168.1.20</domain>  <!-- add your LAN IP -->
</domain-config>
```

Then:
```bash
cd android
./gradlew installDebug -PdotheartBaseUrl=http://192.168.1.20:8000
```

Prefer Option 1 (`adb reverse`) day-to-day — it needs no manifest edit, no
firewall prompt, and works identically on any network (or no network at
all, since it rides the USB connection).

---

## 4. One-command smoke test

`scripts/test_pipeline.py` automates everything in §1–2 and verifies the
result — for a quick "did I break anything" check, or to run before a
Render deploy or handing the APK to another phone.

```bash
python scripts/test_pipeline.py
```

What it does, in order:
1. Checks whether a backend is already running at `http://127.0.0.1:8000`.
   If not, starts its own `uvicorn` subprocess against a throwaway
   temp `DATA_DIR` and a freshly generated `WIDGET_TOKEN` — no manual setup
   needed for a first run.
2. Generates a heart sprite via `heart_sprite.generate_heart_png()`.
3. Pushes it through the **real** `scripts/push_art` CLI (as a subprocess —
   this test exercises the actual tool, not a reimplementation of its
   upload logic).
4. Verifies:
   - `GET /health` responded before proceeding
   - `push_art` exited `0`
   - `GET /api/v1/widget/current` returns `200`
   - the response `checksum` matches the sha256 computed locally over the
     exact bytes generated in step 2
   - the response `message` matches what was pushed
   - `GET /static/{filename}` returns `200`
   - the response body starts with the PNG magic bytes (`\x89PNG\r\n\x1a\n`)
   - the response body is **byte-for-byte identical** to the uploaded
     sprite (validates the backend's atomic-write path preserved the exact
     bytes, not just "some image")
   - the `Cache-Control` header is present and correct
5. Tears down the subprocess and temp directories it created, whether the
   checks passed or failed.

Exit code `0` only if every check passed — safe to wire into a pre-deploy
checklist or a CI job. Sample output:

```
== Backend availability ==
  Starting throwaway backend on http://127.0.0.1:56925 (DATA_DIR=...)
[PASS] Started backend at http://127.0.0.1:56925

== Sprite generation ==
[PASS] Generated 168-byte 32x32 heart PNG (checksum 223221423de0...)

== push_art upload ==
  ...
[PASS] push_art exited 0

== GET /api/v1/widget/current ==
[PASS] current endpoint returned HTTP 200
[PASS] checksum matches locally computed sha256 (223221423de0...)
[PASS] message round-tripped correctly

== GET /static/{filename} ==
[PASS] static endpoint returned HTTP 200
[PASS] response has a valid PNG magic-byte header
[PASS] served bytes are byte-for-byte identical to the uploaded sprite
[PASS] Cache-Control header present: 'no-cache, max-age=0, must-revalidate'

============================================================
ALL CHECKS PASSED
```

### Useful flags

```bash
# Smaller sprite:
python scripts/test_pipeline.py --size 16

# Run against a backend you started manually (§1) instead of spinning up
# a throwaway one - requires DOTHEART_TOKEN set to that instance's
# WIDGET_TOKEN, since the script has no way to know a token it didn't
# generate itself:
export DOTHEART_TOKEN="$WIDGET_TOKEN"
python scripts/test_pipeline.py --server-url http://127.0.0.1:8000 --no-start-server

# Pin the throwaway backend to a specific port (default: an OS-assigned
# free port, to avoid colliding with anything else you have running):
python scripts/test_pipeline.py --port 8123
```

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `push_art` prints "missing required configuration" | `DOTHEART_SERVER_URL`/`DOTHEART_TOKEN` not set and no `.env` found | Export them, or copy `scripts/.env.example` to `./.env` |
| `push_art` gets `HTTP 401` | `DOTHEART_TOKEN` doesn't match the backend's `WIDGET_TOKEN` | They're two different env var names for the *same* secret in two different processes — verify they're identical |
| Widget shows the error badge, `adb logcat` shows `CLEARTEXT communication ... not permitted` | `DOTHEART_BASE_URL` points at a plain-HTTP host not in the debug cleartext allow-list | Use `adb reverse` (§3b Option 1), or add the host to `network_security_config_debug.xml` (§3b Option 2) |
| `test_pipeline.py` fails at "Backend availability" with a 20s timeout | Something else already bound the chosen port, or `app/` has an import error | Check the printed subprocess output in the failure message; try `--port` with an explicit free port |
| `rm`/cleanup fails with "Device or resource busy" on `data/widget.db*` after a manual `--reload` session | A prior `uvicorn --reload` process is still holding the SQLite file open | Stop that process first (`Ctrl+C` in its terminal, or find and kill it) before deleting `data/` |
| `curl -I .../static/widget_image.png` returns `405 Method Not Allowed` | The route only implements `GET`, not `HEAD` | Use `curl -s -o /dev/null -D - <url>` instead (see §2) |
| A repeated `curl -H "If-None-Match: ..."` never gets a `304`, always `200` with the full body | Conditional-GET (`If-None-Match` → `304`) is **not implemented server-side yet** — tracked as remaining Phase 1 work in `PLAN.md`. `/static` does emit an auto-generated `etag` header (Starlette's `FileResponse` default, based on file mtime/size, not the SHA-256 `checksum`), but nothing in `app/main.py` currently reads `If-None-Match` to act on it. This is expected current behavior, not a bug in your test | None needed for now — the Android client's own bandwidth-saving path (comparing the JSON `checksum` field to its locally cached value, skipping the image re-fetch and re-render) still works, since that comparison happens client-side regardless of whether the server itself short-circuits |
