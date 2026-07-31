# Inbound SOS Auth Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Append an AES-256-GCM encrypted UTC timestamp token to outbound SOS SMS and require a valid fresh token on inbound before alarming.

**Architecture:** Compile-time 32-byte key (`BuildConfig.EZ_SOS_AUTH_KEY_HEX`) encrypts epoch seconds on send; `SmsReceiver` decrypts and checks `|now − ts| ≤ authWindowMinutes`. Companion-only setting for the window; no watch AppMessage changes.

**Tech Stack:** Kotlin Android companion, AES-256-GCM (`javax.crypto`), Base64URL, Gradle `buildConfigField`, GitHub Actions secret `EZ_SOS_AUTH_KEY`, JUnit4 JVM unit tests.

## Global Constraints

- Prefix: exact case-sensitive `EZ SOS:` (inbound after trim)
- Token line: final line `ez.<Base64URL(IV || ciphertext || tag)>`
- Plaintext: UTC epoch **seconds** as decimal ASCII
- Cipher: AES-256-GCM, 12-byte IV, 128-bit tag
- `authWindowMinutes` default `15`; presets `5 / 15 / 30 / 60`
- Missing token → reject (no legacy accept)
- Env `EZ_SOS_AUTH_KEY` = 64 hex chars; else well-known debug key
- CI release requires secret `EZ_SOS_AUTH_KEY`
- No watch crypto / no new AppMessage keys
- Commit only when the user explicitly asks (do not auto-commit per task unless requested)
- Spec: `docs/superpowers/specs/2026-07-31-inbound-sos-auth-token-design.md`

---

## File map

| File | Responsibility |
|------|----------------|
| `android/app/build.gradle.kts` | Enable BuildConfig; inject key hex; junit test dep |
| `android/.../sos/SosAuthToken.kt` | Encrypt / decrypt / parse / window check |
| `android/.../test/.../SosAuthTokenTest.kt` | JVM unit tests for crypto + parsing |
| `android/.../settings/SettingsModels.kt` | `authWindowMinutes`; case-sensitive `isInboundSosBody` |
| `android/.../res/layout/activity_settings.xml` | Auth window radio group |
| `android/.../res/values/strings.xml` | Labels / hints / radio text |
| `android/.../ui/SettingsActivity.kt` | Load/save auth window |
| `android/.../sos/SosHandler.kt` | Append `ez.` token to outbound body |
| `android/.../sms/SmsReceiver.kt` | Validate token before alert |
| `.github/workflows/release.yml` | Require + pass `EZ_SOS_AUTH_KEY` |
| `README.md` / `android/README.md` | Document token + secret + debug key |
| `VERSION` / `package.json` | Patch bump when releasing (ask user) |

**Well-known debug key (64 hex):**  
`e25a50de00000000000000000000000000000000000000000000000000000001`  
(Document in READMEs; local builds without env use this.)

---

### Task 1: BuildConfig key + SosAuthToken + unit tests

**Files:**
- Modify: `android/app/build.gradle.kts`
- Create: `android/app/src/main/java/com/ezsos/companion/sos/SosAuthToken.kt`
- Create: `android/app/src/test/java/com/ezsos/companion/sos/SosAuthTokenTest.kt`

**Interfaces:**
- Consumes: `BuildConfig.EZ_SOS_AUTH_KEY_HEX` (String, 64 hex)
- Produces:
  - `SosAuthToken.LINE_PREFIX = "ez."`
  - `SosAuthToken.buildLine(nowEpochSeconds: Long = …): String`
  - `SosAuthToken.extractTokenFromBody(body: String): String?`
  - `SosAuthToken.decryptEpochSeconds(tokenBase64Url: String): Long?`
  - `SosAuthToken.isFresh(epochSeconds: Long, windowMinutes: Int, nowEpochSeconds: Long = …): Boolean`
  - `SosAuthToken.validateBody(body: String, windowMinutes: Int, nowEpochSeconds: Long = …): Boolean`

- [ ] **Step 1: Wire BuildConfig + test dependency**

In `android/app/build.gradle.kts`, after `projectVersionCode`:

