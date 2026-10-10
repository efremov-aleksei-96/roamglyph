# GPX session export

Roamglyph can export an individual exploration session as standard **GPX 1.1**.

## Purpose

GPX is an interoperability format for using a recorded route in other mapping,
fitness, GIS, or archival tools. It is intentionally separate from the full
Roamglyph portable backup.

## Included

Each exported track contains accepted GPS fixes in chronological order:

- latitude and longitude;
- UTC timestamp;
- elevation when Android supplied altitude.

Only points that passed Roamglyph's exploration acceptance filter are exported.

## Excluded

GPX does not contain:

- rejected GPS fixes;
- H3 visited cells;
- Discoveries;
- Room database IDs/provenance beyond the track itself;
- Roamglyph backup metadata.

Use the versioned JSON backup when the goal is complete Roamglyph restoration.

## Long GPS gaps

If two accepted points are more than two minutes apart, Roamglyph starts a new
`trkseg`. This prevents GPX viewers from drawing one misleading straight line over
a period where Roamglyph did not record the route.

## Large sessions

Export is streaming. Accepted points are read from Room in pages of 1000, so a long
courier shift does not require loading the entire route into memory.

For an active session, export uses a timestamp snapshot captured when the user
chooses Export GPX. Points recorded after that moment are not added to the file.
