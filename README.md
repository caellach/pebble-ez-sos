# EZ SOS

Pebble watchapp (C + PebbleKit JS) that sends an emergency SMS with GPS coordinates to your safety contacts. On **Android**, contacts who also run the companion get an urgent phone + watch alarm when you SOS them (and the same in reverse) — not a quiet SMS notification.

Inspired by the original [SmSOS](https://apps.repebble.com/smsos_54d417514ba76441e5000032) app.

**Watchapp UUID:** `cf79bb9f-ab43-4848-81b6-d1c1ae6a9226` (must match root `package.json` and the Android companion).

**Version:** Semver lives in [`VERSION`](VERSION) (synced with `package.json`). Android `versionName` / `versionCode` are derived from that file. After bumping `VERSION`, run `scripts/sync-version.sh` and commit both. Pushing a `VERSION` change to `master` runs [`.github/workflows/release.yml`](.github/workflows/release.yml), which builds the watchapp + a **signed** companion APK and publishes both to the same GitHub Release (`vX.Y.Z`). Signing uses repo secrets (`ANDROID_KEYSTORE_*`); release builds also require `EZ_SOS_AUTH_KEY` (64 hex) for inbound SOS tokens — see [android/README.md](android/README.md#release-signing-github-secrets).

## How it works

EZ SOS is **mutual coverage**: you and your safety contacts each install the Android companion. Either party can trigger SOS from the watch; the others get an urgent alarm (phone + watch), not a single SMS ding.

1. **On Android:** each party installs the companion and adds the others as enabled contacts.
2. Trigger SOS on the watch (mode-dependent).
3. The companion gets GPS and silently texts **all enabled** contacts with your prefix, lat/lon, and a Google Maps link.
4. On the receiving side, an SMS from an enabled contact whose body starts with `EZ SOS:` **and** ends with a valid trailing `ez.` auth token (within the receiver’s `authWindowMinutes`, default **15**) raises an **Incoming SOS** alarm on their phone (configurable) and watch. The token is a shared compile-time secret baked into the APK — it stops casual spoofing, not determined forgery.

Without the companion on Android, Pebble settings / SOS directs you to install it. The emulator can still use a PKJS HTML contact list for `sms:` fallback testing.

### Trigger modes

| Mode | Watch hint | Behavior |
|------|------------|----------|
| **confirm** (default) | Press to confirm | Select → **Confirm SOS?** → Select sends; Back cancels |
| **single** | Press Select | Select sends immediately |
| **hold** | Hold Select | Hold Select ~1.5s to send; release early cancels |

### Watch statuses

| Status | Meaning |
|--------|---------|
| Sending… | SOS in progress |
| Sent | Silent SMS succeeded (Android companion) |
| **Check phone** | Fallback: phone opened prefilled Messages (`sms:`) — finish/send there |
| No contacts | No enabled contacts configured |
| No GPS | Location unavailable; message not sent |
| Phone offline | Phone/JS did not respond in time |
| Incoming SOS | SMS from an enabled contact starting with `EZ SOS:` plus a valid fresh `ez.` auth token (Android); phone + watch alarm (optional repeating watch speaker tone when enabled); Select or Back dismisses on watch |

## Android companion

Silent outbound SMS, contacts UI, and inbound SOS → phone/watch alarm require the Kotlin companion under [`android/`](android/).

**Summary:**

1. Sideload the companion APK (debug via `./gradlew assembleDebug`, or the signed release from GitHub Releases).
2. Grant **SMS**, **contacts**, and **location** on first run; notifications on Android 13+ as prompted.
3. Install the watch `.pbw`, then **Edit contacts & settings** in the companion and save (syncs to the watch).
4. Trigger SOS → expect **Sent** (or **Check phone** / **No GPS** if silent send or location fails).

Deep link: `ezsos://settings`. Full build notes: **[android/README.md](android/README.md)**.

### Manual Android test checklist

1. Configure **1+ enabled contacts in the companion** (people who also have EZ SOS); confirm sync. Optionally set **accept SOS tokens within…** (`authWindowMinutes`: 5 / 15 / 30 / 60; default 15).
2. Trigger SOS → watch shows **Sent** (silent) or **Check phone** (fallback). Outbound SMS includes a final `ez.` auth token line.
3. On another phone with the companion (same auth key build), SMS **from that contact number** with `EZ SOS:` + valid token → **Incoming SOS** alarm (phone + watch).
4. SMS from an enabled contact **without** the `EZ SOS:` prefix, **without** a token, with a token from the **wrong key**, outside the auth window, or from an **unknown number** → no alarm.
5. Without companion installed → Pebble settings prompts to install.

## iOS unsupported

**iOS is not supported.** Apple does not allow third-party apps to read inbound SMS content or reliably send silent SMS. This app cannot provide inbound SMS → watch alarm (or reliable silent outbound) on iPhone. Use Android + the companion for the full experience. Emulator/PKJS-only paths can still exercise settings and the `sms:` fallback UI.

## Prerequisites (host)

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) with **WSL2** backend (Windows)
- [Cursor](https://cursor.com/) or VS Code with the **Dev Containers** extension
- For the emulator GUI: **WSLg** (Windows 11 default with WSL) or [VcXsrv](https://sourceforge.net/projects/vcxsrv/) if the QEMU window does not appear
- Android companion builds need an Android SDK / Android Studio on the **host** (not included in the Pebble Dev Container by default) — see [android/README.md](android/README.md)

## Open in Dev Container

1. Open this repo in Cursor
2. Command Palette → **Dev Containers: Reopen in Container**
3. Wait for the image build (first time downloads `pebble-tool` + SDK)

The container runs as user `pebble` with Core Devices `pebble-tool` and the latest SDK preinstalled.

## Build (watchapp)

```bash
pebble build
```

Output: `build/EZ_SOS.pbw`

## Run in emulator

```bash
pebble install --emulator basalt
```

Other platforms: `aplite`, `chalk`, `diorite`, `emery`.

Emulator covers trigger modes, status strings, and config/AppMessage round-trips. It does **not** send real SMS or run inbound alarms (no Android companion).

Open settings against the emulator with:

```bash
pebble emu-app-config
```

## Display troubleshooting (Windows)

If `pebble install --emulator` fails with a display/X11 error:

1. Confirm Docker Desktop uses the WSL2 engine
2. From a WSL terminal, check `echo $DISPLAY` (often `:0` with WSLg)
3. Or install VcXsrv, start it with “Disable access control”, and set host `DISPLAY` to your Windows IP / `:0.0` so the container can forward it
4. The Dev Container mounts `/tmp/.X11-unix` and reads `DISPLAY` from the host

## Docs

- **App design:** [docs/superpowers/specs/2026-07-30-ez-sos-app-design.md](docs/superpowers/specs/2026-07-30-ez-sos-app-design.md)
- **Inbound SOS auth token:** [docs/superpowers/specs/2026-07-31-inbound-sos-auth-token-design.md](docs/superpowers/specs/2026-07-31-inbound-sos-auth-token-design.md)
- **App implementation plan:** [docs/superpowers/plans/2026-07-30-ez-sos-app.md](docs/superpowers/plans/2026-07-30-ez-sos-app.md)
- Dev container design: [docs/superpowers/specs/2026-07-30-ez-sos-devcontainer-design.md](docs/superpowers/specs/2026-07-30-ez-sos-devcontainer-design.md)
- Dev container plan: [docs/superpowers/plans/2026-07-30-ez-sos-devcontainer.md](docs/superpowers/plans/2026-07-30-ez-sos-devcontainer.md)
- Android companion: [android/README.md](android/README.md)
- C SDK: [developer.repebble.com/docs/c](https://developer.repebble.com/docs/c/)
