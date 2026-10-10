# Distribution and release plan

Roamglyph is structured for source-built F-Droid distribution and
developer-signed GitHub releases for repositories that consume upstream APKs.

## F-Droid main repository

Before submitting a stable version:

1. Merge a fully green release candidate to `main`.
2. Complete the physical ARM64 acceptance matrix in
   [RELEASE_CANDIDATE_QA.md](RELEASE_CANDIDATE_QA.md).
3. Update `CHANGELOG.md` and Fastlane changelog metadata.
4. Create a stable Git tag matching `versionName`, e.g. `v0.5.0`.
5. Add real screenshots under
   `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
6. Submit metadata to the official `fdroiddata` repository.

F-Droid normally builds its own APK from source and signs it with its own key unless
a reproducible-build arrangement is configured.

### H3 native library note

`com.uber:h3-android` is FLOSS (Apache-2.0) and distributed through a trusted
Maven repository, but it contains native Android binaries. F-Droid's scanner may
require reviewer confirmation or recipe-specific handling. Do not add a scanner
exception without a documented reviewer-approved reason.

## GitHub Releases / IzzyOnDroid / Obtainium

IzzyOnDroid consumes APKs released by the upstream developer and pins the signing
certificate. Official upstream APKs therefore need one stable private release key.

The tag-triggered workflow `.github/workflows/release.yml` refuses to publish
unless these GitHub Actions secrets exist:

- `ROAMGLYPH_KEYSTORE_BASE64`
- `ROAMGLYPH_KEYSTORE_PASSWORD`
- `ROAMGLYPH_KEY_ALIAS`
- `ROAMGLYPH_KEY_PASSWORD`

Never commit or paste the corresponding private key into issues, pull requests, or
chat transcripts.

## Compromised pre-0.5 debug key

The key used for versions through 0.4.0 was publicly committed and is permanently
untrusted. Do not reuse it for any official channel.

Early testers should export history before switching to the first official signing
certificate. A one-time uninstall/reinstall will normally be required.

## Reproducibility

The repository pins build versions and CI verifies an unsigned release build as
well as a debug build. A later hardening milestone should establish byte-for-byte
reproducible upstream signed releases.

## Store assets still required

Text metadata is maintained upstream. Before the first public-store submission we
still need real screenshots from the tested release build and, optionally, a
dedicated high-resolution store icon / feature graphic.

Do not use mock screenshots as release evidence.
