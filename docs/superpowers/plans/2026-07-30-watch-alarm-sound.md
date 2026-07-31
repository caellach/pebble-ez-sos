# Watch Alarm Sound Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable, default-on `watchAlarmSound` setting so inbound SOS alarms can loop speaker audio in addition to vibe/backlight.

**Architecture:** Companion owns the boolean in settings JSON and pushes `WATCH_ALARM_SOUND` (key 12) with the first settings chunk (same pattern as `HOLD_MS`). Watch persists it and, while the inbound alarm is active, plays a short fixed `speaker_play_notes()` motif on each vibe tick when sound is enabled and not system-muted; dismiss/deinit calls `speaker_stop()`. Classic SDK stubs make speaker calls no-ops.

**Tech Stack:** Pebble C (SDK 3 Speaker API), PebbleKit JS, Kotlin Android companion, AppMessage keys in `package.json` / `MessageKeys.kt`.

## Global Constraints

- Setting name: `watchAlarmSound` (boolean), default `true`
- AppMessage key: `WATCH_ALARM_SOUND` = `12` (`UInt8`: 1=on, 0=off)
- Vibe always remains on during inbound alarm; sound is additive only
- Do not add `flint` / `gabbro` platforms in this plan
- Do not change phone alert sound behavior
- No melody picker or volume UI in v1 (fixed motif, volume ~90)
- Prefer matching existing `holdMs` / `TRIGGER_MODE` sync patterns
- If using subagents for execution: model **cursor-grok-4.5-high-fast** only (user: “grok 4.5 high”)
- Commit only when the user explicitly asks (do not auto-commit per task unless requested)

---

## File map

| File | Responsibility |
|------|----------------|
| `package.json` | Add `WATCH_ALARM_SOUND: 12` |
| `android/.../MessageKeys.kt` | Mirror key 12 |
| `android/.../SettingsModels.kt` | `watchAlarmSound` field + normalize/default/JSON |
| `android/.../res/layout/activity_settings.xml` | Checkbox UI |
| `android/.../res/values/strings.xml` | Label string |
| `android/.../ui/SettingsActivity.kt` | Load/save checkbox |
| `android/.../pebble/PebbleMessenger.kt` | Send key on first settings chunk |
| `src/pkjs/index.js` | Normalize + send on settings push |
| `src/pkjs/config.html` | Emulator checkbox |
| `src/c/alarm.h` / `src/c/alarm.c` | Persist flag + loop sound on alarm |
| `src/c/app_message.c` | Handle inbound `WATCH_ALARM_SOUND` |
| `docs/superpowers/specs/2026-07-30-ez-sos-app-design.md` | Document key + field |
| `VERSION` / `package.json` version | Bump after feature lands (with sibling unreleased work if still unreleased) |

---

### Task 1: Message key + settings model + companion UI

**Files:**
- Modify: `package.json` (`pebble.messageKeys`)
- Modify: `android/app/src/main/java/com/ezsos/companion/MessageKeys.kt`
- Modify: `android/app/src/main/java/com/ezsos/companion/settings/SettingsModels.kt`
- Modify: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/ezsos/companion/ui/SettingsActivity.kt`
- Modify: `android/app/src/main/java/com/ezsos/companion/pebble/PebbleMessenger.kt`
- Modify: `android/app/src/main/java/com/ezsos/companion/pebble/IncomingAppMessages.kt` (describe keys only)

**Interfaces:**
- Consumes: existing `SosSettings` / `pushSettings` patterns
- Produces: `SosSettings.watchAlarmSound: Boolean` (default `true`); `MessageKeys.WATCH_ALARM_SOUND = 12`; first-chunk AppMessage `UInt8`

- [ ] **Step 1: Add message key**

In `package.json` messageKeys add:

```json
"HOLD_MS": 11,
"WATCH_ALARM_SOUND": 12
```

In `MessageKeys.kt` add:

```kotlin
const val HOLD_MS = 11
const val WATCH_ALARM_SOUND = 12
```

- [ ] **Step 2: Extend `SosSettings`**

Add field and JSON round-trip:

```kotlin
data class SosSettings(
    val triggerMode: String = "confirm",
    val holdMs: Int = DEFAULT_HOLD_MS,
    val watchAlarmSound: Boolean = true,
    val messagePrefix: String = DEFAULT_MESSAGE_PREFIX,
    val phoneAlertMode: String = MODE_NOTIFICATION,
    val contacts: List<Contact> = emptyList()
)
```

In `toJsonString()`:

```kotlin
root.put("watchAlarmSound", watchAlarmSound)
```

In `fromJson()`:

```kotlin
val watchAlarmSound = if (obj.has("watchAlarmSound")) obj.optBoolean("watchAlarmSound", true) else true
// pass into SosSettings(...)
```

Missing key ⇒ `true` (default on for upgrades).

- [ ] **Step 3: Settings UI**

`strings.xml`:

```xml
<string name="label_watch_alarm_sound">Watch alarm sound</string>
<string name="watch_alarm_sound_hint">On watches with a speaker, play a repeating tone during Incoming SOS (vibe always stays on).</string>
```

In `activity_settings.xml`, after hold-duration group (before phone alert), add:

```xml
<CheckBox
    android:id="@+id/watchAlarmSound"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/label_watch_alarm_sound"
    android:checked="true" />

