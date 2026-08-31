# Android platform

The Android application is the `:SentriCam:androidApp` module in the platform
mobile build. Open `mobile/` in Android Studio.

## Build

```bash
cd mobile
./gradlew :SentriCam:androidApp:testDebugUnitTest
./gradlew :SentriCam:androidApp:lintDebug
./gradlew :SentriCam:androidApp:assembleDebug
```

The application keeps its existing package and namespace: `com.ashraffarag.sentricam`.

## Capability areas

- Camera preview and camera ownership coordination
- Recording engine, segmented recording, playback, and recording library
- Motion detection
- Monitoring foreground service
- Unified settings
- Stable DeviceIdentity and device snapshots
- HTTP device registration and secure credentials
- Authenticated SignalR connection, heartbeat, and bounded reconnect foundation
- Persistent recording upload queue, checksum, progress, and bounded WorkManager retry

The monorepo move changes none of these behaviors.

## Server connectivity

The reusable registration layer separates configuration, HTTP transport, mapping, repository behavior, coordination/state, logging, and credential storage. UI code observes the coordinator and never performs HTTP directly.

Debug builds allow an explicitly configured HTTP Local Hub URL for LAN development. The single default debug value is defined by the Android module build and can be overridden with `-Psentricam.debugServerUrl=...`. Release builds accept only a configured HTTPS URL, hide unrestricted URL editing, and keep manifest cleartext disabled.

## Credentials

Device credentials are stored as AES-GCM ciphertext using a key held by Android Keystore on API 23+. Credential preferences are excluded from Android backup and device transfer. Settings show server Device ID and expiration metadata, never the raw token.

The monitoring foreground service owns the SignalR connection lifecycle. The reusable transport layer loads the JWT from secure storage, publishes the existing `DeviceSnapshot` every 15 seconds, waits while the device is offline, and uses five bounded exponential reconnect attempts. Settings observe connection state and metadata without receiving the JWT.

Completed recording segments enter a separate persistent WorkManager queue. Upload work is network-constrained, streams the MP4 with a SHA-256 checksum and progress, and uses the existing secure device credential. The recording library cannot delete the device copy until the queue stores a successful server receipt. See [Recording Upload & Local Storage](RECORDINGS.md).

## Boundaries

Android DeviceIdentity remains the local installation identity. The server Device ID is separate. Pairing, production user login, cloud sync, live view, and streaming are not part of the current Android platform.
