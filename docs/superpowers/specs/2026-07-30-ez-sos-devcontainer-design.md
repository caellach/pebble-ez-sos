# EZ SOS Devcontainer + Skeleton Design

**Date:** 2026-07-30  
**Status:** Approved (as-built)

## Purpose

Rebuild a modern development foundation for an SmSOS-style Pebble emergency SMS app. Phase 1 delivers a Core Devices SDK Dev Container with emulator support and a multi-platform C + PebbleKit JS skeleton. Real SOS features (GPS, SMS, safety contact settings) come later.

## Locked decisions

- **Toolchain:** Core Devices `pebble-tool` (via `uv tool install`, Python 3.13) + SDK installed with `pebble sdk install latest` at **image build** time
- **SDK pin:** Whatever `latest` resolves to when the image is built (currently **4.17**). Rebuild the Dev Container image to pick up a newer SDK; do not fetch at container start
- **Container:** Custom `debian:bookworm-slim` Dockerfile (not a third-party base image)
- **Capabilities:** Build + QEMU emulator (no USB/hardware install yet)
- **Language split:** C on the watch; PebbleKit JS on the phone for location/SMS later
- **Platforms:** `aplite`, `basalt`, `chalk`, `diorite`, `emery`
- **AppMessage key:** `SOS_REQUEST` (`uint8`, value `1` on Select)
- **Line endings:** `.gitattributes` forces LF for text (CRLF only for Windows `*.bat`/`*.cmd`/`*.ps1`)
- **Scope:** Dev container + skeleton only

## Devcontainer architecture

Custom Debian bookworm image for Cursor Dev Containers:

1. Install SDK runtime deps per official install docs: `nodejs`/`npm`, SDL2, glib, pixman, zlib, sndio, fdt, freetype, pulse, plus `curl`/`git`/`make`/`python3`/`sudo`
2. Create non-root user `pebble`; install `uv`, then `pebble-tool` (`uv tool install pebble-tool --python 3.13`), then `pebble sdk install latest` (SDK under `~/.local/share/pebble-sdk`)
3. Workspace at `/workspaces/EZ_SOS`; `remoteUser` is `pebble`
4. `.devcontainer/entrypoint.sh`:
   - Ensures `PATH` includes `pebble-tool` and the ARM toolchain
   - Symlinks the image SDK into `$HOME/.local/share/pebble-sdk` if needed
   - If host `DISPLAY` is empty, defaults to `:0` (WSLg)
5. `.devcontainer/devcontainer.json`:
   - Forwards `DISPLAY` from the host (`containerEnv`)
   - Mounts `/tmp/.X11-unix` for the emulator on Docker Desktop + WSL2/WSLg
   - Recommends the C/C++ extension (`ms-vscode.cpptools`)
   - `postCreateCommand` runs `.devcontainer/postCreate.sh`: marks `/workspaces/EZ_SOS` as a git `safe.directory`, then `pebble --version` and `pebble sdk list`
6. Image `CMD` is `sleep infinity` so the Dev Container stays up

Day-one commands:

```bash
pebble build
pebble install --emulator basalt
```

Host prerequisite: Docker Desktop with WSL2. Emulator display via WSLg; VcXsrv documented as fallback.

## Skeleton app layout

```
EZ_SOS/
  .devcontainer/
    Dockerfile
    devcontainer.json
    entrypoint.sh
    postCreate.sh
  package.json
  wscript
  src/
    c/main.c
    pkjs/index.js
  README.md
  .gitignore
  .gitattributes
```

Skeleton behavior:

- Watch shows centered **"EZ SOS"** label
- **Select** short-vibes, updates label to **"SOS sent (stub)"**, and sends AppMessage `{ SOS_REQUEST: 1 }`
- PKJS logs ready + incoming AppMessage payload (`console.log`); no GPS/SMS/settings
- Outbox failures are logged on the watch (`APP_LOG`); send success is logged too

`package.json` holds app metadata: name `EZ_SOS`, fixed UUID, `sdkVersion` `"3"`, `enableMultiJS` true, all five `targetPlatforms`, and `messageKeys: ["SOS_REQUEST"]`.

## Data flow (phase 1)

```
User → Select → Watch C (vibe + status text + outbox)
              → AppMessage { SOS_REQUEST: 1 }
              → PebbleKit JS (console.log payload)
              → QEMU via pebble install --emulator
```

## Success criteria

- Reopen in Dev Container works as user `pebble`
- `pebble --version` and `pebble sdk list` succeed (SDK active, e.g. 4.17)
- `pebble build` succeeds for all target platforms
- `pebble install --emulator basalt` shows the skeleton UI (or display troubleshooting applies if host lacks X/WSLg)
- Select triggers vibe, status text change, and JS log of `SOS_REQUEST` in the emulator

## Out of scope

- Safety contact settings page
- Real GPS / Google Maps link
- Real SMS send
- Hardware watch install / Dev Connect
- Appstore publishing

## Later phase

Classic SmSOS flow: Select → phone GPS → SMS with lat/long + Maps link; phone settings for safety contact, using modern PebbleKit JS APIs compatible with current phone apps.
