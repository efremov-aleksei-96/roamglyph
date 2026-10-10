# Roamglyph 0.5.0 release-candidate acceptance

This is a release gate, not a claim that the 0.5.0 features have already passed
physical-device testing. Automated AOSP smoke tests prove launchability, but do
not exercise a full ARM64 GPS ride, map rendering performance, or Android
document-provider interoperability.

## Before installing an RC

If using any 0.4.x prototype or previously installed CI debug APK:

1. Stop exploration.
2. Export the **full JSON backup** using the in-app menu.
3. Confirm the file is non-empty and saved outside app-private storage.
4. Keep a second copy elsewhere, if practical.
5. Only then uninstall an older build if Android rejects the new signing
   certificate. The historical 0.4.0 signing certificate is compromised;
   never rely on it for release updates.
6. Install the new test APK and restore the saved JSON.

The full JSON backup is required to preserve explored cells, session history,
and Discoveries. **GPX is not a replacement for a full Roamglyph backup.**

## Required physical-device checks (ARM64, Android 15/16)

| Area | Procedure | Acceptance |
| --- | --- | --- |
| First launch | Grant location and notification permissions | No crash; current position and status work |
| Background GPS | Start, turn screen off, move 500 m | H3 cells accumulate while foreground service stays active |
| Bicycle | Ride at varying speeds, including hill climbs | Reasonable route with no systematic false reveals |
| Accuracy | Temporarily obstruct GPS / go indoors | Poor fixes do not produce remote clusters |
| Location toggle | Disable Android Location; re-enable later | UI shows `Location disabled`, then resumes |
| Fog | Pan/zoom near explored and unexplored cells | Discovered area remains visible, unexplored area darkens without freezing |
| Fog persistence | Toggle Fog off/on; restart app | Setting and explored territory persist |
| Discoveries | Explore near mapped POIs, pan away and back | Anonymous hints appear before visits; discovered POIs persist after eligible exploration |
| Online map | Test street/building details on Wi-Fi | Buildings, roads, and basic styling render; attribution remains available |
| Offline map | Import valid OpenMapTiles-schema PMTiles v3 MVT, enable it, turn off internet | Streets/buildings/water render without online tile requests |
| Offline limitation | Inspect offline map labels and POIs | Basic offline style is intentionally label-free; map geometry works |
| Map switching | Switch online/offline several times | GPS history, Fog, and Discoveries remain unchanged |
| History | Record a short session and Stop | Session and aggregates appear; app restart preserves them |
| GPX | Export the short session to a document provider | GPX opens in another GPX-capable app; chronological route and timestamps present |
| Full backup | Stop tracking, export JSON v3; import twice | Sessions, cells, points, Discoveries restored; second import has no duplicates |
| Old backup | Import a version-1 cell-only JSON and a version-2 backup | Old history remains valid and metadata are not invented |
| Offline map deletion | Remove local PMTiles file | Reverts to online safely; no history deleted |
| Battery/process | Run a typical extended ride | No unexpected exit, repeated freezes, or unusual battery drain |
| Restart | Force-close/open while recording; stop from notification | Recoverable state; stopping terminates foreground tracking |

## Automatic gates

GitHub Actions must pass on the **exact release candidate head**:

- testDebugUnitTest;
- lintDebug;
- assembleDebug;
- unsigned assembleRelease;
- proprietary-runtime dependency check;
- committed-keystore rejection;
- AOSP Activity launch + HistoryActivity launch.

These automated checks do **not** guarantee runtime behavior on a physical device.

## Before pushing the stable tag

- All required physical checks are marked PASS, or a known limitation is clearly
  documented and judged acceptable for the first release.
- Capture **actual** screenshots from the tested build for
  `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- Create a new, private release signing key (not the committed historical key),
  configure GitHub Actions secrets, and securely back up the key outside GitHub.
- Confirm `versionName`, `versionCode`, changelog and F-Droid recipe match.
- Only then create a `v0.5.0` tag on an approved `main` commit and verify
  `apksigner` output and release SHA-256 checksums.
- Request inclusion in F-Droid/IzzyOnDroid **after** the actual release exists;
  store acceptance is controlled by their maintainers and is not guaranteed.

## Known limitations of the first RC

- Offline PMTiles must be OpenMapTiles-schema **MVT vector archives**. Arbitrary
  PMTiles packages may be readable but not compatible with the bundled style.
- The initial offline style does not include text labels, glyphs, or sprites.
- Imported local PMTiles files are stored in app-private storage; the user must
  independently obtain and retain a copy of the map package.
- GPS route, explored cells, and POI availability depend on device location
  quality and coverage of the imported/online map data.
