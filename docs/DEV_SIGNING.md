# Stable signing for installable development APKs

## Why dev.21–dev.24 could not update over each other

The main-branch Android CI workflow previously used the runner's automatically
generated Android debug keystore. Each fresh GitHub-hosted runner could create
a different certificate. Android **rejects in-place updates** with a mismatched
APK certificate, regardless of matching application ID or increasing
`versionCode`. It is not a RoamGlyph database error.

The debug key accidentally exposed in old pre-0.5 repository history is
**compromised**, and must **never** be reused to avoid this migration.

## Design

From `0.5.0-dev.25` onward:

- Local/PR debug builds can continue using their local default debug key,
  but GitHub CI **never publishes those APKs** to testers.
- Only builds of `main` with a dedicated persistent private PKCS12 key
  produce the publicly downloadable `roamglyph-dev25-signed-apk` artifact.
- The private key and its password live in repository **Actions Secrets**,
  not in Git source, Actions logs or artifacts.
- Build `app/build.gradle.kts` configures the `debug` signing certificate
  only when both `ROAMGLYPH_DEV_KEYSTORE` and
  `ROAMGLYPH_DEV_KEYSTORE_PASSWORD` are provided.
- Main CI **fails closed** if the dedicated signing secrets are absent, so
  another randomly signed development APK cannot accidentally be advertised.
- The CI checks the APK certificate SHA-256 against the configured private key
  before upload. The certificate digest is public; the key is never logged.
- Official tag-triggered releases keep their **separate release certificate**
  (`ROAMGLYPH_KEYSTORE_*`). F-Droid upstream builds may use another
  independent signature.

## One-time setup (Windows / PowerShell)

On a trusted Windows computer with JDK 17+ (`keytool`) and the
[GitHub CLI](https://cli.github.com/) (`gh`) available in the terminal:

1. Run `gh auth login` and authenticate an account allowed to administer
   GitHub Actions Secrets for `efremov-aleksei-96/roamglyph`.
2. In a clean checkout of this repository, run:

   ```powershell
   .\scripts\setup-dev-signing.ps1 -BackupDirectory "D:\EncryptedVault\RoamGlyphSigning"
   ```

   Use your **actual private encrypted backup directory**. The example path
   is illustrative and does not have to exist beforehand.

3. The helper creates **one** `roamglyph-dev.p12` and
   `roamglyph-dev-password.txt` (or reuses both existing matching files)
   and uploads exactly two private repository secrets:

   - `ROAMGLYPH_DEV_KEYSTORE_BASE64`
   - `ROAMGLYPH_DEV_KEYSTORE_PASSWORD`

4. Preserve **both** files safely in encrypted storage with a second backup.
   They are the signing identity for every future test build. **Do not**
   publish them, paste their contents into a PR/chat, or commit either file.
5. After merging the signing PR, run the Android CI workflow on `main`
   (or let a new commit trigger it), confirm both CI and Runtime Smoke pass,
   and install only the `roamglyph-dev25-signed-apk` artifact.

The script uploads secrets through `gh secret set` using standard input.
GitHub CLI and the Android tooling must be installed and authenticated locally.
No confidential values need to be sent back into this chat.

## Migrating from an existing dev.24 installation

**One last uninstall is unavoidable**, because the previous app uses a
different, now-unrecoverable ephemeral debug signing key:

1. Stop exploration and **export a JSON history backup** to storage outside
   the app. Verify that the exported file is nonempty.
2. Preserve/reimport any local PMTiles map files separately; they are not
   necessarily included in the JSON history export.
3. Uninstall dev.24, install the new **persistently signed dev.25** APK,
   restore the JSON backup, and verify visited cells and sessions.
4. For all subsequent **GitHub test-channel APKs**, update in place
   (so long as the signing key stays unchanged and `versionCode` increases).

Switching later to an official release/F-Droid channel **with a different
certificate** may require another deliberate migration. Do not treat
GitHub test builds and app-store signed packages as interchangeable.

## In-app development updates (dev.26+)

- After Android CI passes on `main`, a separate, write-scoped job publishes
  the previously verified signed APK as an immutable-versioned GitHub
  **prerelease**, tagged `dev-vc<versionCode>-g<first8commit>`.
- The regular tag-release workflow matches only `v*`; development tags do
  not impersonate official production releases.
- RoamGlyph queries GitHub's anonymous `/releases` API once per day while
  opened and on demand under `⋮ → Check for updates`. Actions artifacts
  are not used for in-app installation (they require login and expire).
- Candidate release must be a prerelease with the exact tag, APK filename,
  HTTPS upstream download URL and GitHub-calculated SHA-256 digest.
- Downloaded APK SHA-256, application ID, versionCode **and signing
  certificate** must match policy/current installation before handing off
  to the system package installer.
- Download requires confirmation, and Android requires confirmation to
  install. For sideloading, the user might need to allow installation from
  RoamGlyph once under Android Settings.
- Updates are blocked while an active exploration session is running;
  stop tracking before updating. Existing history is not uninstalled.
- The debug/dev signing certificate is **not the official release signature**.
  Moving to stable/F-Droid may still require a backed-up migration.
- Testing acceptance: install dev.26 from the public development Release,
  then install dev.27 from the in-app check after it is published. Confirm
  on-device data survival; one build cannot prove two-build OTA continuity.