```kotlin
val debugAuthKeyHex = "e25a50de00000000000000000000000000000000000000000000000000000001"
val authKeyHex = System.getenv("EZ_SOS_AUTH_KEY")?.trim().orEmpty().ifBlank { debugAuthKeyHex }
require(authKeyHex.matches(Regex("^[0-9a-fA-F]{64}$"))) {
    "EZ_SOS_AUTH_KEY must be 64 hex chars (32 bytes); got length ${authKeyHex.length}"
}
```

Inside `android { defaultConfig { ... } }` add:

```kotlin
buildConfigField("String", "EZ_SOS_AUTH_KEY_HEX", "\"$authKeyHex\"")
```

Inside `android { }` add:

```kotlin
buildFeatures {
    buildConfig = true
}
```

In `dependencies { }` add:

```kotlin
testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write failing unit tests**

Create `android/app/src/test/java/com/ezsos/companion/sos/SosAuthTokenTest.kt`:

```kotlin
package com.ezsos.companion.sos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosAuthTokenTest {
    @Test
    fun roundTrip_encryptDecrypt() {
        val now = 1_735_689_600L
        val line = SosAuthToken.buildLine(now)
        assertTrue(line.startsWith("ez."))
        val token = line.removePrefix("ez.")
        assertEquals(now, SosAuthToken.decryptEpochSeconds(token))
    }

    @Test
    fun extractToken_lastNonEmptyLine() {
        val body = "EZ SOS: help\nLat: 1, Lon: 2\nhttps://maps.google.com/?q=1,2\nez.abc123\n"
        assertEquals("abc123", SosAuthToken.extractTokenFromBody(body))
    }

    @Test
    fun extractToken_missing_returnsNull() {
        assertNull(SosAuthToken.extractTokenFromBody("EZ SOS: help\nLat: 1"))
    }

    @Test
    fun isFresh_insideWindow() {
        assertTrue(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 + 14 * 60))
        assertTrue(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 - 14 * 60))
    }

    @Test
    fun isFresh_outsideWindow() {
        assertFalse(SosAuthToken.isFresh(1000, windowMinutes = 15, nowEpochSeconds = 1000 + 16 * 60))
    }

    @Test
    fun validateBody_happyPath() {
        val now = 1_700_000_000L
        val body = "EZ SOS: I need help.\nLat: 0.0, Lon: 0.0\nhttps://maps.google.com/?q=0.0,0.0\n" +
            SosAuthToken.buildLine(now)
        assertTrue(SosAuthToken.validateBody(body, windowMinutes = 15, nowEpochSeconds = now))
    }

    @Test
    fun validateBody_badToken_false() {
        val body = "EZ SOS: x\nez.not-valid-base64!!!"
        assertFalse(SosAuthToken.validateBody(body, windowMinutes = 15, nowEpochSeconds = 1L))
    }

    @Test
    fun decrypt_garbage_returnsNull() {
        assertNull(SosAuthToken.decryptEpochSeconds("not-a-token"))
    }
}
```

- [ ] **Step 3: Run tests — expect FAIL (class missing)**

Run:

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:testDebugUnitTest --tests com.ezsos.companion.sos.SosAuthTokenTest
```

Expected: compile/test failure because `SosAuthToken` does not exist.

- [ ] **Step 4: Implement `SosAuthToken`**

Create `android/app/src/main/java/com/ezsos/companion/sos/SosAuthToken.kt`:

