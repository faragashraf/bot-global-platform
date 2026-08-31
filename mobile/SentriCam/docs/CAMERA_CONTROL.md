# Camera Control Center V1

Camera Control Center establishes SentriCam's zero-touch camera boundary: after an Android phone is mounted, routine camera management is available to an authorized operator through one product API. The browser is the first client. A future SentriCam Admin app uses the same HTTP contracts and receives the same state updates.

## Capability flow

~~~text
Dashboard / future Admin app
        │ authorized HTTP command
        ▼
CameraControlController
        │
        ▼
CameraControlEngine ── atomic file store + audit history
        │ unified SignalR command envelope
        ▼
CameraControlService (Android serialized queue)
        │ validate against current capability report
        ▼
AndroidCameraXControl / RecordingCommandCameraControl
        │                         │
        │                         └── existing RecordingEngineCommandPort
        └── existing shared CameraX session
        │
        └── command result + updated device/upload report ──▶ Hub ──▶ all operators
~~~

There are no dashboard-specific camera commands. SignalR is a transport adapter; it does not contain camera rules. `CameraControlService` is the Android capability boundary. `AndroidCameraXControl` mutates the active CameraX controls, while `RecordingCommandCameraControl` adapts `recording=start|stop` to the existing recording command port. Neither path creates or binds a second camera or recording pipeline.

## Dynamic capability report

Android reads camera characteristics for the selected physical camera and reports each capability with:

- stable capability ID;
- supported state;
- writable or read-only state;
- current value;
- numeric minimum, maximum, step, and unit where applicable;
- allowed values where applicable;
- an honest reason when Android or the stable CameraX API prevents remote writes.

The report includes front/back lenses, torch and flash, zoom, exposure compensation and lock, focus modes, HDR/night/low-light support, white balance, stabilization, FPS, resolution, bitrate, quality, preview, microphone, battery, temperature, charging, and storage. The report is published at connection time, after command completion, and with the existing heartbeat cadence so the dashboard state stays current.

Recording reports normalize Android engine state to `Idle`, `Starting`, `Recording`, `Stopping`, or `Failed`, retain the recording owner (`remote`, `manual`, or `motion`), and include the latest persisted upload state. State-only updates use the existing `ReportCameraControlState` call and `CameraControlChanged` operator event.

Motion Detection settings are a nested, versioned capability report on the same camera-control state contract. The Hub renders a separate discoverable Motion Detection panel from device-reported values/options. Remote mutations remain Camera Control commands, are audited and idempotent, carry the Android settings version for optimistic concurrency, and execute through `MotionEngineCommandPort` against the existing Motion engine. Local Android changes update the same repository and republish the report; the Hub never maintains a competing Motion configuration or validation policy.

Hardware availability is never inferred from a model name. Unsupported controls remain visible as disabled, with the device-provided reason.

## Command lifecycle

Commands move through `Queued`, `Executing`, `Retrying`, and one terminal state: `Succeeded`, `Failed`, or `Canceled`.

The Hub:

1. authorizes the operator;
2. validates the requested value against the last device capability report;
3. persists and audits the queued command;
4. serializes dispatch with a per-device gate;
5. dispatches one unified envelope to the current authenticated device connection;
6. accepts results only from that same device;
7. retries transient failures up to three attempts;
8. persists desired settings only after device success;
9. publishes a `CameraControlChanged` notification.

Android has its own single-consumer queue and repeats capability validation immediately before CameraX execution. This protects against capabilities changing between report and execution. Cancellation is supported while a command is queued or executing. CameraX operations are short and are not interrupted in the middle of an OEM transaction; an executing cancellation waits for that boundary, restores the previous setting, acknowledges cancellation, and only then allows the next command to dispatch.

Remote Start is idempotent when the active session already belongs to the remote operator. A manual- or motion-owned session returns an explicit owner conflict; motion mode is never disabled as a side effect. Remote Stop cannot stop a manual- or motion-owned session. Start waits for CameraX to report `Recording`, and Stop waits for `Completed`/finalized metadata before the device reports command success. Transition timeouts are transient and safe to retry.

## Finalization and upload

Each successful MP4 segment is queued at the durable metadata-finalization boundary, not only when a transient session `Completed` state is observed. Queue rows are persisted in private application preferences and scheduled as unique, network-constrained WorkManager jobs. A boot scan restores completed V3 sidecars and interrupted queue items, while server preflight plus the per-device client recording ID and SHA-256 checksum make retries idempotent.

