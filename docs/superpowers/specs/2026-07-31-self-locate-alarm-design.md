# Self-locate alarm (outbound SOS)

**Date:** 2026-07-31  
**Status:** Approved — implementing

## Goal

When the user sends an SOS, optionally put **their own** phone and watch into alarm mode so nearby people can find them more easily — even if SMS delivery fails (`check_phone`).

## Setting

| Field | Type | Default | UI |
|-------|------|---------|-----|
| `selfLocateAlarm` | boolean | `true` | Companion: **Alarm my phone & watch when I SOS (helps others find me)**. Same on emulator config. |

## When it fires

Companion, after outbound SOS finishes with final `STATUS`:

| STATUS | Self-locate |
|--------|-------------|
| `sent` | Yes |
| `check_phone` | Yes |
| `no_contacts` | No |
| `no_gps` | No |
| `accepted` (interim) | No |

Also: companion **Test self-locate alarm** (no SMS).

## Behavior

### Watch

- New AppMessage key **`SELF_LOCATE_ALERT` = 13** (`UInt8` 1).
- Same alarm machinery as inbound (backlight, vibe, optional speaker, dismiss, 2s suppress after dismiss).
- Distinct copy: **“SOS active”** (not “Incoming SOS”).
- Cold-start: same startApp + delayed retry pattern as inbound.
- Respect `watchAlarmSound` / `speaker_is_muted()` as for inbound.

### Phone

- Use the user’s existing **`phoneAlertMode`** (`off` / `notification` / `fullscreen`).
- If phone mode is `off`, watch still alarms when `selfLocateAlarm` is on.
- Notification/fullscreen copy should indicate self-locate / “SOS active”, not “incoming from contact”, when triggered from this path.

## Sync

- Persist `selfLocateAlarm` in settings JSON (companion source of truth).
- Watch does not need the flag to *receive* alerts (companion gates). Optional: still sync for future local UI; not required for v1.

## Out of scope

- Separate phone vs watch toggles (v1 = both or neither via one checkbox).
- Changing contact inbound alarm copy.
- iOS.