```kotlin
package com.ezsos.companion.sos

import android.util.Base64
import com.ezsos.companion.BuildConfig
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Shared-secret AES-256-GCM timestamp token appended to outbound SOS SMS.
 * Stops casual spoofing; key is extractable from the APK.
 */
object SosAuthToken {
    const val LINE_PREFIX = "ez."
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun buildLine(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): String {
        val key = keyBytes()
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val plaintext = nowEpochSeconds.toString().toByteArray(Charsets.UTF_8)
        val cipherAndTag = cipher.doFinal(plaintext)
        val packed = ByteBuffer.allocate(iv.size + cipherAndTag.size)
            .put(iv)
            .put(cipherAndTag)
            .array()
        val b64 = Base64.encodeToString(packed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return LINE_PREFIX + b64
    }

    fun extractTokenFromBody(body: String): String? {
        val last = body.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return null
        if (!last.startsWith(LINE_PREFIX)) return null
        val token = last.removePrefix(LINE_PREFIX)
        return token.takeIf { it.isNotEmpty() }
    }

    fun decryptEpochSeconds(tokenBase64Url: String): Long? {
        return try {
            val packed = Base64.decode(tokenBase64Url, Base64.URL_SAFE)
            if (packed.size <= IV_LEN) return null
            val iv = packed.copyOfRange(0, IV_LEN)
            val cipherAndTag = packed.copyOfRange(IV_LEN, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes(), "AES"),
                GCMParameterSpec(TAG_BITS, iv)
            )
            val plain = cipher.doFinal(cipherAndTag).toString(Charsets.UTF_8)
            plain.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isFresh(
        epochSeconds: Long,
        windowMinutes: Int,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val windowSec = windowMinutes.toLong().coerceAtLeast(0) * 60L
        return abs(nowEpochSeconds - epochSeconds) <= windowSec
    }

    fun validateBody(
        body: String,
        windowMinutes: Int,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        val token = extractTokenFromBody(body) ?: return false
        val ts = decryptEpochSeconds(token) ?: return false
        return isFresh(ts, windowMinutes, nowEpochSeconds)
    }

    private fun keyBytes(): ByteArray {
        val hex = BuildConfig.EZ_SOS_AUTH_KEY_HEX
        require(hex.length == 64)
        return ByteArray(32) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
```

Note: JVM unit tests run against the Android stub `android.util.Base64` via the android unit-test classpath; if Base64 fails in pure JVM, switch encode/decode to `java.util.Base64.getUrlEncoder().withoutPadding()` / `getUrlDecoder()` inside `SosAuthToken` (preferred for testability — use `java.util.Base64` instead of `android.util.Base64`).

**Prefer `java.util.Base64` in the implementation** so unit tests need no Robolectric:

```kotlin
import java.util.Base64
// encode: Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
// decode: Base64.getUrlDecoder().decode(tokenBase64Url)
```

- [ ] **Step 5: Run tests — expect PASS**

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:testDebugUnitTest --tests com.ezsos.companion.sos.SosAuthTokenTest
```

Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Stop — do not commit unless user asked**

---

### Task 2: Settings model + case-sensitive prefix

**Files:**
- Modify: `android/app/src/main/java/com/ezsos/companion/settings/SettingsModels.kt`

**Interfaces:**
- Consumes: none new
- Produces: `SosSettings.authWindowMinutes: Int` (default 15); `normalizeAuthWindowMinutes`; case-sensitive `isInboundSosBody`

- [ ] **Step 1: Extend `SosSettings`**

Add field:

```kotlin
val authWindowMinutes: Int = DEFAULT_AUTH_WINDOW_MINUTES,
```

In `toJsonString()`:

```kotlin
root.put("authWindowMinutes", normalizeAuthWindowMinutes(authWindowMinutes))
```

In companion:

```kotlin
const val DEFAULT_AUTH_WINDOW_MINUTES = 15
val AUTH_WINDOW_PRESETS = listOf(5, 15, 30, 60)

fun normalizeAuthWindowMinutes(raw: Int?): Int {
    val value = raw ?: DEFAULT_AUTH_WINDOW_MINUTES
    if (value in AUTH_WINDOW_PRESETS) return value
    return AUTH_WINDOW_PRESETS.minByOrNull { kotlin.math.abs(it - value) }
        ?: DEFAULT_AUTH_WINDOW_MINUTES
}
```

In `fromJson`, parse:

```kotlin
val authWindowMinutes = normalizeAuthWindowMinutes(
    if (obj.has("authWindowMinutes")) obj.optInt("authWindowMinutes", DEFAULT_AUTH_WINDOW_MINUTES)
    else DEFAULT_AUTH_WINDOW_MINUTES
)
```

Pass into `SosSettings(...)` constructor.

Make `isInboundSosBody` case-sensitive:

```kotlin
fun isInboundSosBody(raw: String?): Boolean {
    val trimmed = raw?.trim().orEmpty()
    return trimmed.startsWith("EZ SOS:")
}
```

Keep `normalizeMessagePrefix` inbound strip case-insensitive for migration of stored odd casing if desired; outbound always emits `REQUIRED_PREFIX` (`EZ SOS: `).

- [ ] **Step 2: Compile**

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:compileDebugKotlin
```

