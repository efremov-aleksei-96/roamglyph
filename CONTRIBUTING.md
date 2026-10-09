# Contributing to Roamglyph

Roamglyph is an Apache-2.0 open-source Android project.

## Product principles

1. **Local first** — exploration history belongs to the user.
2. **Portable data** — core history must remain exportable and restorable.
3. **No mandatory Roamglyph server** — core exploration must survive without one.
4. **FLOSS runtime** — do not introduce proprietary Play Services, Firebase,
   advertising, analytics, or closed-source runtime SDKs.
5. **Map-provider independence** — user exploration data must not depend on a map
   vendor or tile provider.
6. **Privacy by default** — avoid transmitting user location unless the user
   explicitly requests a feature that requires it.

## Build requirements

- JDK 17
- Android SDK platform 36
- Gradle 9.6.0

Build:

```bash
gradle :app:assembleDebug
```

Verify:

```bash
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleRelease
```

The release build is intentionally allowed to build unsigned when no release
keystore is supplied. This lets F-Droid rebuild and sign from source.

## Pull requests

Keep changes focused. Explain any new network endpoint, permission, binary
dependency, or persistent-data migration in the pull request description.

Do not commit signing keys, credentials, API tokens, user location databases, or
private test exports.
