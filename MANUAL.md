# Call Ignore — Technical Manual

Version 1.3 (versionCode 4) · package `io.github.rowsail.callignore` · Android 10 (API 29) and later, built against API 36

Call Ignore screens incoming phone calls against a user-editable list of number patterns. For each match it can let the call ring, ignore it silently, send it to voicemail, or answer it and hang up immediately. It is free software (GPL-3.0-or-later), built with Gradle, and it has no network access. Source: https://github.com/rowsail/CallIgnore

---

## 1. How it works

Android lets one app hold the **Call screening** role (`android.app.role.CALL_SCREENING`). Before an incoming call rings, Telecom binds that app's `CallScreeningService` and waits for a `CallResponse`. Call Ignore implements that service in `IgnoreService`.

```
incoming call ──► Telecom ──► IgnoreService.onScreenCall()
                                 │
                                 ├─ not incoming, or screening disabled ─► allow
                                 ├─ number hidden/withheld ─► "hidden" setting
                                 └─ Rules.match(number) ─► first matching rule
                                                             (no match ─► allow)
```

The app runs nothing between calls. It has no background service, alarm, wake lock, receiver or network access. Android starts the process only to screen a call.

### Platform limits

These come from Android and the carrier, and the app can't change them:

| Limit | Effect |
|---|---|
| **Contacts are never screened** | Unless the screening app is also the default phone app, Android binds the service only for calls from numbers *not* in the contacts. |
| **Blocked calls still appear in the call log** | Android logs blocked calls as `BLOCKED_TYPE` whatever `setSkipCallLog` says. |
| **No-answer forwarding is the carrier's** | An *ignored* call keeps ringing on the network side. If the line has no-answer call forwarding (normally to voicemail), the carrier forwards it when the ring timer runs out. The phone has no per-caller control over this. |
| **One screening app at a time** | Making Call Ignore the screening app removes the role from whichever app held it before. |

---

## 2. Actions

Each rule, and the hidden-number setting, selects one action:

| Keyword | UI label | `CallResponse` | What the caller experiences | What the phone does |
|---|---|---|---|---|
| `allow` | Ring normally | default (allowed) | Normal call | Rings normally |
| `ignore` | Ignore silently | `disallow=true`, `reject=false`, `skipCallLog=true`, `skipNotification=true` | Ringback until they hang up, or until no-answer forwarding sends them to voicemail | Nothing: no ring, no screen, no notification. Call log shows "Blocked". |
| `voicemail` | Send to voicemail | `disallow=true`, `reject=true`, plus skip flags | Goes straight to voicemail, or hears busy if the line has none | Nothing; call log shows "Blocked" |
| `answer` | Answer & hang up | `silence=true` (allowed, but silent), then answer and end | Pickup, then immediate disconnect; never reaches voicemail | The call screen may appear for a moment; call log shows an answered call |

`ignore` is the default when a rule names no action.

### Answer & hang up in detail

After allowing the call silently, `answerThenHangUp()` checks `TelephonyManager.getCallState()` every **50 ms** on the main looper:

1. **`CALL_STATE_RINGING`:** calls `TelecomManager.acceptRingingCall()` once.
2. **`CALL_STATE_OFFHOOK`** (connected) after answering: calls `TelecomManager.endCall()` and stops.
3. **`CALL_STATE_IDLE`** after answering: the caller hung up first; stops without doing anything.
4. **Giving up:** after **5 s** with no result, it stops and logs `gave up: call never rang|connected`.

The app hangs up only once the call is *off-hook*, because `endCall()` on a call that is still ringing **rejects** it. A rejected call goes to voicemail, the opposite of what this action is for.

`getCallState()` gives the device-wide state, so it also covers calls arriving on the second SIM. `getCallStateForSubscription()` would cover only the default SIM.

**Fallback:** if `ANSWER_PHONE_CALLS` or `READ_PHONE_STATE` is missing, `answer` rules **ignore** the call instead. The log entry then says `Ignore silently (answer permission missing)`.

**Caution:** answering tells automated diallers that the number is live, which can increase spam. Use `answer` only for callers where avoiding voicemail matters more than that.

---

