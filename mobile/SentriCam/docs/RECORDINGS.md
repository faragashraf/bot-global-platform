# Recordings Library V1

Recordings Library V1 provides a paged, operator-authorized archive for completed Android MP4 uploads stored by the Local Hub. It adds asynchronous real-frame thumbnails, database-backed filtering and sorting, lazy time aggregation, and three responsive web views without changing the Android upload queue or the `IRecordingStorageProvider` boundary.

Live View, timeline events, notifications, cloud sync, and AI are not part of this capability.

## Capability architecture

```text
Android finalized MP4
  -> existing upload queue and device-authenticated multipart upload
  -> RecordingService verifies streamed size and SHA-256
  -> media object stored through IRecordingStorageProvider
  -> Recording metadata committed with ThumbnailState=Pending
  -> upload returns 201 without waiting for media processing
  -> deduplicating thumbnail scheduler
  -> RecordingThumbnailWorker
  -> IRecordingThumbnailGenerator (local FFmpeg adapter)
  -> JPEG stored separately through IRecordingStorageProvider
  -> metadata committed with ThumbnailState=Ready

Authorized web operator
  -> paged RecordingQuery projection
  -> lazy time-bucket aggregation
  -> shared presentation/actions
     -> Large Grid | Compact Grid | List
```

Playback, range-enabled content responses, download, delete, authorization policies, checksum validation, and storage path validation remain in their existing flows.

## Thumbnail pipeline

`ThumbnailGenerationState` is persisted as `Pending`, `Processing`, `Ready`, or `Failed`, together with attempt count, safe error code, processing start, and generated time. Upload commits metadata before scheduling work. A missing or failing media processor therefore never rolls back an accepted upload or prevents the Hub from starting.

The worker uses a singleton, in-process deduplicating scheduler and one reader. It also discovers pending and stale-processing rows at startup, which makes work idempotent across Hub restarts. A ready recording is not processed twice. Processing rows older than ten minutes are recoverable. Failures remain failed until an operator explicitly retries them; this avoids an uncontrolled retry loop.

The FFmpeg adapter:

- resolves the stored media key through the Local Hub storage provider, which rejects rooted and traversal paths;
- seeks to 10% of duration, with a one-second preference and a 30-second ceiling;
- applies FFmpeg's representative-frame selection before scaling and padding to 640×360;
- emits a quality-3 JPEG, capped at 2 MiB;
- validates JPEG content type, extension, and start/end markers before accepting the object;
- uses `ProcessStartInfo.ArgumentList`, `UseShellExecute=false`, and `-nostdin`, so filenames and paths are never interpreted by a shell;
- has a configurable 30-second timeout and kills an over-time process tree;
- records only a bounded error code in recording metadata and does not expose media paths or FFmpeg output through the API.

The browser shows a neutral processing placeholder for a recording without a thumbnail and an unavailable/retry state after permanent failure. Existing stored SVG covers continue to be served while their replacement is pending.

## Media dependency and Local Hub packaging

FFmpeg is intentionally behind `IRecordingThumbnailGenerator`. `RecordingStorage__FfmpegPath` must point to the executable supplied with the Local Hub distribution. The server does not assume an executable on `PATH`, and an end user should not be asked to install FFmpeg manually as an undocumented prerequisite.

The packaging owner must:

1. select and license an FFmpeg distribution appropriate to the target operating system;
2. place the executable in a stable, non-user-writable application location;
3. set `RecordingStorage__FfmpegPath` to that packaged executable;
4. preserve executable permissions on macOS/Linux and signing/notarization requirements where applicable;
5. validate thumbnail extraction in the packaged artifact.

If the path is empty, missing, non-executable, times out, or cannot decode a video, the recording transitions to `Failed` with a code such as `ffmpeg_not_configured`, `ffmpeg_unavailable`, `ffmpeg_timeout`, or `ffmpeg_failed`. Upload, browsing, playback, download, delete, and Hub startup remain available.

Configuration:

```text
RecordingStorage__RootPath
RecordingStorage__MaximumUploadBytes
RecordingStorage__FfmpegPath
RecordingStorage__ThumbnailWidth          # 640 default
RecordingStorage__ThumbnailHeight         # 360 default
RecordingStorage__ThumbnailJpegQuality    # 3 default; FFmpeg q:v scale
RecordingStorage__ThumbnailTimeoutSeconds # 30 default
```

## Existing-recording regeneration

Migration `AddRecordingLibraryUxV1` keeps existing media and thumbnail keys intact. Existing JPEG thumbnails are marked ready. Existing SVG covers or recordings with no thumbnail are marked pending, so the startup worker regenerates them after the migration and a configured FFmpeg executable are in place.

An authorized operator can retry a failed or intentionally regenerate an existing recording:

