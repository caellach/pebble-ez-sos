# EZ SOS — Android companion

Kotlin companion app that pairs with the EZ SOS Pebble watchapp for:

- **Safety contacts, message prefix, and trigger mode** (source of truth — edit in this app)
- **Silent outbound SMS** (`SmsManager`) when the watch fires `SOS_REQUEST`
- **Incoming SOS detection** (`SMS_RECEIVED` from an enabled contact whose body starts with case-sensitive `EZ SOS:` **and** includes a valid trailing `ez.` auth token within `authWindowMinutes`) → watch `INBOUND_ALERT` + configurable phone alert (off / notification / full-screen); optional watch speaker tone via `watchAlarmSound`
- Test buttons for watch and phone alarms on the main screen
- Overflow menu: event log, grant permissions

Mutual coverage: both parties install the companion so either can send SOS and either can receive an urgent alarm.
- **`COMPANION_PRESENT`** announcement so PebbleKit JS skips parallel `sms:` fallback
- Deep link: `ezsos://settings`

Watch UUID (must match root `package.json`): `cf79bb9f-ab43-4848-81b6-d1c1ae6a9226`

**iOS is unsupported.** Apple does not allow third-party apps to read SMS or reliably send silent SMS, so inbound SMS→watch alarm cannot work on iPhone.

## Requirements

- Android Studio Hedgehog+ **or** Android SDK command-line tools
- JDK 17+
- `compileSdk` 36 / `targetSdk` 35, `minSdk` 26
- A phone with the **Pebble / Core / Rebble-compatible** phone app installed and paired
- Android SDK platform 36 and Build-Tools installed

Environment variables (typical):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or your SDK path
export PATH="$PATH:$ANDROID_HOME/platform-tools"
```

## PebbleKit dependency

**Inbound (Core Devices):** Core only emits classic `com.getpebble.action.app.RECEIVE` broadcasts when the watchapp PBW does **not** declare `companionApp`. With `companionApp` set, Core uses Kit2 bind exclusively and never sends those broadcasts. EZ SOS therefore omits `companionApp` and follows the [PebbleKit Android docs](https://developer.rebble.io/guides/communication/using-pebblekit-android/): `PebbleKit.registerReceivedDataHandler` + ACK via `sendAckToPebble`. The receiver is also in the manifest for cold-start.

**Outbound:** Still prefers PebbleKit 2 `DefaultPebbleSender`, with classic `sendDataToPebble` fallback.

As a backup, PKJS also opens `ezsos://sos` when the companion has announced itself.

