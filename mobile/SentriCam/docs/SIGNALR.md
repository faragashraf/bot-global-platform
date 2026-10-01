# SignalR connectivity foundation

SentriCam uses SignalR as a control-plane transport between a registered Android device, an authorized browser operator, and `SentriCam.Server`. The connectivity foundation establishes authenticated connections, publishes device heartbeats, and recovers from bounded transient failures. Remote Monitoring adds commands, while [Live View](LIVE_VIEW.md) adds WebRTC signaling and session lifecycle messages.

SignalR never transports video. Live media is a direct WebRTC flow from Android to the browser.

Camera Control also uses SignalR only as a control plane. The server sends one `CameraControlCommandEnvelope`; Android returns one result plus its updated capability/state report. Recording Start/Stop, zoom, torch, lens, exposure, preview, video-quality, and profile business rules remain in capability engines, not in SignalR handlers. Browser and future Admin clients submit commands through the same operator-authorized HTTP API.

## Runtime flow

```text
Encrypted device credentials
            │ JWT provider (never UI/logging)
            ▼
DeviceConnectivityService
            │ owns connectivity start / stop
            ▼
SignalRConnectionManager
            │ bounded reconnect + offline recovery
            ▼
Microsoft SignalR Java client
            │ /hubs/device
            ▼
DeviceHub [device authorization policy]
            │ delegates only
            ▼
DeviceConnectionLifecycleService
            ├── DeviceConnectionManager (current in-process connection state)
            └── SQL persistence abstractions (connection + snapshot lifecycle)
```

## Android boundary

`SignalRClient` hides the Microsoft Java client. `SignalRConnectionManager` owns the explicit state machine, access-token reuse, heartbeat loop, network recovery, cancellation, and client disposal. Neither Activity nor Settings performs SignalR calls. `DeviceConnectivityService` is the sole production owner of connection start and stop. Camera monitoring is a hosted capability with its own state and resource lifetime; stopping monitoring does not call `SignalRConnectionManager.stop()` or stop the service.

The connection states are `Stopped`, `Disconnected`, `Connecting`, `Connected`, `Reconnecting`, `AuthenticationFailed`, `ServerUnavailable`, and `Error`. Debug Settings can request a force reconnect only while the service owns a running manager.

Credentials are loaded from the existing Android Keystore-backed `DeviceCredentialStore` before every connection attempt. State, settings, and logs never contain the access token.

## Heartbeat

The first heartbeat is sent immediately after connection, followed by the default 15-second interval. It contains:

- Server Device ID
- SignalR connection ID
- Monotonic persisted snapshot version
- UTC timestamp
- The existing Android `DeviceSnapshot`

The server acknowledges device ID, connection ID, snapshot version, heartbeat time, and server UTC time. Duplicate snapshot versions are idempotent. The full existing Android snapshot is retained as bounded metadata while stable server fields update battery, storage, monitoring, recording, and online state.

## Reconnect policy

Transient connection, heartbeat, and server failures use five bounded retries at 1, 2, 4, 8, and 16 seconds. Offline periods do not consume retries; the manager waits for network restoration. Authentication, missing/expired credentials, invalid configuration, secure-storage failures, and protocol mismatches stop without an infinite retry loop.

The server uses a 30-second client timeout and 15-second keep-alive interval. A replacement connection atomically becomes current in the in-process registry, and a delayed disconnect from the previous transport cannot mark the replacement offline.

## Security and scope

- The hub requires the existing device JWT authorization policy.
- JWT bearer acquisition uses the supported SignalR access-token provider.
- Android debug builds may use the existing LAN HTTP policy; release remains HTTPS-only.
- Logs contain safe host, connection, retry, failure-code, and snapshot-version metadata only.
- The hub has no repository dependency and delegates to Application services.
- Live session methods enforce the exact operator and device connection owners and one active session.
- Camera Control reports and results enforce the exact authenticated active device connection; operator commands require the DeviceControl policy and are audited.
- SDP and ICE are bounded control payloads; contract tests reject video byte/stream payload types.
- Motion events, notifications, cloud upload, installer work, and historical recording recovery remain out of scope. Remote recording uses the existing local recorder and local-to-Hub upload path.

## Real-device acceptance — 2026-08-01

Samsung validation on the local Wi-Fi network confirmed:

- Device registration returned HTTP 200.
- SignalR negotiation returned HTTP 200 and the authenticated hub connection was established.
- The active device connection was inserted and repeated heartbeats were received.
- `DeviceSnapshot` rows were inserted and device/connection `LastSeenAtUtc` values advanced.
- Client disconnect was persisted with the safe `client_closed` reason.
- The phone reached the Mac-hosted server directly over the LAN; ADB reverse was not used.

Huawei SignalR validation remains deferred because that device was unavailable for the acceptance run.
