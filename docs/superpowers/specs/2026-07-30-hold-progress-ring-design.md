# Hold progress ring + configurable hold duration

**Date:** 2026-07-30  
**Status:** Approved

## Hold progress ring

In **hold** trigger mode, while Select is held:

- Draw a circular progress arc around the center title/status text
- Fill clockwise from 12 o’clock over the configured hold duration
- Early release cancels the ring and does not send SOS
- On completion, ring clears and SOS fires as today

## Configurable hold duration

- Stored in companion settings as `holdMs` (integer milliseconds)
- Default: `1500`
- Allowed presets in UI: **1s / 1.5s / 2s / 3s** (clamped 500–5000 if out of range)
- Synced to watch via AppMessage key `HOLD_MS` (11) on settings push (with `TRIGGER_MODE`)
- Watch persists `HOLD_MS` and uses it for both the fire timer and the progress ring
- Idle hint shows duration when in hold mode (e.g. “Hold Select 1.5s”)
