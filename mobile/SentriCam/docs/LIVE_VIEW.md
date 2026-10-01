# Live View Foundation V1

Live View provides one low-latency, LAN-only video session from one registered Android camera to one authorized browser operator.

## Transport boundary

```text
Android CameraX ── I420 frames ── WebRTC publisher ═════ WebRTC ═════ Browser subscriber
                                          │                               │
                                          └──── SignalR signaling only ───┘
```

SignalR carries session creation/closure, SDP offer and answer, ICE candidates, device capability reports, connection state, statistics, preview visibility, errors, and timeout events. Its contracts contain no byte arrays, streams, encoded frames, or video chunks.

WebRTC uses host ICE candidates with an empty ICE-server list. V1 has no STUN dependency, TURN relay, Internet relay, cloud routing, or recording-over-live behavior.

## Session lifecycle

The authorized browser creates a session for one online device. The browser creates a receive-only offer, Android installs it and returns an answer, and both sides trickle ICE candidates through their authenticated SignalR connections.

The server owns the authoritative in-memory lifecycle:

```text
Connecting → Negotiating → Connected ↔ Buffering
     └──────────── failure / timeout / disconnect ────────────→ Closed
```

V1 allowed only one Hub-wide session. v0.6 replaces that global slot with one active session per device, allowing one authorized operator connection to own independent sessions for multiple devices. Every session remains bound to the exact operator subject and SignalR connection and the exact device identity and connection. A second viewer for the same device or a replaced/stale connection cannot control it. Negotiation and activity timeouts release each slot independently. See `MULTI_CAMERA_LIVE_WALL.md`.

## Android camera path

The publisher reuses the active CameraX `ImageAnalysis` pipeline when the Activity or monitoring service already owns it. A registered cold-start device temporarily acquires a service-owned CameraX lease for Live View and releases only that lease on stop. The shared frame source converts `YUV_420_888` planes to I420 for the native WebRTC video source; it does not duplicate or modify the recording engine.

The phone preview is a presentation surface. Every camera owner binds one CameraX `Preview` use case into its existing CameraX group. The `PreviewView` surface can attach when the Activity appears and detach when it leaves without creating another camera pipeline. Hiding it withdraws only the local Preview surface request; CameraX analysis and WebRTC publishing remain active. Showing it reissues the same `PreviewView` provider, preventing an OEM-cancelled TextureView request from returning as an overlay-only black preview. No camera rebind, restart, or renegotiation occurs. The timestamp/date view is layered over that live surface. Service-owned Live View uses the required foreground camera service type and displays an honest ongoing notification.

All Activity-, monitoring-service-, and Live-View-owned CameraX pipelines share one target-rotation policy. A physical-orientation listener supplies the authoritative CameraX target whenever it has a stable reading, regardless of whether an OEM exposes screen lock through Android's standard setting. Readings are quantized to 0°/90°/180°/270° with 10° hysteresis. Display rotation is used only during cold start or while the physical sensor reports an unknown orientation, such as a phone lying flat. Changes update Preview, VideoCapture, and ImageAnalysis target rotation without rebinding the camera.

The app supplies only this target rotation. CameraX applies camera sensor orientation and front/back lens-facing rules and exposes the resulting per-frame `ImageProxy.rotationDegrees`. The I420 converter leaves pixels in CameraX sensor-buffer order and carries that value into one WebRTC `VideoFrame` rotation field. There is no pixel rotation or second lens transform in SentriCam; WebRTC performs the rotation once and derives swapped display dimensions for 90°/270° frames.

CameraX mirrors the local front-camera preview for familiarity. ImageAnalysis frames are not mirrored, so the remote browser intentionally receives an upright, non-mirrored monitoring view. Rotation and horizontal mirroring remain separate policies.

## Capabilities and quality

Android reports front/back camera presence, torch, future zoom support, resolutions, and frame rates. Zoom is capability data only and has no V1 control.

The protocol reserves Auto, Low, Medium, and High profiles. Medium (1280×720 target at 30 fps) is the only selectable V1 profile. Other profiles are displayed as prepared but unavailable.

## Browser behavior

The reusable Live View page exposes Start Live, Stop Live, Fullscreen, device selection, preview visibility, state, errors, and receive statistics. The browser creates a receive-only `RTCPeerConnection`, queues early Android candidates until the answer is installed, and closes local media resources even if server closure fails.

## Foundation limits retained by v0.6

- One active publisher/viewer session per Android device.
- Multiple devices may stream independently to one authorized Live Wall viewer.
- Local network only.
- Video only; microphone/audio is not published.
- No TURN, cloud, recording, timeline, or notifications.