```http
POST /api/v1/recordings/{recordingId}/thumbnail/regenerate
Authorization: Bearer <operator token>
```

The endpoint returns `202 Accepted`; the existing image may remain available until the replacement JPEG is atomically published. Repeated requests while processing do not create duplicate work.

## Recording query API

```http
GET /api/v1/recordings
```

The response is `PagedRecordingResult` with `items`, `page`, `pageSize`, `totalCount`, `totalPages`, `hasPreviousPage`, and `hasNextPage`. The default page size is 24 and the enforced maximum is 100. No endpoint loads the complete archive.

Supported query parameters:

| Capability | Parameters |
| --- | --- |
| Device | repeat `deviceIds`; legacy singular `deviceId` remains accepted |
| Text | `search` (maximum 100 characters) across filename, client id, session id, and device name |
| Date | `dateFromUtc` inclusive, `dateToUtc` exclusive, or `exactDayUtc` |
| Hour | `hourFrom` inclusive (0–23), `hourTo` exclusive (1–24) |
| Source | `source=All|Motion|Manual|MonitoringSession`; legacy `trigger` remains accepted |
| Duration | `minimumDurationMilliseconds`, `maximumDurationMilliseconds` |
| Size | `minimumSizeBytes`, `maximumSizeBytes` |
| Processing | `uploadState=Completed`, `thumbnailState=Pending|Processing|Ready|Failed` |
| Sort | `Newest`, `Oldest`, `Longest`, `Shortest`, `Largest`, `Smallest`, `DeviceName`, `UploadTime`, `RecordingStartTime` |
| Page | `page`, `pageSize` |

All filtering, sorting, counting, projection, and paging run in the database. Sorts are enum allow-listed and always add deterministic secondary ordering by recording time and/or recording id. Projection joins device name in one query and does not issue per-recording queries.

## Time aggregation API

```http
GET /api/v1/recordings/time?level=Year
GET /api/v1/recordings/time?level=Month&parentStartUtc=...&parentEndUtc=...
```

Levels are `Year -> Month -> Week -> Day -> Hour`. Only the requested child level is aggregated. Each `RecordingTimeNode` contains a stable key, label, UTC start/end, count, total bytes, child flag, and next-level metadata. Database grouping returns buckets, not recording rows.

Weeks use ISO Monday boundaries. At the Month level, a week is clipped to its parent month's UTC start/end so a recording is not duplicated between month branches. The web formats node dates and hours in the browser's local time zone. Selecting a node applies its half-open UTC range to the recordings query.

Active device, search, source, duration, size, hour, date, and thumbnail filters also apply to time counts. Children are fetched only when expanded. The web remembers expansion keys for the browser session and sends one initial Year request.

## Web browsing behavior

- Device selection is a multi-select. Opening the library from a device details page preselects and explains that device scope while leaving every other filter available.
- Search, source, date/hour, duration, size, and thumbnail state appear as active removable chips with a clear-all action.
- Filter, time-range, sort, and page state use the URL so refresh and browser navigation restore the library. Sort and view preferences are also kept in local storage.
- Large Grid emphasizes 16:9 thumbnails, Compact Grid increases density, and List shows precise metadata. All modes use `RecordingItem` and the same play/download/delete/regenerate action model.
- Thumbnails use intersection-based lazy fetching plus native lazy image decoding. Playback never autoplays.
- Requests use abort signals and sequence guards so a stale response cannot overwrite newer filters.
- Desktop uses a sticky left time tree; smaller screens use the same tree in an accessible drawer.

## Performance and security

The migration adds indexes for recording/device time, upload time, thumbnail work discovery, trigger/time, duration/time, and size/time. Thumbnail responses use private one-day browser caching. Media remains behind operator authorization and database identity lookup; no physical path appears in a response.

The API enforces the operator `DeviceControl` policy, validates ranges and paging, allow-lists enums, parameterizes EF searches, and uses device access authorization before opening, regenerating, downloading, or deleting a recording. Device credentials can only access their existing upload endpoints. Tokens, FFmpeg stderr, absolute paths, and storage keys are not logged or returned.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `ffmpeg_not_configured` | packaged executable path is present in `RecordingStorage__FfmpegPath` |
| `ffmpeg_unavailable` | file exists, architecture is correct, and service identity can execute it |
| `ffmpeg_timeout` | input is readable/valid and timeout is appropriate for Local Hub hardware |
| `ffmpeg_failed` | MP4 can be decoded by the packaged FFmpeg build |
| `invalid_thumbnail_output` | packaging has not replaced/wrapped FFmpeg with an incompatible command |
| pending after restart | database migration is applied and the API worker is running |
| fallback remains | retry the recording after fixing the media dependency, then refresh the library |

Back up SQL Server and the configured recording root together. A media object without its row, or a row without its media object, is not a complete backup.
