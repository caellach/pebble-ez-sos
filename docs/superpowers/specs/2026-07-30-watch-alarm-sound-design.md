# Watch alarm sound (configurable)

**Date:** 2026-07-30  
**Status:** Implemented

## Goal

On inbound SOS (watch alarm), optionally play repeating sound through the watch speaker in addition to the existing backlight + vibe loop. Watches without a usable speaker API stay silent for audio; vibe is unchanged.

## Setting

| Field | Type | Default | UI |
|-------|------|---------|-----|
| `watchAlarmSound` | boolean | `true` | Companion checkbox: **Watch alarm sound**. Same control on emulator `config.html`. |

Semantics:

- **On:** while the inbound alarm window is active, play a short urgent tone/melody on a loop until dismiss.
- **Off:** vibe + backlight only (current behavior).
- Vibe is **always** on during the alarm; sound is additive only.

## Sync

- Persist in companion settings JSON as `watchAlarmSound`.
- On settings push (first chunk), also send AppMessage key **`WATCH_ALARM_SOUND` = 12** as `UInt8` (`1` = on, `0` = off).
- Watch persists locally (new persist key) and applies immediately if an alarm is not active; if an alarm is already active, the next sound tick respects the new value (or stop sound immediately when turned off).
- PKJS normalize/default/`HOLD_MS`-style push path mirrors other scalar settings.

`package.json` / `MessageKeys.kt` must stay aligned on key **12**.

## Watch behavior (`alarm.c`)

On `alarm_handle_inbound` (existing path):

1. Backlight + repeating vibe (unchanged).
2. If `watchAlarmSound` is enabled **and** speaker is not system-muted (`speaker_is_muted()` when available): start/restart looping sound.
3. On dismiss / deinit: `speaker_stop()` + cancel any sound helper timer; leave vibe cancel as today.

### Sound pattern

- Prefer a short fixed `speaker_play_notes()` motif (a few square/sine beeps, ~0.5–1s total), then either:
  - re-trigger from `speaker_set_finish_callback`, or
  - re-trigger on the existing ~1s vibe timer when the speaker is idle.
- Volume: fixed high but not max (e.g. 80–100); no user volume control in v1.
- Classic SDK targets where `speaker_*` are stubs/no-ops: calls are safe; no audio, no crash.

### Quiet Time / mute

If `speaker_is_muted()` is true, skip starting sound (do not try to override). Vibe still runs.

## Out of scope

- Sound-only / vibe-only modes.
- User-selectable melodies or volume slider.
- Adding `flint` / `gabbro` target platforms (can be a follow-up if Core builds need explicit speaker binaries).
- Changing phone alert sound behavior.

## Testing

1. Companion: enable sound, Test watch alarm → vibe + sound on a speaker watch; vibe only on stub platforms.
2. Disable sound, Test watch alarm → vibe only.
3. Default new settings → sound on.
4. Mute / Quiet Time (if available) → vibe only even when setting is on.
5. Dismiss → sound stops immediately.
6. Settings push with app already open updates behavior for the next alarm.
