# Library availability — 2026-10-29

Visible library rows query metadata on a shared IO dispatcher limited to two active checks. A cancellation signal accompanies each query and the UI attempt has a three-second timeout. A provider can still delay cancellation; no permanent scanner or periodic task is created. Thumbnails are requested only after a successful metadata lookup.

A missing result is reported only for known mounted local storage (the app's indexed MediaStore downloads or a primary ExternalStorageProvider document). A SecurityException means access was denied. Unknown providers, unavailable authorities, null/empty remote listings and other failures remain unavailable; they are not treated as proven deletion. A provider may reject a moved/deleted tree URI with SecurityException, so the UI says access was denied rather than claiming certainty about why.

The durable index is never erased by these checks. On a confidently missing local item, an explicit 'Remove missing entry' command asks for confirmation and performs a fresh availability check before removing only the index record. It never calls file deletion. The library refresh button rechecks visible rows while retaining filters and sorting.

Evidence is tracked in the local device log and CI matrix. The tests cover a live MediaStore entry followed by deletion, rejection of metadata removal for an existing file, absent providers, denied document queries and empty remote queries. Metadata presence is not a codec/integrity check and does not prove that remote file contents can currently be downloaded. Physical removable/cloud provider matrices remain open.
