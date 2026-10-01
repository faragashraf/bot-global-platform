# UX debt

This file records intentional, bounded UX compromises so they are not mistaken for finished product decisions.

## Camera Control Center V1

- The new Camera Control Center has complete scoped light and dark themes. The older Hub header and existing pages remain on their established dark presentation because this milestone explicitly avoids redesigning existing screens. A later shared-shell theme milestone should promote the control-center theme tokens to the full product shell.
- Commands display queued/executing/retrying activity and product-safe result codes. A future activity drawer can expose the complete bounded audit history without changing the shared API or command engine.
- Unsupported OEM camera controls are shown as disabled capability rows. V1 does not provide device-specific instructions because support varies by installed Android/CameraX implementation.
- Temperature shows Unavailable on devices that do not expose battery temperature. No inferred temperature is displayed.
- Upload status shows the latest persisted Android queue item only. It does not aggregate historical failures or offer a recovery action for legacy recordings without finalized V3 sidecars; those files remain preserved for a separate recording-recovery capability.
- Start and Stop wait for Android recording transitions, but the dashboard intentionally shows only the five operator states (`Idle`, `Starting`, `Recording`, `Stopping`, `Failed`) instead of exposing every internal preparation/segment-rotation state.
- The Android camera screen's Development-only Realtime detail expands the safe Hub host, reconnect attempt, and stable failure code. Full exception cause chains remain in debug logcat so the compact camera overlay never exposes payloads or occupies the preview with stack traces.
- Live View V1 exchanges host ICE candidates only and intentionally has no relay. A host VPN that captures the camera's LAN subnet can therefore allow SignalR signaling while blocking browser-to-phone WebRTC media. The Live page reports this as a distinct LAN media-path error with VPN guidance; automatic route changes or a TURN relay remain outside this local-only milestone.
- The integrated pairing scanner temporarily owns the phone camera while its dedicated screen is visible, then releases it before Monitoring starts. A future onboarding visual pass may add an animated scan guide; V1 keeps the scanner functional, local, and independent of Google Play services for Huawei compatibility.
- First-run clock validation compares the phone with the Hub using the current wall clock and timezone offset. Android does not allow an ordinary app to correct system time, so a severe mismatch remains an explicit on-device action with a direct Date & Time settings shortcut.
- Settings that affect privacy, permissions, or local service policy remain Android-only in V1 (timestamp burn-in, microphone capture, segment duration, notification detail, auto-start policy, and diagnostics). Motion tuning now has full Hub parity. The complete classification and remote parity boundary are recorded in `CAMERA_SETTINGS_PARITY.md`.
- The compact camera screen keeps Realtime, Live, and Recording visible over the preview and moves the recordings library into the existing action overflow. A later dedicated UX milestone may replace the current XML overlay composition with a fully adaptive compact/expanded component system; this pass deliberately avoids redesigning the established camera workflow.
