# Roamglyph Privacy Policy

Last updated: 2026-10-09

Roamglyph is designed as a local-first exploration tracker.

## What Roamglyph stores

When you grant location permission and use the app, Roamglyph can store:

- your last known location and reported accuracy;
- timestamped GPS points recorded during exploration sessions, including coordinates,
  accuracy and, when Android supplies them, speed, altitude, and provider name;
- whether each recorded point was accepted for exploration or rejected by the
  accuracy/teleport filter;
- exploration-session start/end times and local statistics;
- H3 map cells you have explored and, for newly recorded cells, their first-seen
  timestamp;
- whether background exploration is currently enabled.

This data is stored in the app's private local storage on your Android device.

## What Roamglyph does not do

Roamglyph has:

- no Roamglyph account;
- no advertising SDK;
- no analytics SDK;
- no Firebase or Google Play Services dependency;
- no Roamglyph-operated backend receiving your location;
- no automatic upload of exploration history.

Android system cloud backup is disabled for the app. Use Roamglyph's explicit export
feature if you want a portable backup.

## Map network requests

The default online basemap is provided by OpenFreeMap and rendered with MapLibre.
Loading the map therefore sends normal HTTPS requests to OpenFreeMap infrastructure.
Those requests are governed by OpenFreeMap's privacy policy:

https://openfreemap.org/privacy/

Map data is derived from OpenStreetMap:

https://www.openstreetmap.org/copyright

Future offline-map support is intended to allow map viewing without a map provider,
but it is not part of version 0.5.0.

## Discoveries

The Discoveries feature reads POI features from the same OpenMapTiles vector data
that is already loaded for the visible map. Classification and visit matching happen
locally on the device. Roamglyph does not send a separate discovery or POI lookup
request to a Roamglyph server.

When a POI lies inside explored H3 coverage, Roamglyph can store its identifier,
name, category, coordinates, discovery time when known, and source provenance in
the local database and portable backup.

## Export and import

Export happens only when you explicitly choose **Export backup** and select a
destination through Android's system document picker. A version-2 backup contains
explored cells, sessions, and recorded GPS points in a documented JSON format.
Import likewise happens only after you explicitly select a file.

Legacy version-1 cell-only JSON exports remain importable.

Roamglyph does not receive a copy of exported or imported files. See
`docs/BACKUP_FORMAT.md` in the source repository for the portable format.

## Permissions

- **Location**: required to determine your position and reveal nearby cells.
- **Foreground service / location foreground service**: keeps an explicitly started
  exploration session running with the screen off.
- **Notifications**: used for the persistent foreground-service notification on
  Android versions where notification permission applies.
- **Internet**: used to load the default online basemap.

Roamglyph does not request contacts, phone, SMS, camera, microphone, or storage-wide
permissions.

## Source code

https://github.com/efremov-aleksei-96/roamglyph

Roamglyph is licensed under Apache-2.0.
