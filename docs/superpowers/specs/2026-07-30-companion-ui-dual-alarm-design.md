# EZ SOS companion UI + dual alarm design

**Date:** 2026-07-30  
**Status:** Approved

## Purpose

Improve companion main-screen UI and make inbound SMS alerts fire on both watch and phone, with a user-configurable phone alert style and test buttons.

## Phone alert mode

Stored in companion settings (synced with other settings as part of the settings JSON; watch ignores unknown fields).

| Mode | Behavior |
|------|----------|
| `off` | Watch only (`INBOUND_ALERT`) |
| `notification` | High-priority heads-up notification + sound/vibrate |
| `fullscreen` | Full-screen alarm activity until dismissed |

**Default:** `notification`

On inbound SMS from an enabled contact:
1. Always attempt watch `INBOUND_ALERT` (launch app + AppMessage, then a delayed retry so cold launch still alarms).
2. Apply the selected phone mode in parallel (not “fallback only”).

## Test controls

On the main screen:
- **Test watch alarm** — send `INBOUND_ALERT` (same path as a real reply).
- **Test phone alarm** — run the currently selected phone mode without touching the watch.

## Main UI

- Use ActionBar title only (remove duplicate in-layout “EZ SOS”) so content is not covered.
- Status text: drop the classic-PebbleKit Note paragraph.
- Remove iOS unsupported footer.
- Keep: status, contacts summary, Edit contacts & settings, Pull settings from watch.
- Add: Test watch alarm, Test phone alarm.
- Overflow menu (⋮): **Event log**, **Grant permissions**.
- Event log: separate activity; Clear button; no instructional hint.

## Out of scope

- Reply-token / non-falsifiable inbound SMS (separate follow-up).
- Changing watch alarm UI.
