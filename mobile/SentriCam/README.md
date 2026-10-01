# SentriCam

SentriCam is a local-first camera monitoring product. Android phones provide recording, motion detection, monitoring, and secure device identity. SentriCam Hub keeps device state and recordings on a computer in the home or office, and the browser dashboard provides setup, pairing, health, device control, and the local recording archive.

Live View Foundation adds one low-latency Android-to-browser WebRTC stream over the local network. SignalR coordinates the authorized session and negotiation but never carries video. See [Live View](docs/LIVE_VIEW.md).

Camera Control Center V1 adds the zero-touch camera capability. An authorized operator can manage supported CameraX settings from the Hub while Android dynamically reports the real device ranges and persists successful settings across reconnects and restarts. The browser and future Admin apps share one API and unified SignalR command pipeline. See [Camera Control Center](docs/CAMERA_CONTROL.md).

## SentriCam Hub experience

v0.3.0 replaces infrastructure-first startup with a product-first journey:

~~~text
Install Hub → Welcome → Home / Office / Enterprise
            → Name → Recording folder → Storage policy
            → Configure automatically → Hub Ready → Scan QR → Done
~~~

Home is the default. It creates a private built-in SQLite data store, applies migrations, generates signing keys and Hub secrets, prepares folders, detects the media engine, and opens the dashboard. The Home UI never asks for a database provider, connection string, JWT, migration, or port.

Office and Enterprise expose optional provider, connection-test, HTTPS, certificate, networking, and advanced-storage controls. All providers use the same domain model, SentriCamDbContext, repositories, and application services.

This milestone builds the complete first-run and Hub product experience. It deliberately does not produce MSI, PKG, or EXE installers. See [Setup Experience](docs/SETUP_EXPERIENCE.md) and [Installer Roadmap](docs/INSTALLER_ROADMAP.md).

## Repository layout

~~~text
SentriCam/
├── androidApp/ # Android app, secure registration, and QR pairing deep link
├── server/    # .NET 8 API, setup engines, persistence, migrations, tests
├── web/       # React first-run wizard and Local Hub dashboard
├── docs/      # Product, architecture, operations, and ADRs
└── scripts/   # Platform validation entry points
~~~

## Run the development experience

Start the Hub:

~~~bash
cd server
dotnet restore
dotnet run --project src/SentriCam.Api/SentriCam.Api.csproj --no-launch-profile
~~~

Start the web UI:

~~~bash
cd web
npm ci
npm run dev
~~~

Open http://localhost:4173. The development proxy connects to the Hub at http://localhost:5173. A fresh Hub opens the setup wizard; a configured Hub silently creates a local browser session and opens the overview.

The runtime state defaults to server/src/SentriCam.Api/data/hub/ and is Git-ignored. Set SentriCam__Home to use an isolated location.

For a guarded full first-run acceptance pass that cannot touch an existing Hub profile, run `scripts/server/run-fresh-hub.sh` from the repository root. It creates a new temporary Development profile and prints the restart command used to verify that completion persists.

## Validate

~~~bash
cd server
dotnet build
dotnet test

cd ../web
npm test
npm run build

cd ..
./gradlew :SentriCam:androidApp:testDebugUnitTest \
  :SentriCam:androidApp:lintDebug \
  :SentriCam:androidApp:assembleDebug
~~~

See [Deployment](docs/DEPLOYMENT.md) for SDK and environment details.

## Local-first boundary

Core operation does not require SentriCam cloud. Live View V1 is LAN-only and uses no relay. A future cloud relay may extend remote access, but it must not replace local operation or silently move recordings off the Hub. Notifications, timeline, cloud, and AI remain outside this milestone.

Start with the [documentation index](docs/README.md), [Local Hub](docs/LOCAL_HUB.md), and [Architecture](docs/ARCHITECTURE.md).
