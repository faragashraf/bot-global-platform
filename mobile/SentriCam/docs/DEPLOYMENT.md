# Deployment and local operation

v0.3.0 provides runtime setup and provisioning, not a platform installer. Development runs remain explicit; future MSI, PKG, and desktop packaging are tracked in [Installer Roadmap](INSTALLER_ROADMAP.md).

## Development Hub

~~~bash
cd server
dotnet tool restore
dotnet restore
dotnet build
dotnet test
dotnet run --project src/SentriCam.Api/SentriCam.Api.csproj --no-launch-profile
~~~

Without an existing configuration, the Hub listens on port 5173 and exposes the setup experience. For a guarded, isolated first-run acceptance profile, run:

~~~bash
cd /path/to/SentriCam
scripts/server/run-fresh-hub.sh
~~~

The script creates a new temporary directory and prints a restart command. It does not delete or overwrite existing Hub data. The `SentriCam__FreshInstall=true` guard works only in Development, only with an explicit empty temporary `SentriCam__Home`, and is rejected in Production.

The browser UI runs separately during development:

~~~bash
cd web
npm ci
npm test
npm run build
npm run dev
~~~

The API serves a compiled dashboard when index.html exists in its web root. A distribution pipeline should build web/ and place its output in the published API web root.

## Database providers

Home uses SQLite at the Hub home data/sentricam.db path. No live database service is required. Advanced modes can select SQL Server or PostgreSQL and test the connection before configuring.

Each provider has an independent migration history:

~~~text
SQL Server  → SentriCam.Infrastructure/Persistence/Migrations
SQLite      → SentriCam.Migrations.Sqlite/Migrations
PostgreSQL  → SentriCam.Migrations.PostgreSql/Migrations
~~~

The Hub runs provider-native migrations during the final configuration transaction. Later startup performs health checks only and does not repeat provisioning. Developers can still inspect or apply a specific migration assembly with dotnet ef; end users never run those commands.

## Advanced configuration overrides

Existing managed deployments can still supply:

~~~text
SentriCam__Home
SentriCam__Hub__AdvertisedUrl=https://hub.example:8443
SentriCam__UseLegacyConfiguration=true
Database__Provider
ConnectionStrings__SentriCam
Authentication__Jwt__Issuer
Authentication__Jwt__Audience
Authentication__Jwt__SigningKey
RecordingStorage__MaximumUploadBytes
RecordingStorage__ThumbnailWidth
RecordingStorage__ThumbnailHeight
RecordingStorage__ThumbnailJpegQuality
RecordingStorage__ThumbnailTimeoutSeconds
~~~

Legacy environment configuration is deliberately opt-in. A valid legacy SQL Server or PostgreSQL connection is mapped to Enterprise mode and must include a signing key of at least 32 characters. Incomplete legacy settings become `NeedsRepair`; their presence alone never marks a fresh Hub configured.

Do not commit runtime settings, passwords, certificates, or signing keys.

## HTTPS and network

Home keeps networking automatic and hidden. Advanced setup can select HTTPS, a port, and either a custom certificate or automatic certificate generation. Generated certificate material is protected under the Hub home and becomes active after restart.

The internal Kestrel listener and the externally advertised Hub URL are independent. Pairing advertises an explicit valid `SentriCam__Hub__AdvertisedUrl` when supplied; otherwise it prefers a dynamically discovered usable LAN IPv4 address and uses a safe hostname only when no usable LAN address exists. The dynamic form uses HTTPS on standard port 443 and never exposes Kestrel's internal port. On macOS, the Caddy deployment additionally serves the dynamically discovered Bonjour `LocalHostName.local` identity when available, while QR pairing continues to prefer the LAN IPv4 identity for Android compatibility. Neither identity is hardcoded.

Debug/local Android builds obtain and validate the current Hub's public Caddy Root CA into an ignored generated resource. Release builds do not reference that resource and retain normal system/public CA trust. The preparation tooling never copies Caddy private keys.

A full-tunnel VPN may route private LAN traffic away from the local Hub even when HTTPS and pairing configuration are correct. Disable the VPN while using a LAN-only Hub, or configure the VPN to allow/bypass local LAN traffic. SentriCam does not override the device's VPN routing policy.

## FFmpeg packaging

Detection checks, in order:

1. a selected advanced executable;
2. a packaged platform path under tools/ffmpeg;
3. the development host PATH.

A distribution must provide the licensed platform-specific executable in the packaged location and include license notices. Home users must not be sent to a terminal or third-party download page.

## Android

~~~bash
cd mobile
ANDROID_HOME=/path/to/android/sdk ./gradlew \
  :SentriCam:androidApp:testDebugUnitTest \
  :SentriCam:androidApp:lintDebug \
  :SentriCam:androidApp:assembleDebug
~~~

Debug builds allow the development LAN policy. QR deep links route through sentricam://pair. Release builds require HTTPS and keep credentials in encrypted storage.

## Backup and recovery

Back up the recording folder together with the selected database and Hub settings. Treat hub-secrets.json and generated certificates as sensitive recovery material. Restoring recordings without the matching database does not reconstruct the recording catalog in this milestone.
