# Inbound SOS auth token (AES-256-GCM timestamp)

**Date:** 2026-07-31  
**Status:** Implemented

## Goal

Stop casual spoofing of inbound SOS (any SMS that merely starts with `EZ SOS:` from an enabled contact). Outbound SMS appends an encrypted timestamp token; inbound requires a valid, fresh token before alarming.

This is a shared compile-time secret baked into the APK. Anyone who extracts the key can still forge tokens. Scope is **dumb easy spoofing**, not strong cryptography against a determined attacker.

## Prefix

- Required start of every SOS SMS: exact **`EZ SOS:`** (**case-sensitive**).
- Inbound rejects bodies that do not start with that string (after trim).
- Outbound continues to normalize stored prefix to `EZ SOS: ` + body.

## Message format

Outbound body (unchanged content) plus a **final line**:

```
EZ SOS: I need help.
Lat: <lat>, Lon: <lon>
https://maps.google.com/?q=<lat>,<lon>
ez.<Base64URL(IV || ciphertext || tag)>
```

| Piece | Spec |
|-------|------|
| Plaintext | UTC epoch **seconds** as decimal ASCII (e.g. `1735689600`) |
| Cipher | AES-256-GCM, 12-byte random IV, 128-bit auth tag |
| Encoding | Base64URL (no padding preferred; accept padded on decode) |
| Line marker | Literal prefix `ez.` then the token |

## Inbound validation (all required)

1. Sender matches an **enabled** contact (`PhoneNormalizer`).
2. Body starts with `EZ SOS:` (case-sensitive).
3. Last non-empty line matches `ez.<token>`; missing token → **reject**.
4. Decrypt with compile-time key; failure → **reject**.
5. `|nowSeconds − ts| ≤ authWindowMinutes * 60` → else **reject**.

On reject: no phone/watch alarm; `EventLog` records the reason (format / decrypt / window / contact).

## Setting

| Field | Type | Default | UI |
|-------|------|---------|-----|
| `authWindowMinutes` | int | `15` | Companion radio: **5 / 15 / 30 / 60** minutes. Label: accept SOS tokens within…. Hint: receiving side only. |

- Persist in companion settings JSON.
- Snap unknown values to nearest preset (same pattern as `holdMs`).
- **No** watch AppMessage key — watch never validates SMS.

## Key injection

| Source | Behavior |
|--------|----------|
| Env `EZ_SOS_AUTH_KEY` | 64 hex chars (32 bytes). Injected as `BuildConfig.EZ_SOS_AUTH_KEY_HEX`. |
| Missing (local/debug) | Fall back to a **fixed well-known debug key** (documented in README / android README). |
| CI release | GitHub Actions secret `EZ_SOS_AUTH_KEY` required; pass into `assembleRelease`. Fail the release Android job if unset. |

- Key is never committed to the repo.
- Official release APKs use the secret; local builds without the env var use the debug key (interop only among debug-key builds).

## Components

| Unit | Responsibility |
|------|----------------|
| `SosAuthToken` (new) | Encrypt/decrypt timestamp; parse `ez.` line; window check |
| `SosHandler.buildMessageBody` | Append token line after maps URL |
| `SmsReceiver` | Case-sensitive prefix + token validation before alert |
| `SosSettings` | `authWindowMinutes` + case-sensitive `isInboundSosBody` |
| Settings UI | Radio group for window presets |
| `app/build.gradle.kts` | `buildConfigField` from env / debug fallback; enable `buildConfig` |
| `.github/workflows/release.yml` | Require + pass `EZ_SOS_AUTH_KEY` |

## Out of scope

- Per-user / per-pair keys
- Asymmetric crypto / key rotation UX
- Accepting legacy messages without a token
- iOS
- Watch-side crypto
