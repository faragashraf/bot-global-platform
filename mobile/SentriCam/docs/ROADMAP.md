# SentriCam roadmap

This document separates shipped foundations from future capability areas. It is directional and does not replace milestone-specific scope or validation.

## Established foundations

- Android recording engine and recording library
- Motion detection and monitoring foreground service
- Unified Android settings and stable DeviceIdentity
- SentriCam.Server domain/application/infrastructure/API foundation
- SQL Server persistence model, JWT foundation, and SignalR server adapter foundation
- Idempotent Android-to-server device registration with secure local credentials
- Independent `android/` and `server/` build roots
- Authenticated SignalR connectivity, Remote Monitoring V1, and operator dashboard
- Recording Upload & Local Storage V1 with a persistent Android queue and provider-backed Hub archive
- Local Hub first-run wizard, Home/Office/Enterprise modes, automatic SQLite provisioning, provider-native migrations, capability status, and QR pairing foundation
- Multi-Camera Live Wall v0.6.0 with per-device session ownership, connection-scoped readiness, shared action eligibility, group recording controls, server-controlled Date/Time Overlay, and correlated Live diagnostics

## Candidate future milestones

1. Package the Hub into signed MSI, PKG, and later supported platform installers.
2. Validate physical-device QR discovery and certificate trust across supported home-network topologies.
3. Design optional cloud relay and remote access without weakening local operation.
4. Plan later recording experiences without conflating archive playback with live streaming.
5. Implement Smart Detection only after privacy, device capability, and model-delivery decisions are approved.

Installer packaging, cloud, live view, notifications, and Smart Detection are not part of v0.3.0.
