# Connectivity and Self-Healing Foundation

SentriCam v0.5 separates capability state, health, recovery, heartbeat, presence, and UI refresh. Existing capability engines remain authoritative; the recovery coordinator observes their explicit state and never creates a second camera, recording, motion, Live, upload, or command pipeline.

## Recovery policies

| Subsystem | Owner | Automatic recovery | Safe terminal state |
| --- | --- | --- | --- |
| Realtime | `DeviceConnectivityService` / `SignalRConnectionManager` | Retry indefinitely with jittered bounded exponential backoff; republish capabilities, controls, and health after reconnect | Action required for revoked or missing credentials |
| Live | `LiveSessionManager` / Hub `LiveSessionEngine` | Preserve an active viewer session through a temporary device transport loss and renegotiate when the device reconnects | Failed after the bounded negotiation grace or when the viewer leaves |
| Recording | Existing recording engine | Finalize on lifecycle stop; restart the camera session for retryable camera failures; preserve completed segments and upload metadata | Action required for permission or storage failures |
| Upload | WorkManager upload worker | Persistent, network-constrained exponential retry; interrupted `UPLOADING` entries return to `RETRYING` after process restart | Degraded for terminal file/auth rejection; local file is retained |
| Motion | Existing motion engine | Restart the owning camera session for retryable analyzer/camera failures | Degraded for invalid configuration or unsupported hardware |
| Camera | Monitoring foreground service | Reopen indefinitely with bounded exponential backoff while monitoring remains desired | Action required when Android camera permission is missing |
| Command queue | Hub command repository and authenticated device connection | Keep undispatched commands pending and dispatch on reconnect until their explicit expiry | Timed out with an audited terminal result |
| Battery / storage | Android system providers | Refresh continuously | Degraded battery; action required for low/full storage |

The desired monitoring state is never cleared by a transient start failure. `BOOT_COMPLETED`, `START_STICKY`, and persisted pairing/monitoring/upload/recovery state restore operation after reboot or process death where Android permits foreground startup. Android 11+ does not grant while-in-use camera access to a foreground service started from boot, so the boot path restores Realtime, explicitly reports Camera and Motion as **Action required on device**, and defers camera ownership until the app is opened. Android 12+ and some OEM power managers can also deny a background foreground-service restart after process death; the same user-facing state and foreground retry path apply. The app never silently claims camera recovery after the OS rejected access.

## Health and observability

The Android heartbeat schema v2 includes independent Realtime, Live, Recording, Upload, Motion, Camera, Battery, Storage, and Command queue reports. Every report contains lifecycle, health, recovery reason, reconnect count, last failure, last recovery, and recovery duration. Overall health is derived with this precedence: action required, offline, degraded, recovering, healthy, unknown.

Health changes are sent immediately over the authenticated device connection. Heartbeats remain 15 seconds. Updated device clients opt in to a lightweight one-second transport pulse; the Hub declares transport loss after two seconds without changing the legacy SignalR timeout. This keeps older clients stable. Transport loss publishes **Recovering** immediately but does not change presence to Offline. A separate 25-second heartbeat timeout, checked by a one-second sweep, is the only transition from Recovering to **Offline**, producing a 25–26 second fallback detection window without flickering red for short hiccups. Health freshness is 30 seconds and uses Hub receipt time. Persisted heartbeat snapshots provide bounded health history. The browser applies Realtime events immediately (with a 120 ms coalescing window) and retains five-second polling only as a fallback.

When validated Android connectivity returns, any pending reconnect delay is canceled and exactly one immediate attempt is requested. A connected/registered device is Recovering until the Hub receives a fresh post-reconnect heartbeat or health report; historical Healthy state cannot complete recovery. If the network is healthy but the Hub is unavailable, the existing jittered 1, 2, 4, 8, then 16-second bounded backoff remains in force.

The operational state machine is `Healthy → Recovering → Offline`. A transport reconnect before the presence deadline follows `Healthy → Recovering → Healthy` and does not create an Offline transition. A reconnect after the deadline follows `Healthy → Recovering → Offline → Recovering → Healthy`. In both cases Healthy requires a fresh report received after the new connection was registered.

## Repeatable fault injection

Automated tests cover Hub/network interruption, Wi-Fi change, process restart, camera busy, upload interruption, recording interruption, command deferral, and storage full. Physical runs should use explicit targets and restore each fault before proceeding:

1. Hub restart: stop and restart the API process while the dashboard and device logs remain attached.
2. Wi-Fi loss/change: use Android system Wi-Fi controls or `adb -s <serial> shell svc wifi disable`, then restore with `adb -s <serial> shell svc wifi enable`.
3. Process death (debug APK): `adb -s <serial> shell am broadcast -a com.ashraffarag.sentricam.debug.KILL_PROCESS -n com.ashraffarag.sentricam/.device.recovery.RecoveryFaultInjectionReceiver`. The receiver requires the shell-only `android.permission.DUMP`, is absent from release builds, and lets `START_STICKY` recovery be observed without putting the package in Android's manually stopped state. Do not use `am force-stop` to assess automatic recovery: Android intentionally blocks restart until the user or another explicit launcher starts a force-stopped package.
4. Device reboot: `adb -s <serial> reboot`, wait for boot completion, and observe automatic service restoration.
5. Camera busy: open another camera owner during the controlled test and release it; verify the monitoring service reopens CameraX.
6. Upload failure: stop the Hub while a finalized segment is queued, then restore it and verify the same client recording id uploads once.
7. Storage full: use a controlled test volume or emulator image. Never fill a personal device blindly; verify the explicit action-required state and that recording stops safely.

Physical acceptance evidence must record device serial, fault start/recovery timestamps, resulting health transition, and whether manual interaction was required. Android restrictions such as a revoked camera permission are intentionally surfaced as **Action required on device**.

## Known boundary

Android process death can interrupt the container currently being written by CameraX. SentriCam preserves the local file and all previously finalized segments, restores monitoring, and never deletes the interrupted file. Guaranteed repair of a partially written MP4 container is a separate media-repair capability; v0.5 reports this as recording degradation instead of claiming a successful recording.