## 3. Rule syntax

The rules are plain text, edited on the main screen.

```
<regex>    [action]   # optional comment
=<pattern> [action]   # optional comment
```

A rule is either a **regular expression** (any line) or, with a leading `=`, a **simple wildcard pattern**. Both kinds can be mixed in one list.

- **One rule per line.** Blank lines are ignored.
- **Comments:** a `#` starts a comment only at the beginning of a line or after a space or tab. So a `#` inside a regex (`[#*]`) is part of the regex.
- **First match wins.** Put `allow` exceptions *above* the broader rules they override.
- **The action** is the last word on the line, if it is one of `allow`, `ignore`, `voicemail` or `answer` (any case). Otherwise the whole line is the pattern and the action is `ignore`.

### Preloaded rules

A new install starts with:

```
# Caribbean area codes (268 284 473 649 664 767 784 809 829 849 876): "one-ring" call-back scams
^\+?1?(268|284|473|649|664|767|784|809|829|849|876)\d{7}$  ignore
# Premium-rate: area code 900, and 976 local exchanges in any area code
^\+1(900\d{7}|\d{3}976\d{4})$  voicemail
# Toll-free (800 888 877 866 855 844 833)
^\+1(800|888|877|866|855|844|833)\d{7}$  voicemail
# '=' = simple wildcard pattern, e.g.:
# =404555*  answer
# =+44*  ignore
```

| Rule | Catches | Action | Notes |
|---|---|---|---|
| Caribbean | NANP area codes 268, 284, 473, 649, 664, 767, 784, 809, 829, 849, 876 | ignore | The usual source of "one-ring" scams that try to get you to call back an expensive number. See the note on optional country codes below. |
| Premium-rate | Area code **900**, and exchange **976** in *any* area code (`(404) 976-1234`) | voicemail | 976 was the old local pay-per-call exchange, not an area code. Some areas have since reused 976 as an ordinary exchange, so a few normal local numbers will also go to voicemail. Add an `allow` rule above it for any you need. |
| Toll-free | NANP toll-free codes 800, 888, 877, 866, 855, 844, 833 | voicemail | Businesses, including banks, pharmacies and delivery firms, often call from toll-free numbers. Voicemail lets them leave a message. Put an `allow` rule above this one for any you want to ring through, e.g. `=8005551234 allow`. |

When rules are upgraded, an earlier default list that was **never edited** is replaced by the current one. Lists you've changed are left exactly as they are.

### Regular-expression rules

Any line without a leading `=` is a regular expression.

- **What it's tested against:** the number in **E.164 form**, `+` followed by the international digits, after normalization (see *How incoming numbers are normalized* below). So `(268) 555-1234`, `1 268 555 1234` and `+1 268 555 1234` all reach the regex as `+12685551234`.
- **"Find" semantics:** the regex may match anywhere in the number unless you anchor it with `^` and `$`. `5550199` matches any number containing those digits.
- **Syntax:** Java `java.util.regex`, the familiar Perl/PCRE style: `\d`, `{n}`, `(a|b)`, `[0-9]`, `(?:…)` and so on. No delimiters and no extra escaping: write the regex exactly as you would anywhere else. `/` and `#` need no escaping, and spaces are allowed.
- **Errors** are listed under the editor with ✗ and the line is skipped: `bad regex: <reason> near position N`.

**Watch out for optional country codes.** NANP numbers always arrive as `+1` followed by ten digits, so a `1?` in the regex isn't needed, and it can match more than intended. The preloaded rule `^\+?1?(268|…|876)\d{7}$` matches every Caribbean number. But because `1?` is optional, it also matches some numbers in other countries: Norway's `+47 32 22 33 34` arrives as `+4732223334`, which the regex reads as `+`, `473`, `2223334`. To rule that out, use `^\+1(268|284|473|649|664|767|784|809|829|849|876)\d{7}$`. Use **Test a number** to check edge cases like this.

| Regex rule | Matches | Doesn't match |
|---|---|---|
| `^\+1(268\|876)\d{7}$` | +1 268 and +1 876 numbers | `+4732223334` |
| `^\+44 voicemail` | every UK number | |
| `^\+1800\d{7}$ allow` | US toll-free 800 numbers (an exception) | |
| `(\d)\1{6}` | any number with the same digit 7 times in a row | |
| `5550199` | any number containing 5550199 | |