**Fallback:** This project also vendors classic PebbleKit (sources from [pebble/pebble-android-sdk](https://github.com/pebble/pebble-android-sdk)) as a local Gradle module for older phone apps:

```
android/libs/pebblekit/
```

## Build / sideload APK

Debug (local development):

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open `android/` in Android Studio → Run.

CI release builds produce a **signed** APK (`assembleRelease`) attached to the GitHub Release. The Pebble Dev Container does **not** include the Android SDK by default — build the companion on a host (or CI) that has it.

## Release signing (GitHub secrets)

Release APKs are signed with a PKCS12 keystore stored only in GitHub Actions secrets (never committed):

| Secret | Purpose |
|--------|---------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `.keystore` / `.p12` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias (default `ezsos`) |
| `ANDROID_KEY_PASSWORD` | Key password |
| `EZ_SOS_AUTH_KEY` | 64 hex chars (32 bytes) for inbound SOS auth tokens — required for release CI |

Generate once and upload:

```bash
./scripts/create-android-release-keystore.sh --upload
```

That writes files under `android/.signing/` (gitignored) and sets the four signing secrets via `gh`. Re-uploading a **new** keystore changes the signing identity — keep a secure backup of the first keystore if you need update-compatible installs. Also set `EZ_SOS_AUTH_KEY` as a repo secret before running the release workflow (the Android job fails if it is missing).

Local signed release (same env vars CI uses):

```bash
export ANDROID_KEYSTORE_PATH=android/.signing/ezsos-release.keystore
# plus ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD from .signing/github-secrets.env
export EZ_SOS_AUTH_KEY=<64-hex-chars>   # production key; omit only for debug-key interop testing
(cd android && ./gradlew assembleRelease)
```

## Inbound SOS auth token

Outbound SOS SMS append a final line `ez.<Base64URL…>` (AES-256-GCM encrypted UTC timestamp). Inbound requires:

1. Enabled contact sender
2. Body starts with case-sensitive `EZ SOS:`
3. Valid trailing `ez.` token decryptable with the compile-time key
4. Timestamp within `authWindowMinutes` (default **15**; presets **5 / 15 / 30 / 60** in companion settings)

This is a **shared secret baked into the APK** — it stops casual spoofing of `EZ SOS:` SMS, not strong anti-forgery against someone who extracts the key.

| Build | Key source |
|-------|------------|
| CI / local release | Env `EZ_SOS_AUTH_KEY` = 64 hex chars → `BuildConfig.EZ_SOS_AUTH_KEY_HEX` |
| Local/debug without env | Fixed debug key: `e25a50de00000000000000000000000000000000000000000000000000000001` |

Debug-key builds only interoperate with other debug-key builds. Release APKs must use the production secret.

## First-run permissions

1. Launch **EZ SOS** on the phone.
2. Read the first-run screen (SMS + contacts + location; iOS unsupported).
3. Grant **SMS**, **contacts**, and **location**.
4. On Android 13+, allow **notifications** (fallback if the watch cannot be alerted).

## End-to-end setup

1. Build the watchapp in the repo root (`pebble build`) → `build/EZ_SOS.pbw`.
2. Install the `.pbw` on the watch via the Pebble/Core phone app.
3. Sideload this companion APK; complete first-run permissions.
4. In the companion, tap **Edit contacts & settings**: add/enable contacts, set prefix + trigger mode + **accept SOS tokens within…** (`authWindowMinutes`), **Save & sync to watch**.
5. (Optional) Opening EZ SOS “settings” in the Pebble app on Android shows links that open the companion (`intent://` / `ezsos://settings`) or the matching GitHub release APK (`vX.Y.Z`, with a Latest fallback).
6. Trigger SOS on the watch → companion sends silent SMS (body ends with `ez.` auth token); watch shows **Sent** (or **Check phone** / **No GPS** / **No contacts**). PKJS does **not** open parallel `sms:` when the companion has announced presence.
7. SMS from an enabled contact with body starting `EZ SOS:` **and** a valid fresh `ez.` token → phone + watch **Incoming SOS** alarm.

## Manual test checklist

1. Configure 1+ enabled contacts **in the companion** (peers who also use EZ SOS); set `authWindowMinutes` if desired; save/sync.
2. Trigger SOS → silent **Sent**, or **Check phone** / **No GPS** / **No contacts**. Confirm outbound SMS has a final `ez.` line.
3. SMS from that number starting with `EZ SOS:` **and** a valid fresh token (same auth key) → **Incoming SOS** alarm.
4. SMS from that number without the prefix, **without a token**, with a **wrong-key** token, outside the auth window, or from an unknown number → no alarm.
5. Uninstall companion (or clear presence) → Pebble settings / SOS should prompt to install the companion.

## Security: AppMessage sender allowlist

`EzSosPebbleReceiver` only handles `com.getpebble.action.app.RECEIVE` (including `SOS_REQUEST`) when the broadcast comes from an allowlisted Pebble phone app:

- `coredevices.coreapp` (current Core Devices / Pebble app)
- `com.getpebble.android.basalt` (classic Pebble)
- `com.getpebble.android` (older classic)
- `io.rebble.cobble` (Rebble Cobble)

Other apps forging that intent are ignored. This authenticates the **phone app**, not a cryptographic watch token (`COMPANION_PRESENT` is unrelated — it only tells PKJS the companion is installed).

## AppMessage keys (locked)

| Key | Id |
|-----|----|
| SOS_REQUEST | 1 |
| STATUS | 2 |
| TRIGGER_MODE | 3 |
| SETTINGS_JSON | 4 |
| SETTINGS_REQUEST | 5 |
| INBOUND_ALERT | 6 |
| SETTINGS_CHUNK_INDEX | 7 |
| SETTINGS_CHUNK_DATA | 8 |
| SETTINGS_CHUNK_COUNT | 9 |
| COMPANION_PRESENT | 10 |
