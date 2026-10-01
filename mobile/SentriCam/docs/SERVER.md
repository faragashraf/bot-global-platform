# SentriCam Server

The complete .NET 8 solution lives under `server/`. The current platform foundation implements the Device capability while reserving clean adapters and contracts for later milestones. The monorepo move changes no API, namespace, project reference, or runtime behavior.

## Dependency direction

```text
SentriCam.Domain
      ↑
SentriCam.Application ← SentriCam.Contracts / SentriCam.Shared
      ↑
SentriCam.Infrastructure
      ↑
SentriCam.Api

SentriCam.SignalR → SentriCam.Application
SentriCam.Tests   → tested projects
```

`SentriCam.Application` must never reference `SentriCam.Infrastructure`, `SentriCam.SignalR`, or `SentriCam.Api`. Assembly-level tests enforce these rules.

## Configuration

The first-run Hub setup owns the default configuration. Home mode generates the signing key, Hub secret, SQLite location, and recording paths, then writes secrets to the protected Hub secret document. Managed deployments can still provide external configuration through a deployment secret store or environment variables. The advanced environment variable names are:

```text
ConnectionStrings__SentriCam
Authentication__Jwt__Issuer
Authentication__Jwt__Audience
Authentication__Jwt__SigningKey
Authentication__Jwt__AccessTokenLifetimeMinutes
```

An externally supplied signing key must contain at least 32 characters. No connection password or signing key is checked into settings files.

## HTTP and SignalR entry points

- Health: `GET /health`
- Swagger UI in Development only: `GET /swagger`
- Device registration: `POST /api/v1/devices/registrations`
- Device hub: `/hubs/device`
- Device recording upload: `HEAD/POST /api/v1/recordings/uploads`
- Operator recording archive: `GET/DELETE /api/v1/recordings`

Registration is idempotent by stable installation id. A repeat registration refreshes the identity/capability advertisement and records a renewal without creating another Device.

The registration request accepts non-privileged Android metadata (installation id, manufacturer/model, Android/API version, app version/build), stable capability identifiers, client UTC time, and registration schema version. The response acknowledges the installation id, registration state/schema, access-token expiration, and `ServerUtcNow`. It reserves nullable refresh-token fields, but no refresh token is issued or persisted in v1.

## Local Android registration

For the normal local path, complete the Home wizard and scan the dashboard QR from Android. The Hub uses its private SQLite database and generated credentials; the user does not enter an address, port, or token.

Restore and build from the server root:

```bash
cd server
dotnet restore
dotnet build
```

For development without a packaged launcher, set an isolated Hub home and run:

```bash
export SentriCam__Home='<temporary-or-user-data-folder>'
ASPNETCORE_ENVIRONMENT='Development' ASPNETCORE_URLS='http://0.0.0.0:5173' \
  dotnet run --project src/SentriCam.Api/SentriCam.Api.csproj --no-launch-profile
```

Allow the selected Hub endpoint in the host firewall and keep phone and host on the same non-isolated LAN. Swagger remains available at `/swagger` only in Development; health is `/health`, direct registration is `/api/v1/devices/registrations`, and QR completion is `/api/v1/hub/pairing-sessions/complete`.

The debug app defaults to the emulator loopback bridge configured by the Android module build; use the Settings value or `-Psentricam.debugServerUrl=http://<host-lan-ip>:5173/` for physical devices. Release builds take only `-Psentricam.releaseServerUrl=https://...`, hide URL editing, reject HTTP in application policy, and keep manifest cleartext disabled.

The hub is a device-authenticated transport adapter only. It delegates connection, disconnection, and heartbeat calls to the Application-owned connection lifecycle service. It has no repository dependency and exposes no remote-command method in this phase.

Recording uploads are device-authenticated, streamed through `IRecordingStorageProvider`, verified by declared size and SHA-256, and persisted idempotently by device plus client recording id. Archive browsing, server-side filtering/sorting/paging, lazy time aggregation, range-enabled playback/download, asynchronous real-frame thumbnail access/regeneration, and deletion require the operator policy. The V1 local-folder adapter and media processor are configured under `RecordingStorage`; see [Recordings Library](RECORDINGS.md).

The in-process `DeviceConnectionManager` keeps one current transport connection per device and ignores a stale disconnect after a replacement connection is accepted. Application services persist connection lifecycle, online/offline state, and idempotent versioned snapshots through shared provider-neutral entities and repository abstractions. The default server client timeout is 30 seconds, with 15-second keep-alives.

Device-scoped hub operations authorize the requested device id against the authenticated device claim. Registration and hub connection establishment are rate limited, request sizes are bounded, and production hosts enable HSTS. Forwarded-header trust and production rate limits must be configured for the deployment topology.

## Evolution foundations

- A Device can optionally reference a DeviceGroup. Organization, Site, Area, and DeviceGroup form the future hierarchy, while registration remains hierarchy-independent.
- Device snapshots retain the legacy capture shape and add snapshot, schema, and generation-time version metadata.
- Aggregates collect typed domain events. Application-owned outbox and publisher contracts, plus Infrastructure outbox persistence, reserve a single future path for SignalR, notification, cloud, analytics, and audit consumers.
- No dispatcher is hosted and no integration publisher is registered in v1, so this phase does not publish events.
- Device commands have stable command/correlation ids, creation/expiry metadata, origin, retry-safe submission behavior, and a database uniqueness constraint for `(DeviceId, CorrelationId)`.
- Audit actor, source, severity, entry, and writer contracts are available without choosing an audit store.

## Database migrations

Setup applies provider-native migrations automatically. Developers can target the appropriate migration project from the server root; for example, SQLite uses:

```bash
cd server
dotnet tool restore
dotnet tool run dotnet-ef database update \
  --project src/SentriCam.Migrations.Sqlite/SentriCam.Migrations.Sqlite.csproj \
  --startup-project src/SentriCam.Api/SentriCam.Api.csproj \
  --context SentriCamDbContext
```

The initial migration creates Devices, DeviceCapabilities, DeviceRegistrations, DeviceConnections, DeviceSnapshots, DeviceCommands, DeviceEvents, DeviceStatusHistory, Organizations, Sites, Areas, DeviceGroups, and OutboxMessages. Later reviewed migrations add Remote Monitoring command results and recording metadata. `AddRecordingUploadLocalStorage` adds the `Recordings` table and its indexes without altering existing data.

## Validation

```bash
cd server
dotnet build
dotnet test
```

Cloud sync, notifications, live view/streaming, billing, and ML remain out of scope. v0.3.0 includes only the local QR pairing foundation.
