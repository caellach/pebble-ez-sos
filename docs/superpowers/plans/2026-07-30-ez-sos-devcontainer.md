# EZ SOS Devcontainer + Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a working Cursor Dev Container (Core Devices `pebble-tool` + latest SDK + QEMU emulator) and a minimal EZ_SOS C/PebbleKit JS project that builds and runs on the Basalt emulator.

**Architecture:** Custom Ubuntu/Debian Dockerfile installs official SDK dependencies, `uv`, `pebble-tool`, and `pebble sdk install latest`. Cursor `.devcontainer.json` mounts the workspace and forwards `DISPLAY` / X11 for the emulator on Docker Desktop + WSL2/WSLg. Repo-root Pebble project: C watch UI stub + PebbleKit JS AppMessage stub; GPS/SMS/settings deferred.

**Tech Stack:** Core Devices pebble-tool, Pebble C SDK 4.x (via `sdk install latest`), PebbleKit JS / pypkjs in emulator, Node for PKJS packaging, Docker Dev Containers.

## Global Constraints

- Toolchain: Core Devices `pebble-tool` + latest SDK only
- Custom Dockerfile (approach A); do not base on third-party images
- Build + emulator; no USB/hardware in this phase
- Platforms: `aplite`, `basalt`, `chalk`, `diorite`, `emery`
- No real SMS, GPS, or settings UI in phase 1

---

## File Structure

| File | Responsibility |
|------|----------------|
| `docs/superpowers/specs/2026-07-30-ez-sos-devcontainer-design.md` | Approved design |
| `.devcontainer/Dockerfile` | Image with SDK + emulator libs |
| `.devcontainer/devcontainer.json` | Cursor Dev Container config |
| `package.json` | App metadata and platforms |
| `wscript` | Pebble build script |
| `src/c/main.c` | Watch UI + Select stub |
| `src/pkjs/index.js` | AppMessage log stub |
| `README.md` | Setup and usage |
| `.gitignore` | Ignore `build/` |

---

## Task 1: Design + plan docs

- [ ] Write design spec under `docs/superpowers/specs/`
- [ ] Write this plan under `docs/superpowers/plans/`

## Task 2: Dev Container

- [ ] Create `.devcontainer/Dockerfile` with Debian bookworm, SDK deps (incl. SDL2), `uv`, `pebble-tool`, `pebble sdk install latest`, non-root user
- [ ] Create `.devcontainer/devcontainer.json` with DISPLAY forwarding, X11 socket mount, C extension, postCreate sanity check

## Task 3: Skeleton app

- [ ] Create `package.json` with EZ_SOS name, UUID, all five platforms, PKJS entry
- [ ] Create standard `wscript`
- [ ] Create `src/c/main.c` with window, label, Select → vibe + AppMessage
- [ ] Create `src/pkjs/index.js` that logs incoming AppMessage

## Task 4: README + gitignore

- [ ] Write README with Docker/WSL2, reopen in container, build, emulator, display troubleshooting
- [ ] Add `.gitignore` for `build/`

## Task 5: Verify

- [ ] `pebble build` succeeds inside container
- [ ] `pebble install --emulator basalt` launches (or document display blockers if host lacks X)
- [ ] Confirm Select path is wired (code review / emulator log when display available)
