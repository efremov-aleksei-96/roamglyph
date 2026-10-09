# Roamglyph Privacy Policy

Last updated: 2026-10-09

Roamglyph is designed as a local-first exploration tracker.

## What Roamglyph stores

When you grant location permission and use the app, Roamglyph can store:

- your last known location and reported accuracy;
- the H3 map cells you have explored;
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

## Export and import

Export happens only when you explicitly choose **Export history** and select a
destination through Android's system document picker. Import likewise happens only
after you explicitly select a file.

Roamglyph does not receive a copy of exported or imported files.

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
