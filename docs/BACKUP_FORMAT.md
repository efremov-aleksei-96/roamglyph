# Roamglyph portable backup format

Roamglyph backups are intentionally independent of any Roamglyph server or map
provider.

## Version 2

The exported file is UTF-8 JSON with this top-level structure:

```json
{
  "format": "roamglyph-backup",
  "version": 2,
  "h3_resolution": 13,
  "exported_at_ms": 0,
  "cell_count": 0,
  "session_count": 0,
  "gps_point_count": 0,
  "visited_cells": [],
  "sessions": [],
  "gps_points": []
}
```

The count fields are informational. Import validates the actual arrays.

### visited_cells

Each item contains:

- `h3`: H3 cell address, currently resolution 13;
- `first_seen_at_ms`: Unix epoch milliseconds or `null` when the historical
  timestamp is unknown;
- `source`: provenance label.

### sessions

Each item contains:

- `session_id`: stable UUID;
- `started_at_ms`: Unix epoch milliseconds;
- `ended_at_ms`: Unix epoch milliseconds or `null`;
- `distance_m`: accumulated accepted-route distance;
- `accepted_points`: count of accepted GPS points;
- `new_cells`: cells first discovered by that session;
- `source`: provenance label.

Restored sessions are marked internally as imported so future competitive features
can distinguish portable personal history from live-verified activity.

### gps_points

Each item contains:

- `point_id`: stable UUID;
- `session_id`: parent session UUID;
- `timestamp_ms`;
- `latitude`, `longitude`;
- `accuracy_m`;
- optional `speed_mps`, `altitude_m`, and `provider`;
- `accepted_for_exploration`;
- optional `h3` for accepted points;
- optional `rejection_reason`.

## Import safety

Before importing version 2, Roamglyph performs a complete validation pass:

- format/version/resolution are checked;
- required arrays must exist;
- H3 values are validated;
- coordinates and basic numeric ranges are validated;
- duplicate session IDs are rejected;
- every GPS point must reference a session present in the backup.

Only after validation does restore begin. Inserts use stable primary keys and
conflict-ignore semantics, making repeated import idempotent.

## Legacy version 1

Roamglyph still accepts the previous cell-only format:

```json
{
  "format": "roamglyph-history",
  "version": 1,
  "h3_resolution": 13,
  "cells": ["..."]
}
```

Because version 1 did not store visit timestamps, imported legacy cells have an
unknown first-seen time.

## Compatibility policy

A future backup format must receive a new integer `version`. Existing documented
versions should remain importable whenever practical. Unknown versions are rejected
instead of being guessed.
