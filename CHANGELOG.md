# Changelog

## 0.5.0 - unreleased

### Changed

- Replaced Google Fused Location Provider with Android's open platform
  `LocationManager`; Roamglyph no longer depends on Google Play Services.
- Switched the default online basemap from direct OpenStreetMap raster tiles to
  OpenFreeMap vector styling rendered by MapLibre.
- Changed all in-app and notification text to English.
- Made location recentering slower and smoother.
- Increased contrast of explored cells.
- Correctly reports when Android location services are disabled.

### Security and distribution

- Removed the publicly committed debug signing key from the repository head.
- Added protected release-signing configuration using environment/secrets only.
- Added CI checks rejecting proprietary Google/Firebase runtime SDKs and committed
  signing keys.
- Added Apache-2.0 licensing, privacy, third-party notices, and app-store metadata.
- Added a tag-driven signed GitHub release workflow.

### Compatibility

The pre-0.5 development signing certificate is compromised. Export history before
moving from those development APKs to the first official release.