Expected: SUCCESS (fix any call sites that construct `SosSettings` — defaults cover most; update named args if needed).

- [ ] **Step 3: Stop — no commit unless asked**

---

### Task 3: Settings UI for auth window

**Files:**
- Modify: `android/app/src/main/res/layout/activity_settings.xml`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/ezsos/companion/ui/SettingsActivity.kt`

**Interfaces:**
- Consumes: `SosSettings.authWindowMinutes` / `normalizeAuthWindowMinutes`
- Produces: UI load/save for presets 5/15/30/60

- [ ] **Step 1: Strings**

Add to `strings.xml`:

```xml
<string name="label_auth_window">Accept SOS tokens within</string>
<string name="auth_window_hint">Receiving only. Tighter reduces replay risk; looser tolerates clock skew and SMS delay.</string>
<string name="auth_window_5m">5 minutes</string>
<string name="auth_window_15m">15 minutes</string>
<string name="auth_window_30m">30 minutes</string>
<string name="auth_window_60m">60 minutes</string>
```

- [ ] **Step 2: Layout**

After the phone-alert `RadioGroup` (before message-prefix section), insert:

```xml
<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/label_auth_window"
    android:textStyle="bold" />

<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="@string/auth_window_hint"
    android:textSize="12sp"
    android:paddingBottom="4dp" />

<RadioGroup
    android:id="@+id/authWindowGroup"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingBottom="12dp">

    <RadioButton
        android:id="@+id/authWindow5m"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/auth_window_5m" />

    <RadioButton
        android:id="@+id/authWindow15m"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/auth_window_15m" />

    <RadioButton
        android:id="@+id/authWindow30m"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/auth_window_30m" />

    <RadioButton
        android:id="@+id/authWindow60m"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="@string/auth_window_60m" />
</RadioGroup>
```

- [ ] **Step 3: Wire `SettingsActivity`**

Add:

```kotlin
private lateinit var authWindowGroup: RadioGroup
```

In `onCreate` after `phoneAlertGroup`:

```kotlin
authWindowGroup = findViewById(R.id.authWindowGroup)
```

In `loadFromCache`:

```kotlin
when (SosSettings.normalizeAuthWindowMinutes(settings.authWindowMinutes)) {
    5 -> findViewById<RadioButton>(R.id.authWindow5m).isChecked = true
    30 -> findViewById<RadioButton>(R.id.authWindow30m).isChecked = true
    60 -> findViewById<RadioButton>(R.id.authWindow60m).isChecked = true
    else -> findViewById<RadioButton>(R.id.authWindow15m).isChecked = true
}
```

In `collectSettings`:

```kotlin
val authWindowMinutes = when (authWindowGroup.checkedRadioButtonId) {
    R.id.authWindow5m -> 5
    R.id.authWindow30m -> 30
    R.id.authWindow60m -> 60
    else -> 15
}
```

Pass `authWindowMinutes = authWindowMinutes` into `SosSettings(...)`.

- [ ] **Step 4: Compile**

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:compileDebugKotlin
```

Expected: SUCCESS.

- [ ] **Step 5: Stop — no commit unless asked**

---

### Task 4: Outbound append + inbound validate

**Files:**
- Modify: `android/app/src/main/java/com/ezsos/companion/sos/SosHandler.kt`
- Modify: `android/app/src/main/java/com/ezsos/companion/sms/SmsReceiver.kt`

**Interfaces:**
- Consumes: `SosAuthToken.buildLine()`, `SosAuthToken.validateBody()`, `settings.authWindowMinutes`
- Produces: SMS body with final `ez.` line; inbound reject without valid fresh token

- [ ] **Step 1: Append token in `SosHandler.buildMessageBody`**

Replace:

