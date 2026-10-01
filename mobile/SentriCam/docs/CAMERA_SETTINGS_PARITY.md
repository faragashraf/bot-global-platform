# Camera settings parity

This is the Camera Control Center V1 source of truth for Android camera settings and operational controls. The inventory was audited against `AppSettingsActivity`, every `settings_section_*.xml` control, the camera-control capability descriptor, and the Hub device-command surface.

## Classification

- **Writable** — the Hub can read and change the value through an existing validated command/capability path.
- **Read-only** — Android publishes the value or capability, but the Hub intentionally does not edit it in V1.
- **Requires Android interaction** — the setting is local because it grants permission, changes privacy/burn-in behavior, controls foreground-service policy, or performs a destructive identity action.
- **Deprecated** — debug-only, informational, or unsupported UI that is not a production setting.

The web application does not duplicate Android validation. `DefaultMotionSettingsCapabilityReporter` and `AndroidCameraCapabilityEngine` publish allowed values and bounds; `MotionSettingsCapabilityPolicy`, `CameraControlService`, and the current Motion engine validate a unified command when it executes.

## Motion Detection settings — complete V1 inventory

The Android `settings_section_motion.xml`, `AppSettingsActivity.bindMotion`, `MotionDetectionConfig`, `MotionSettingsPolicy`, `SharedPreferencesMotionSettingsRepository`, and the Motion recording coordinator are the source of truth. The current Android screen has exactly six interactive Motion settings; all six are classified and Hub-writable. None requires Android interaction or a CameraX restart.

| Contract identifier | Android control / meaning | Type and default | Options / validation | Persistence | Runtime effect | Camera restart | Parity |
|---|---|---|---|---|---|---|---|
| `motion.enabled` | Motion detection switch; arms or disarms frame analysis | Boolean; `false` | Boolean only | `motion_detection_v1_settings.enabled` | Starts/stops the existing Motion analyzer. Disabling prevents new detections but never silently stops an active recording. | No | Hub writable |
| `motion.sensitivity` | Detection sensitivity profile | Enum string; `medium` | `low`, `medium`, `high`, `advanced`; case-insensitive input normalized to the Android enum | `motion_detection_v1_settings.sensitivity` | Selects the existing threshold, changed-area, noise, brightness, and confirmation-frame profile. | No | Hub writable |
| `motion.advancedSensitivity` | Retained custom sensitivity slider, visible/editable only while `motion.sensitivity` is `advanced` | Integer; `50` | Inclusive `0–100`, step `1`; normalized by `MotionSensitivityPolicy` | `motion_detection_v1_settings.advanced_sensitivity` | Recomputes the existing Advanced analyzer profile only while Advanced is selected. Low/Medium/High ignore but retain this value. | No | Hub writable |
| `motion.triggerDelayMillis` | Time motion must remain confirmed before the event triggers | Integer milliseconds; `1000` | `0`, `1000`, `2000` | `motion_detection_v1_settings.trigger_delay` | Updates confirmation timing in the running Motion engine. | No | Hub writable |
| `motion.stopDelayMillis` | Hold time after the last detected motion before the event ends | Integer milliseconds; `10000` | Release: `5000`, `10000`, `20000`, `30000`; Debug APK additionally reports `3000` | `motion_detection_v1_settings.stop_delay` | Updates the existing hold/finalization policy. An already-active recording follows its safe finalization lifecycle. | No | Hub writable |
| `motion.cooldownMillis` | Minimum delay before a new Motion event can begin | Integer milliseconds; `5000` | `3000`, `5000`, `10000` | `motion_detection_v1_settings.cooldown` | Updates the existing coordinator cooldown. | No | Hub writable |

`motion.frameIntervalMillis` (`150 ms`) and `motion.warmupFrameCount` (`6` frames) are engine-managed, Hub read-only capability values. `MotionSensitivityPolicy.resolve` is the one authoritative selected-mode resolver: Low/Medium/High map to fixed detector profiles, while Advanced maps the retained `0–100` custom value to effective threshold, confirmation-frame count, noise tolerance, changed-area threshold, brightness tolerance, and confirmation behavior. The Android analyzer consumes that resolved profile on every analyzed frame. The device report returns the selected mode plus those effective parameters; the Hub displays them but does not duplicate their calculation.