**Wildcard-looking lines get a warning.** A line made only of digits and `*` or `?` but no `=` is probably a wildcard pattern with the `=` forgotten. As a regex it means something very different: `888*` is "`88` followed by any number of `8`s, anywhere in the number", which catches far more than area code 888. Such lines are kept as regexes, but flagged under the editor with ⚠ and the suggestion `=888*`. Invalid ones, such as `+44*`, are rejected with ✗ and the same suggestion.

### Wildcard (`=`) rules

A line starting with `=` uses simple wildcard matching. Spaces, tabs and `( ) . -` inside the pattern are ignored, so `= (404) 555-****` is the same as `=404555****`.

| Character | Meaning |
|---|---|
| `0`–`9` | That digit |
| `*` | Any run of digits, including none |
| `?` | Exactly one digit |
| leading `+` | International pattern (see below) |

Anything else makes the line invalid: `can't understand wildcard pattern "…"`.

- **Without `+`: a North American (NANP) pattern.** It's matched against the **10-digit national number** (area code + exchange + line). A leading `1` followed by a digit is dropped, so `=1888*` means the same as `=888*`. These patterns never match numbers outside +1.
- **With `+`: an international pattern.** It's matched against the **full international number including country code**, with no `+`.

Unlike a regex, a wildcard must match the **whole** number, so `=888` only matches a number that is exactly "888".