```kotlin
fun buildMessageBody(prefix: String, lat: Double, lon: Double): String {
    val latStr = lat.toString()
    val lonStr = lon.toString()
    return "$prefix\nLat: $latStr, Lon: $lonStr\nhttps://maps.google.com/?q=$latStr,$lonStr"
}
```

With:

```kotlin
fun buildMessageBody(prefix: String, lat: Double, lon: Double): String {
    val latStr = lat.toString()
    val lonStr = lon.toString()
    val tokenLine = SosAuthToken.buildLine()
    return "$prefix\nLat: $latStr, Lon: $lonStr\nhttps://maps.google.com/?q=$latStr,$lonStr\n$tokenLine"
}
```

- [ ] **Step 2: Validate in `SmsReceiver`**

After prefix check and before/after contact match (order: prefix → token → contact is fine; contact-after-token also fine). Required sequence matching spec intent:

```kotlin
val body = messages.joinToString("") { it.messageBody.orEmpty() }
if (!SosSettings.isInboundSosBody(body)) {
    EventLog.i(TAG, "SMS ignored (body missing EZ SOS: prefix)")
    return
}
val window = SosSettings.normalizeAuthWindowMinutes(settings.authWindowMinutes)
if (!SosAuthToken.validateBody(body, windowMinutes = window)) {
    EventLog.i(TAG, "SMS ignored (auth token missing/invalid/expired)")
    return
}
// then sender / enabled-contact match as today
```

Import `com.ezsos.companion.sos.SosAuthToken`.

Update class KDoc to mention required `ez.` token.

- [ ] **Step 3: Compile + unit tests**

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests com.ezsos.companion.sos.SosAuthTokenTest
```

Expected: SUCCESS.

- [ ] **Step 4: Stop — no commit unless asked**

---

### Task 5: CI secret + docs + version

**Files:**
- Modify: `.github/workflows/release.yml`
- Modify: `README.md`
- Modify: `android/README.md`
- Modify: `docs/superpowers/specs/2026-07-31-inbound-sos-auth-token-design.md` (status → Implemented)
- Optionally: `VERSION` / `package.json` / `scripts/sync-version.sh` — **only if user wants a release bump** (suggest `1.1.1`)

**Interfaces:**
- Consumes: GitHub secret `EZ_SOS_AUTH_KEY`
- Produces: Release APK built with production key; docs match behavior

- [ ] **Step 1: Release workflow**

In the Android job “Require Android signing secrets” step, also require `EZ_SOS_AUTH_KEY` (add to `env:` and the missing-secret loop).

In “Build signed release APK” step `env:`, add:

```yaml
EZ_SOS_AUTH_KEY: ${{ secrets.EZ_SOS_AUTH_KEY }}
```

- [ ] **Step 2: Docs**

Root `README.md` / `android/README.md`:

- Incoming SOS requires body starting with `EZ SOS:` **and** a valid trailing `ez.` auth token.
- Document `authWindowMinutes` (default 15).
- Document `EZ_SOS_AUTH_KEY` (64 hex) for CI/local release; debug fallback key hex for local builds without the env var.
- Note: shared secret in APK — not strong anti-forgery.

Update inbound test checklist: SMS without token / wrong key / expired window → no alarm.

- [ ] **Step 3: Spec status**

Set design doc status to `Implemented`.

- [ ] **Step 4: Full Android verify**

```bash
cd /workspaces/EZ_SOS/android && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: SUCCESS.

- [ ] **Step 5: Ask user before commit / version bump / push**

Do not bump `VERSION` or commit unless the user requests it. Suggest patch `1.1.1` when ready to release (remind them to set GitHub secret `EZ_SOS_AUTH_KEY` before the release workflow).

---

## Spec coverage checklist

| Spec item | Task |
|-----------|------|
| Case-sensitive `EZ SOS:` | Task 2 |
| AES-256-GCM timestamp + `ez.` line | Task 1, 4 |
| Reject missing token | Task 4 |
| `authWindowMinutes` default 15, presets | Task 2, 3 |
| BuildConfig / env / debug key | Task 1 |
| CI require secret | Task 5 |
| Docs | Task 5 |
| No watch AppMessage | (none) |