Both Android and Hub hide the Advanced slider outside Advanced mode. Editing the retained custom value never changes the selected mode as a side effect. Preset → Advanced restores and applies the last valid custom value; Advanced → preset immediately selects the fixed preset profile. The retained value persists through app/device restart and is republished after Realtime reconnect.

Motion-triggered recording is the existing behavior of the Motion coordinator, not a separate user-configurable “trigger recording” switch. No Motion zones, schedules, AI/person recognition, analyzer mode, or segment-duration setting exists on the current Motion page, so none was invented for parity.

Every local or remote save updates the single SharedPreferences repository and increments its monotonic `settings_version` only when the effective normalized configuration changes. Remote commands carry `expectedVersion`; stale changes are rejected with `stale_motion_settings_version` instead of overwriting a local edit. Android reports the complete settings/capability snapshot after local changes, remote acknowledgements, restart, and Realtime reconnect. Event-driven `CameraControlChanged` updates are primary; five-second Dashboard polling is fallback.

## Android settings inventory

| Android setting | Classification | Hub equivalent or V1 boundary | Persistence / validation |
|---|---|---|---|
| Camera lens (rear/front) | Writable | Controls → Optics → Camera | Capability-reported lenses; synchronized with the recording settings repository |
| Date/time overlay | Writable | Controls → Date / Time Overlay | Server-persisted Camera Control setting; synchronized to Android, restored after reconnect, and burned into Live and recording frames using the phone wall clock/timezone |
| Recording quality profile | Writable | Controls → Video → Quality | Encoder capability descriptor; shared recording profile repository |
| Segment duration | Requires Android interaction | Local file-rotation and storage policy | Recording engine duration policy |
| Record audio | Requires Android interaction | Requires Android microphone permission and an explicit local capture choice | Recording settings plus runtime permission check |
| Motion detection enabled | Writable | Camera Control Center → Motion Detection | Shared Motion capability/repository/engine; independent of Monitoring Mode |
| Motion sensitivity preset | Writable | Camera Control Center → Motion Detection → Sensitivity | Shared `MotionSensitivityPolicy` and versioned Motion repository |
| Advanced Motion sensitivity | Writable | Camera Control Center → Motion Detection → Advanced sensitivity | Shared `MotionSensitivityPolicy`; integer 0–100 |
| Motion trigger delay | Writable | Camera Control Center → Motion Detection → Confirm motion after | Capability-reported options and versioned Motion repository |
| Motion stop/hold delay | Writable | Camera Control Center → Motion Detection → Continue after last motion | Capability-reported release/debug options and versioned Motion repository |
| Motion cooldown | Writable | Camera Control Center → Motion Detection → Cooldown before a new event | Capability-reported options and versioned Motion repository |
| Monitoring Mode enabled | Writable | Devices → Start Monitoring / Stop Monitoring; successful first pairing enables it automatically | Monitoring settings and the single foreground connectivity service |
| Restore monitoring after service/app restart | Requires Android interaction | Android foreground-service lifecycle policy | Automatically enabled for a newly paired camera and persisted |
| Foreground notification: motion | Requires Android interaction | Android notification-content choice | Monitoring settings repository |
| Foreground notification: recording | Requires Android interaction | Android notification-content choice | Monitoring settings repository |
| Foreground notification: battery | Requires Android interaction | Android notification-content choice | Monitoring settings repository |
| Foreground notification: storage | Requires Android interaction | Android notification-content choice | Monitoring settings repository |
| Device friendly name | Read-only | Hub receives and displays the registered name; remote rename is outside V1 | Local identity repository; 1–64 nonblank characters |
| Hub address / registration | Requires Android interaction | Integrated secure QR pairing is preferred; manual URL is Advanced setup | URL policy, QR protocol validation, encrypted credential storage |
| Forget registration | Requires Android interaction | Intentionally local destructive identity action | Clears credentials; never deletes recordings |
| Restart after unexpected failure | Deprecated | Development-only diagnostic switch; safe restart is enabled by first-pairing policy | Hidden in release builds |
| Verbose monitoring log | Deprecated | Development-only diagnostics | Hidden in release builds; secrets are excluded |
| Simulate storage warning | Deprecated | Development-only test control | Hidden in release builds |
| Smart Detection rows | Deprecated | Informational placeholder; AI is outside V1 | No production setting is persisted |

