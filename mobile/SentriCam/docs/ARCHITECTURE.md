# SentriCam architecture

## Runtime topology

~~~text
Android app ── registration / SignalR / recording upload ─┐
       ╲════════════ WebRTC Live View ═══════════ Browser │
                                                         ▼
Browser ── setup / local session / dashboard ──▶ SentriCam.Api
                                                         │
                       ┌─────────────────────────────────┤
                       ▼                                 ▼
             SentriCam.Application             SentriCam.SignalR
                       │
                       ▼
             SentriCam.Infrastructure
                       │
        ┌──────────────┼───────────────┐
        ▼              ▼               ▼
      SQLite       SQL Server      PostgreSQL
~~~

Android owns device-local camera, recording, motion, settings, identity, secure credentials, and its persistent upload queue. The Hub owns setup, server device identity, registration history, operator access, recording metadata/files, pairing sessions, and capability health.

## Server dependency direction

~~~text
SentriCam.Domain
      ↑
SentriCam.Application ← SentriCam.Contracts / SentriCam.Shared
      ↑
SentriCam.Infrastructure
      ↑
SentriCam.Api

SentriCam.SignalR → SentriCam.Application
Provider migration assemblies → SentriCam.Infrastructure
~~~

Controllers map HTTP to capability interfaces. HubSetupEngine validates and orchestrates setup. PairingEngine owns one-time pairing semantics. Infrastructure implements file persistence, provider selection, provisioning, media detection, storage probes, and LAN address discovery.

LiveSessionEngine owns the single-session lifecycle, connection-specific ownership, device availability, negotiation validation, statistics activity, and timeout rules. SignalR adapters relay only session control, SDP, ICE, state, statistics, capabilities, and errors. WebRTC media travels directly between Android and the browser; it never enters the Hub, controllers, repositories, or SignalR.

CameraControlEngine is the product-neutral camera-management capability used by the Dashboard and future Admin apps. It validates dynamic device capabilities, serializes and audits commands per camera, persists desired settings, and dispatches a single command envelope through SignalR. Android CameraControlService validates again, executes through the existing shared CameraX session, persists successful device settings, and reports updated state. Camera logic does not live in controllers, hubs, or Web components.

## Setup state boundary

Setup state deliberately lives outside the operational database so the Hub can start before that database exists or is reachable.

~~~text
FileHubSetupStore
  ├── public draft / explicit lifecycle state
  └── protected secret material
          │
          ├── bootstrap configuration for JWT/options
          └── live setup state for provider/storage selection
~~~

Draft saves are atomic. Secret values are redacted from response contracts. `isConfigured` is derived from `State == Completed && ConfiguredAtUtc != null`; it is not independently persisted. Secrets are committed first and the public `Completed` marker is written last, after folder preparation, connection verification, and migrations succeed. Startup only health-checks a completed state and moves unhealthy installations to `NeedsRepair`.

## Database provider abstraction

DatabaseProviderConfigurator is the only runtime provider switch. It configures one SentriCamDbContext; all repositories and business services remain unchanged.

Provider-native migration assemblies avoid running SQL Server column/filter SQL against SQLite or PostgreSQL. Portable providers use application-stamped concurrency bytes while SQL Server keeps native rowversion behavior.

## Web architecture

App is the product bootstrapper:

~~~text
GET setup
  ├── NotStarted → SetupWizard welcome
  ├── InProgress → resume saved wizard step
  ├── Failed / NeedsRepair → repair and retry
  └── Completed + timestamp → silent local session → Dashboard
                                      ├── HubOverview
                                      ├── Device components
                                      ├── Recording components
                                      ├── LiveViewPage → receive-only WebRTC subscriber
                                      └── CameraControlCenter → shared Camera Control API
~~~

Shared choice cards, fields, toggles, status cards, tokens, and responsive rules keep the wizard and dashboard consistent. Home filters technical steps from both navigation and rendered copy.

## Pairing security

Pairing codes are random, stored only as HMAC-derived keys in memory, expire after five minutes, and are consumed once. The QR contains discovery and authorization-to-pair, not a reusable access token. Normal registration creates the device credential, and Android stores that credential through its existing encrypted store.

The pairing code service is process-local in v0.3.0, which matches a single Local Hub process. A future clustered Enterprise host would replace it through the same capability boundary.

## Evolution boundaries

- Local Hub remains usable without cloud.
- Live View Foundation V1 is LAN-only, one-device/one-browser, and has no relay or recording path.
- Notifications, timeline, cloud, and AI remain outside this milestone.
- FFmpeg is a degradable Hub capability; it is not a startup prerequisite.
- Platform installers, service registration, firewall changes, and certificate trust are future packaging responsibilities.
- Business rules remain reusable from APIs, hosted services, SignalR, tests, and future automation.
