# Call Ignore

An Android app that screens incoming calls against number patterns you write yourself, before the phone rings.

| Action | What happens |
|---|---|
| `ignore` (default) | No ring, no screen, no notification. The phone behaves as if the call isn't happening. |
| `voicemail` | The call is rejected, which sends it to voicemail. |
| `answer` | The call is answered and hung up at once, so it never reaches voicemail. |
| `allow` | An exception: the number rings normally. |

```
4045551234 allow     # exceptions go first: the first matching rule wins
888*                 # every US/Canada number in area code 888
877*       voicemail
+44*       answer    # '+' patterns match international numbers
```

`*` matches any run of digits and `?` exactly one. Spaces, dashes and brackets are ignored. Hidden and withheld numbers have their own setting, and the last 20 screened calls are logged on the phone.

The app has no internet permission, no contacts access, no tracking and no ads.

**Requirements:** Android 10 or later. Android only passes calls from numbers *not* in your contacts to screening apps.

## Install

Build it yourself (below). Once the app is on F-Droid, you'll also be able to install it from there. After installing, open the app and:

1. tap **Make Call Ignore the call-screening app**;
2. tap **Allow "answer & hang up"** if you'll use `answer` rules.

## Build

You need JDK 17 or 21, and the Android SDK with platform 36 (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew test assembleRelease   # unit tests + app/build/outputs/apk/release/
./gradlew installDebug           # debug build straight onto a connected device
```

Release builds are unsigned unless there's a `keystore.properties` in the project root. The file is git-ignored and must never be committed:

```properties
storeFile=callignore.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

## Documentation

[MANUAL.md](MANUAL.md) covers the screening logic, rule syntax, storage, permissions, building, testing and troubleshooting.

## License

Copyright © 2026 rowsail

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

The handset in the icon is the "phone" icon from Google's [Material Icons](https://github.com/google/material-design-icons) (Apache License 2.0).