| Rule | Matches | Doesn't match |
|---|---|---|
| `=888*` | any +1 888 number | `+888…` (not NANP) |
| `=1877* voicemail` | any +1 877 number, sent to voicemail | |
| `=404555????` | +1 404 555 0000 to 9999 | `4045551` (too short) |
| `=*9999` | any NANP number ending 9999 | |
| `=+44*` | any UK number | `44…` without `+` (that's read as NANP) |
| `=+44 20 *` | London numbers | other UK numbers |
| `=4045551234 allow` | exactly that number, which then rings normally | |

### How incoming numbers are normalized (`Rules.international`)

| Incoming form | Becomes |
|---|---|
| Starts with `+` | its digits (`+44 20 7946 0000` becomes `442079460000`) |
| 10 digits | `1` + digits (assumed NANP) |
| 11 digits starting `1` | unchanged |
| Starts with `011` | the `011` is dropped (US international dialling prefix) |
| Anything else | its digits unchanged |

Regex rules see `+` followed by the result. NANP wildcard rules see the last 10 digits of an 11-digit number beginning with `1`. Any other number has no national form, so only `+` wildcards and regexes can match it.

### A typical rule list

```
=4045551234 allow       # exception: must be above the 404 rule
=404555*    answer
# Caribbean one-ring scams
^\+1(268|284|473|649|664|767|784|809|829|849|876)\d{7}$
=888*                   # ignore is the default
=877*       voicemail
^\+44       ignore
```

### Upgrading from 1.1

Version 1.1 had only wildcard rules, written without `=`. On the first start after upgrading, saved rules are converted automatically (rule format 2):
- **Unchanged defaults:** if the saved rules are exactly the old default list, they're replaced by the new preloaded list. The same happens on later upgrades: an untouched 1.2 default list becomes the 1.3 one.
- **Your own rules:** every rule line gets an `=` prefix, so it keeps its meaning. Comments and blank lines are kept.

---

## 4. Hidden and withheld numbers

A call counts as hidden when `Call.Details.getHandlePresentation()` isn't `PRESENTATION_ALLOWED` (restricted, unknown, payphone), or when the number is empty. Hidden calls skip the rule list and use the **Hidden / withheld numbers** setting, which offers the same four actions. The default is **Ring normally**. In the log they appear as `Hidden number` with rule `[hidden/withheld]`.

---

## 5. User interface

All on one screen (`MainActivity`), built in code with no layout files:

| Element | Function |
|---|---|
| **Screening enabled** switch | Off means every call is allowed. The role stays held, so turning it back on works instantly. |
| **Status lines** | `✓/✗ Screening incoming calls` shows whether the app holds the screening role. `✓ Can answer & hang up` shows whether both answer permissions are granted. |
| **Make Call Ignore the call-screening app** | Shown only when the role isn't held; opens Android's role request dialog. |
| **Allow "answer & hang up"** | Shown only when permissions are missing; asks for *Phone calls → answer* and *Phone status*. |
| **Hidden / withheld numbers** | Drop-down choosing the action for hidden callers. Saved immediately. |
| **Rules** editor | Monospace, multi-line and horizontally scrolling. Below it: rule count, saved/unsaved state, any errors (✗, line skipped) and warnings (⚠, e.g. a wildcard missing its `=`), updated as you type. |
| **Save rules** | Saves the editor contents. **Rules take effect only after saving.** |
| **Test a number** | Type or paste any number to see what the *editor's current text* would do with it, including the matching rule. Unsaved edits are tested too. |
| **Last 20 screened calls** | Newest first: time, number, action taken, matching rule. Only blocked, voicemail and answered calls are logged; allowed calls aren't. |
| **Clear log** | Empties the log. |

---

## 6. Data storage

Everything is in one private SharedPreferences file, `callignore` (`/data/data/io.github.rowsail.callignore/shared_prefs/callignore.xml`):

| Key | Type | Default | Contents |
|---|---|---|---|
| `enabled` | boolean | `true` | Screening switch |
| `rules` | string | the preloaded rules (§3) | Rule editor text, verbatim |
| `rulesFormat` | int | `1` if absent | Rule syntax version. `2` = bare lines are regexes and wildcards need `=`. Older saved rules are migrated on first read. |
| `hidden` | string | `allow` | Hidden-number action keyword |
| `log` | string | empty | Up to 20 lines, newest first: `when⇥number⇥action label⇥rule` |

`android:allowBackup="false"`, so **settings aren't included in Android or Google backups**. Keep a copy of your rule list somewhere else. Reinstalling with `adb install -r` keeps the data; uninstalling deletes it.

---

## 7. Permissions and privileges

| Permission or role | Why | Granted by |
|---|---|---|
| Role `CALL_SCREENING` | Receive incoming calls for screening | The in-app button, or `cmd role add-role-holder` |
| `BIND_SCREENING_SERVICE` | Protects `IgnoreService` so only Telecom can bind it | The system |
| `ANSWER_PHONE_CALLS` | `acceptRingingCall()` and `endCall()` for `answer` rules | Runtime prompt or `pm grant` |
| `READ_PHONE_STATE` | `getCallState()`, to tell when an answered call has connected | Runtime prompt or `pm grant` |

The app doesn't request internet, contacts, call log, SMS or storage access.

---

## 8. Source layout

```
CallIgnore/
├── app/
│   ├── build.gradle                     app ID, SDK levels, version, optional release signing
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      activity, screening service, permissions
│       │   ├── java/io/github/rowsail/callignore/
│       │   │   ├── Rules.java           rule parsing, number normalization, matching (pure Java)
│       │   │   ├── Store.java           preferences: switch, rules, hidden action, log
│       │   │   ├── IgnoreService.java   CallScreeningService: decisions plus answer/hang-up loop
│       │   │   └── MainActivity.java    settings screen, built in code
│       │   └── res/drawable/ic_launcher.xml   vector launcher icon
│       └── test/java/.../RulesTest.java JUnit tests for Rules
├── build.gradle, settings.gradle, gradle.properties
├── gradlew, gradle/wrapper/             Gradle wrapper (8.14.3, checksum-pinned)
├── fastlane/metadata/android/en-US/     store listing: descriptions, changelogs, icon, screenshots
├── docs/fdroid/                         draft F-Droid metadata for submission
├── .github/workflows/release.yml        CI: build, test, sign and publish on each v* tag
├── README.md, MANUAL.md, LICENSE
└── keystore.properties, *.keystore      local signing only: git-ignored, never committed
```

`Rules` doesn't depend on Android, so the matching logic is tested as plain JVM unit tests.

---

## 9. Building

### Requirements

| Tool | Notes |
|---|---|
| JDK 17 or 21 | Gradle 8.14 doesn't run on newer JDKs. `JAVA_HOME` must point at a full JDK (with `javac`), not just a runtime. |
| Android SDK, platform 36 | Found through `ANDROID_HOME`, or `sdk.dir` in `local.properties` (git-ignored). The Android Gradle Plugin installs missing build-tools automatically. |
| Network access on first build | The wrapper downloads Gradle, and Gradle downloads the Android Gradle Plugin 8.13.0 and JUnit 4.13.2. |

To set up a new machine:

```sh
sdkmanager --licenses
sdkmanager "platforms;android-36"
```

### Commands

```sh
./gradlew test                 # JUnit tests for Rules
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release[-unsigned].apk
./gradlew installDebug         # debug build onto the connected device
```

The release build is not minified, and it leaves out the dependency-metadata block so builds can be reproduced.

### Signing

Release builds are **unsigned** unless the project root has a `keystore.properties`:

```properties
storeFile=callignore.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Both files are git-ignored and must never be committed.

- **F-Droid** builds the app from source and signs it with **F-Droid's own key**, so it needs neither file.
- **Your own builds:** Android installs an update only if it's signed with the same key as the installed copy. Keep your keystore safe; if you lose it, the app has to be uninstalled (deleting its settings) before a build with a new key will install.
- **Mixing sources:** the F-Droid build and a self-signed build are signed with different keys, so switching between them also means uninstalling once.
- **Debug builds** use the SDK's debug key.

---

## 10. Installing and setting up

### Over adb (USB debugging on)

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING io.github.rowsail.callignore
adb shell pm grant io.github.rowsail.callignore android.permission.ANSWER_PHONE_CALLS
adb shell pm grant io.github.rowsail.callignore android.permission.READ_PHONE_STATE
```

### On the phone

Open **Call Ignore**, then tap **Make Call Ignore the call-screening app** and accept. If you'll use `answer` rules, also tap **Allow "answer & hang up"** and allow both prompts. Once both status lines show ✓, setup is done.

### Checking it

```sh
adb shell cmd role get-role-holders android.app.role.CALL_SCREENING   # io.github.rowsail.callignore
adb shell dumpsys package io.github.rowsail.callignore | grep -E 'versionName|granted=true'
```

### Updating

Build and `adb install -r` again. The role, permissions and settings survive updates signed with the same key.

### Releasing

Releases are built by GitHub Actions (`.github/workflows/release.yml`):

```sh
# 1. In app/build.gradle: bump versionCode and versionName
# 2. Add fastlane/metadata/android/en-US/changelogs/<versionCode>.txt (it becomes the release notes)
git commit -am "Call Ignore 1.4: …"
git tag -a v1.4 -m "Call Ignore 1.4"
git push origin main --tags
```

When a `v*` tag is pushed, the workflow:
1. **Checks** that the tag equals `v<versionName>` and that the changelog for `<versionCode>` exists. If not, it stops without publishing anything.
2. **Builds and signs:** runs the unit tests and builds a release APK signed with the repository's signing secrets.
3. **Publishes** a GitHub **pre-release** named "Call Ignore <version> (beta)". It attaches `CallIgnore-<version>.apk` and its `.sha256`, and the notes include the changelog plus the APK and signing-certificate SHA-256s.

F-Droid also picks up new versions from the tags.

**Dry run:** **Actions → Release → Run workflow** (or `gh workflow run release.yml`) does the same build and signing without publishing anything. The signed APK is kept as a workflow artifact for 90 days.

**Signing secrets** (repository **Settings → Secrets and variables → Actions**):

| Secret | Contents |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 callignore.keystore` |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Anyone who can change the workflows in this repository can use the key, so give write access sparingly. If the key is ever exposed, generate a new one and replace the secrets. Users will then have to uninstall once, since Android rejects updates signed with a different key.

---

## 11. Testing

### Automated (off-device)

`app/src/test/java/io/github/rowsail/callignore/RulesTest.java` (21 JUnit 4 tests) covers:
- **Wildcards:** NANP numbers in several formats, leading-`1` patterns, separators, international `+` patterns, `011` dialling, suffix wildcards, whole-number matching, and `+888…` not being treated as NANP
- **Regexes:** the preloaded Caribbean rule (including its optional-`1` pitfall) and the strict alternative; find semantics; `#`, `/` and spaces inside a regex
- **Ordering:** first match wins across both kinds of rule
- **Errors and warnings:** invalid wildcards and regexes, wildcard-looking regexes, and the `=` hint
- **Comments:** comment parsing
- **Defaults and migration:** the preloaded rules parse with no errors or warnings; 900 and 976-exchange numbers and all seven toll-free codes go to voicemail, and near misses don't; the 1.1 → 1.2 migration works; untouched earlier default lists are upgraded and edited ones aren't
- **Display:** number formatting

Run them with `./gradlew test`. The report is written to `app/build/reports/tests/`.

### On the device

Use **Test a number** to check rules without a real call.

The call-handling paths only run with a real incoming call. To test them, use a second phone whose number **isn't in the contacts**:

1. Add `=<second number> ignore` (the `=` makes it an exact-number wildcard) and save, then call. The phone should stay silent; the caller hears ringback; the log shows *Ignore silently*.
2. Change the action to `voicemail`, save, and call. The call goes straight to voicemail; the log shows *Send to voicemail*.
3. Change the action to `answer`, save, and call. The call connects and drops at once; the log shows *Answer & hang up*.

Watch the decisions while you test:

```sh
adb logcat -s CallIgnore
```

| Log line | Meaning |
|---|---|
| `screen (888) 555-0123 -> IGNORE (=888*)` | Decision and matching rule (`null` means no rule matched) |
| `answered` | `acceptRingingCall()` was called |
| `endCall -> true` | The connected call was hung up |
| `gave up: call never rang` / `never connected` | The answer loop timed out after 5 s |

**Verification status (version 1.3):** the matching rules pass all automated tests. The app installs, holds the role, has both permissions and runs its screen without errors. The call-handling paths above haven't yet been tried with a real incoming call. `acceptRingingCall()` and `endCall()` are deprecated (since API 29), so the `answer` test (step 3) is the one most worth running on each new Android version.

---

## 12. Troubleshooting

| Symptom | Check |
|---|---|
| Far more calls are ignored than expected | A wildcard pattern written without `=` is being used as a regex (`888*` matches any number containing `88`). Look for ⚠ under the editor and add the `=`. |
| Matching calls still ring | Is the status `✓ Screening incoming calls`? Is the switch on? Did you tap **Save rules**? Is the caller in your contacts (contacts are never screened)? What does **Test a number** say for that exact number? |
| Ignored callers still reach voicemail | Expected: that's the carrier's no-answer forwarding. Use `answer` for those numbers, or turn off no-answer forwarding for the whole line (e.g. `##61#` on many GSM carriers). |
| `answer` behaves like ignore | Status shows `–`, or the log says `answer permission missing`: grant both permissions (§10). |
| `answer` sends the caller to voicemail | Look in `adb logcat -s CallIgnore` for `endCall` before `answered`. That shouldn't be possible, since hang-up waits for off-hook, but report it with the log. |
| Call screen flashes briefly for `answer` | Expected: the call has to reach the phone's call system before it can be answered. |
| Another app stopped blocking spam | It lost the screening role to Call Ignore; only one app can hold it. |
| Status shows ✗ after an Android update or reset | Tap **Make Call Ignore the call-screening app** again, or re-run the `cmd role` command. |
| Rules vanished | Settings aren't backed up (§6). Uninstalling, clearing app data or a factory reset deletes them. |

---

## 13. Removing it

```sh
adb uninstall io.github.rowsail.callignore
```

This also releases the screening role. Alternatively, untick **Screening enabled** to pause it without uninstalling, or assign the role to another app in **Settings → Apps → Default apps → Caller ID & spam app**.

---

## Credits and license

Call Ignore is © 2026 rowsail, licensed under the GNU General Public License v3.0 or later (see `LICENSE`).

The handset shape in the launcher and store icon is the "phone" icon from Google's [Material Icons](https://github.com/google/material-design-icons), used under the Apache License 2.0. The circle and slash are original.