## Hub-only writable controls

These settings are intentionally managed from the Hub and are not duplicated as Android settings-screen editors.

| Hub control | Classification | Android authority |
|---|---|---|
| Zoom | Writable | CameraX capability bounds and persisted camera-control state |
| Torch | Writable | Active-lens flash capability; unsupported states return a structured result |
| Exposure compensation | Writable | CameraX exposure range |
| Resolution | Writable | Reported encoder/camera combinations |
| Frames per second | Writable | Reported frame-rate values |
| Bitrate | Writable | Reported bitrate bounds |
| Phone preview visibility | Writable | Visible, dimmed, or hidden without stopping remote video |
| Night profile | Writable | Reported profile values; unsupported hardware behavior remains explicit |

Start/Stop Recording and Start/Stop Live are operational commands, not durable settings. They use the existing recording and camera pipelines and report structured lifecycle state.

## Motion synchronization and conflict policy

Remote changes use the existing flow: Hub settings API → audited Camera Control command queue → authenticated device connection → `MotionSettingsCommandCameraControl` → `DeviceCommand.UpdateMotionConfig` → `MotionEngineCommandPort` → current Motion engine and versioned repository → structured result → device report → Dashboard refresh. Android-local saves use that same repository and republish the report; the settings screen refreshes an externally changed value when it has no unsaved local draft.

- Device offline: the existing command queue waits and retries; its expected version is checked again on Android, so a local offline edit wins over a stale queued command.
- Duplicate command: the existing command-id cache acknowledges the previous result without applying twice.
- Concurrent local/remote edits: version mismatch is a structured conflict; the operator refreshes and reapplies intentionally.
- Camera/analyzer unavailable: the current adapter returns a structured transient failure and the existing bounded retry policy applies.
- Live active: all six settings apply live without interrupting Live.
- Manual or remote recording active: settings apply without silently stopping the recorder.
- Motion-triggered recording active: disabling Motion stops new analysis; the owned recording continues through its normal safe finalization.

The parity gate `MotionSettingsCapabilityTest.everyInteractiveAndroidMotionControlHasOneParityClassification` compares every interactive switch, chip group, and slider in the actual Motion layout with `MotionSettingsParityInventory`. Adding an Android Motion control without one of **Hub writable**, **Hub read-only**, **Action required on device**, or **Deprecated** fails the Android test suite.

## Read-only capability and telemetry inventory

The Hub reads but does not edit: device ID, app/OS version, registered Hub/device identity, registration state and expiry, last successful connection, Realtime state, last heartbeat, reconnect attempts, monitoring state, recording state, motion state, available front/back cameras, focus modes, autofocus availability, manual-focus support, exposure-lock support, HDR/night-scene support, low-light boost, white balance, stabilization, battery, charging state, temperature, available storage, audio availability, and connection quality.

Unsupported capabilities remain **Read-only** and include a device-provided reason. The Hub never renders them as writable.

## Local operational actions

| Action | Classification | Reason |
|---|---|---|
| Scan Hub QR | Requires Android interaction | Camera must see the short-lived code during installation |
| Advanced manual Hub entry | Requires Android interaction | Recovery-only fallback for installations where QR scanning is impossible |
| Test Hub connection | Requires Android interaction | Advanced setup diagnostic |
| Force Realtime reconnect | Requires Android interaction | Local diagnostic action; normal reconnect is automatic |
| Open Android permissions/settings | Requires Android interaction | Android owns camera, microphone, notification, battery, and Date & Time settings |
| Open local recordings library | Requires Android interaction | Local-file inspection; remote recordings are available in the Hub library |

## Zero-touch boundary

After QR pairing and Android-required permissions, ordinary monitoring, Realtime, Live, recording, upload, camera control, reconnect, and restart recovery require no phone interaction. When Android blocks an operation, the camera screen shows **Action required on device** with the relevant system action. An unavailable Hub-time check is informational and never presents Date & Time settings unless drift or timezone mismatch has actually been confirmed.