<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/watch_alarm_sound_hint"
    android:textSize="12sp"
    android:paddingBottom="12dp" />
```

In `SettingsActivity`:

- `private lateinit var watchAlarmSound: CheckBox`
- bind in `onCreate`
- `loadFromCache`: `watchAlarmSound.isChecked = settings.watchAlarmSound`
- `collectSettings`: pass `watchAlarmSound = watchAlarmSound.isChecked`

- [ ] **Step 4: Push on settings sync**

In `PebbleMessenger.pushSettings`, first chunk (`index == 0`):

```kotlin
map[MessageKeys.WATCH_ALARM_SOUND.toUInt()] =
    PebbleDictionaryItem.UInt8(if (settings.watchAlarmSound) 1u else 0u)
```

In `IncomingAppMessages.describe`, add `WATCH_ALARM_SOUND` to the key list for logs.

- [ ] **Step 5: Compile Android**

Run: `cd /workspaces/EZ_SOS/android && ./gradlew :app:compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`

---

### Task 2: PKJS normalize + emulator config

**Files:**
- Modify: `src/pkjs/index.js`
- Modify: `src/pkjs/config.html`

**Interfaces:**
- Consumes: `watchAlarmSound` boolean from settings objects
- Produces: normalized settings always include `watchAlarmSound`; first chunk sends `WATCH_ALARM_SOUND`

- [ ] **Step 1: Normalize in `index.js`**

In `defaultSettings()`:

```javascript
watchAlarmSound: true,
```

In `normalizeSettings()` return object:

```javascript
watchAlarmSound: raw.watchAlarmSound !== false,
```

(Only explicit `false` disables; missing/`undefined` ⇒ on.)

In `sendSettingsToWatch` first chunk:

```javascript
payload.WATCH_ALARM_SOUND = normalized.watchAlarmSound ? 1 : 0;
```

- [ ] **Step 2: Emulator `config.html`**

After Hold duration section, add:

```html
<h2>Watch alarm</h2>
<div class="card">
  <label class="inline"><input type="checkbox" id="watchAlarmSound" checked> Watch alarm sound</label>
</div>
```

Update `normalizeSettings` / `collectSettings` / load path to read/write `#watchAlarmSound` the same way (`!== false` default on).

- [ ] **Step 3: Sanity check**

Run: `cd /workspaces/EZ_SOS && node -e "require('fs'); console.log('ok')"` is insufficient — prefer `pebble build` in Task 4. For this task, visually confirm the checkbox exists and `normalizeSettings` includes the field by grepping the built bundle after Task 4, or run a tiny node snippet that evals the normalize logic if extracted.

Minimal local check (optional):

```bash
rg "watchAlarmSound|WATCH_ALARM_SOUND" /workspaces/EZ_SOS/src/pkjs
```

Expected: matches in `index.js` and `config.html`.

---

### Task 3: Watch persist + AppMessage + looping speaker in alarm

**Files:**
- Modify: `src/c/alarm.h`
- Modify: `src/c/alarm.c`
- Modify: `src/c/app_message.c`

**Interfaces:**
- Consumes: `MESSAGE_KEY_WATCH_ALARM_SOUND` (generated from `package.json`)
- Produces:
  - `void alarm_set_sound_enabled(bool enabled);`
  - `bool alarm_get_sound_enabled(void);`
  - Persist key `3` for sound enabled (`PERSIST_KEY_WATCH_ALARM_SOUND`) — note triggers already use 1–2; settings blobs use 20+

- [ ] **Step 1: Extend `alarm.h`**

```c
#pragma once

#include <pebble.h>

void alarm_init(void);
void alarm_deinit(void);
void alarm_handle_inbound(void);
void alarm_set_sound_enabled(bool enabled);
bool alarm_get_sound_enabled(void);
void alarm_persist_load(void);
```

- [ ] **Step 2: Implement sound loop in `alarm.c`**

Core behavior:

```c
#define PERSIST_KEY_WATCH_ALARM_SOUND 3
#define SOUND_VOLUME 90

static bool s_sound_enabled = true;

static const SpeakerNote s_alarm_notes[] = {
  { .midi_note = 76, .waveform = SpeakerWaveformSquare, .duration_ms = 180, .velocity = 127, .reserved = 0 },
  { .midi_note = 0,  .waveform = SpeakerWaveformSquare, .duration_ms = 80,  .velocity = 0,   .reserved = 0 },
  { .midi_note = 79, .waveform = SpeakerWaveformSquare, .duration_ms = 180, .velocity = 127, .reserved = 0 },
  { .midi_note = 0,  .waveform = SpeakerWaveformSquare, .duration_ms = 80,  .velocity = 0,   .reserved = 0 },
  { .midi_note = 76, .waveform = SpeakerWaveformSquare, .duration_ms = 220, .velocity = 127, .reserved = 0 },
};

static void try_play_alarm_sound(void) {
  if (!s_alarm_active || !s_sound_enabled) {
    return;
  }
  if (speaker_is_muted()) {
    return;
  }
  speaker_stop();
  speaker_play_notes(s_alarm_notes, ARRAY_LENGTH(s_alarm_notes), SOUND_VOLUME);
}
```