The upload worker reads the current encrypted Hub credential and the server derives DeviceId from the authenticated device claim; the installation ID is never substituted into upload metadata. Device credentials refresh at expiration, and retry-safe failures are rescheduled after registration. The Hub writes the storage object, verifies size/checksum, commits one recording row, and then exposes it through the existing Recordings library query. Android never deletes the local file in this workflow.

Legacy local recordings without finalized V3 sidecars do not contain the stable segment/session identity required for safe automatic upload. They are preserved in place. Recovering those older files is a separate recording-recovery feature and is intentionally not implemented here.

Diagnostics use stable event names (`recording_finalized`, `upload_queued`, `worker_scheduled`, `worker_started`, `upload_attempt`, `server_accepted`, `upload_succeeded`, `upload_failed`, and `retry_scheduled`) and identifiers only; tokens and secrets are never logged.

## Timestamp overlay

The server-controlled configuration independently selects enabled/disabled, date, time, 12/24-hour format, and one of four corners. It is part of the persisted Camera Control desired settings and is restored when Android reconnects. Android synchronizes the same configuration to its durable recording settings, burns it into CameraX `VIDEO_CAPTURE`, applies it to the local preview layer, and burns it into outgoing WebRTC I420 frames. The overlay uses `System.currentTimeMillis()` as user-visible wall clock time and resolves `ZoneId.systemDefault()` on every rendered second. Camera frame/media timestamps do not drive the printed clock. Diagnostic identifiers remain logs only and are never drawn on video. Server recording metadata continues to use UTC.

## Persistence and recovery

Hub desired state, command history, and bounded audit history live in `camera-control/state.json` below the configured Hub data root. Writes use a temporary file followed by atomic replacement. This remains database-provider independent and contains no tokens or credentials.

Android stores applied camera settings in private application preferences. On reconnect, Android reports its current settings. If they differ from the Hub's desired settings, the Hub queues one restore command. When the shared CameraX session attaches after app or device restart, Android reapplies zoom, torch, exposure, and preview state and recreates the existing session when lens, resolution, FPS, quality, bitrate, or profile requires it.

### Cleaning up commands created by the pre-fix HTTP 500

Earlier development builds could persist a command successfully and then return HTTP 500 while MVC tried to generate a nonexistent response route. Do not edit `camera-control/state.json` or the Hub database manually. Review the device's recent command activity and cancel each unwanted queued command through authenticated `DELETE /api/v1/camera-control/devices/{deviceId}/commands/{commandId}` requests. Reusing the original correlation ID is safe and returns the existing command instead of creating another one.

## Night profiles

V1 defines `Auto`, `Day`, `Night`, `Indoor`, and `Outdoor`. Profiles are portable intent, not an AI feature. CameraX applies supported exposure and torch policy, selects the effective frame cadence, and reconfigures the existing stream. OEM scene modes, noise reduction, and white-balance modes are reported but remain read-only where stable CameraX does not provide a portable writable control.

## Android platform limits

- Android does not permit remotely granting camera or microphone runtime permissions. They must already have been granted during installation/onboarding.
- Some manufacturers do not expose battery temperature; the dashboard reports Unavailable.
- Exposure lock, manual focus distance, white-balance selection, HDR scene selection, low-light boost, and explicit noise reduction are read-only when stable CameraX cannot safely apply them across supported Android versions.
- Android may restrict background camera startup after a force-stop or before the user has opened the app following installation. The existing foreground camera service remains the supported ownership model.

These limits do not create hidden fallback behavior. Capability responses state the actual device result.

## Security

- HTTP Camera Control endpoints require the operator `DeviceControl` policy.
- Device report and completion methods require the device policy and exact active connection ownership.
- Commands carry no access token, pairing secret, or Dashboard-specific payload.
- Control, correlation ID, actor, transition, outcome, and time are audited; secrets are not.
- One command modifies a camera at a time, preventing conflicting lens/rebind/control operations.

## V1 scope

V1 implements remote recording Start/Stop, zoom, torch, front/back lens, exposure compensation, visible/hidden/dimmed phone preview, FPS, resolution, bitrate, quality, and night-profile selection. It does not add AI, cloud transport, installer work, historical recording recovery, snapshots, notifications, or a separate camera/recording engine.