Wire `try_play_alarm_sound()` into the existing vibe timer callback (after `vibes_long_pulse()`), and call it once from `alarm_handle_inbound` after `start_vibe()`.

In `dismiss_alarm` / `alarm_deinit`:

```c
speaker_stop();
```

`alarm_set_sound_enabled`:

```c
void alarm_set_sound_enabled(bool enabled) {
  s_sound_enabled = enabled;
  persist_write_bool(PERSIST_KEY_WATCH_ALARM_SOUND, enabled);
  if (!enabled) {
    speaker_stop();
  }
}

bool alarm_get_sound_enabled(void) {
  return s_sound_enabled;
}

void alarm_persist_load(void) {
  if (persist_exists(PERSIST_KEY_WATCH_ALARM_SOUND)) {
    s_sound_enabled = persist_read_bool(PERSIST_KEY_WATCH_ALARM_SOUND);
  } else {
    s_sound_enabled = true;
  }
}
```

Call `alarm_persist_load()` from `alarm_init()` (or from `ui_init` before `alarm_init` — prefer inside `alarm_init` so one call site).

- [ ] **Step 3: Handle AppMessage in `app_message.c`**

```c
static void handle_watch_alarm_sound_tuple(Tuple *tuple) {
  if (!tuple) {
    return;
  }
  bool enabled = true;
  if (tuple->type == TUPLE_UINT) {
    enabled = tuple->value->uint8 != 0;
  } else if (tuple->type == TUPLE_INT) {
    enabled = tuple->value->int32 != 0;
  } else {
    return;
  }
  alarm_set_sound_enabled(enabled);
}
```

In `inbox_received_callback`, after hold-ms handling:

```c
Tuple *sound_t = dict_find(iterator, MESSAGE_KEY_WATCH_ALARM_SOUND);
if (sound_t) {
  handle_watch_alarm_sound_tuple(sound_t);
}
```

- [ ] **Step 4: Build watchapp**

Run: `cd /workspaces/EZ_SOS && pebble clean && pebble build`

Expected: `'build' finished successfully` and `build/EZ_SOS.pbw` exists.

If `SpeakerNote` / `speaker_*` fail to compile on a platform, verify headers include the Speaker stubs (they should on SDK 4.17 classic targets). Do not add flint/gabbro.

---

### Task 4: Docs + version bump + verify

**Files:**
- Modify: `docs/superpowers/specs/2026-07-30-ez-sos-app-design.md`
- Modify: `docs/superpowers/specs/2026-07-30-watch-alarm-sound-design.md` (status → Implemented)
- Modify: `VERSION` (and sync `package.json` via `scripts/sync-version.sh` if needed)
- Optionally: `README.md` / `android/README.md` one-line mention under inbound alarm

**Interfaces:** none new

- [ ] **Step 1: Update app design spec**

- Settings JSON: add `"watchAlarmSound": true`
- messageKeys table: add `| \`WATCH_ALARM_SOUND\` | 12 | Phone → watch | Inbound alarm speaker on/off |`
- Watch UI inbound line: mention optional repeating speaker tone when enabled

- [ ] **Step 2: Version**

If `1.0.12` is still the unreleased bucket containing hold-ring + inbound retry + this feature, keep or bump per repo practice. If `1.0.12` already released, set `VERSION` to next patch (`1.0.13`), run:

```bash
/workspaces/EZ_SOS/scripts/sync-version.sh
```

- [ ] **Step 3: Final verification**

```bash
cd /workspaces/EZ_SOS && pebble build
cd /workspaces/EZ_SOS/android && ./gradlew :app:compileDebugKotlin
rg "WATCH_ALARM_SOUND|watchAlarmSound" /workspaces/EZ_SOS --glob '!build/**' --glob '!.git/**'
```

Expected: PBW builds; Kotlin compiles; key `12` appears in `package.json`, `MessageKeys.kt`, watch handler, and messenger push.

- [ ] **Step 4: Manual test checklist (device)**

1. Fresh settings → checkbox on
2. Test watch alarm with sound on → vibe (+ tone on speaker watches)
3. Sound off → vibe only
4. Dismiss → tone stops
5. Quiet Time / mute (if available) → no tone override

---

## Spec coverage (self-review)

| Spec requirement | Task |
|------------------|------|
| Boolean `watchAlarmSound` default true | Task 1 |
| Companion checkbox UI | Task 1 |
| JSON + `WATCH_ALARM_SOUND` 12 sync | Tasks 1–2 |
| Watch persist + apply | Task 3 |
| Loop until dismiss via speaker notes | Task 3 |
| `speaker_is_muted` respect | Task 3 |
| Vibe always on | Task 3 (unchanged path) |
| Classic no-op safe | Task 3 |
| Emulator config | Task 2 |
| No flint/gabbro / no phone sound changes | Global constraints |
| Docs / test | Task 4 |
