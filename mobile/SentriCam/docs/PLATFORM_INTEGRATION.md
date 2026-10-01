# Platform integration capability map

SentriCam was imported from standalone commit
`7691d01342cbd37b3a41ccea04cf3b8fe562dee9` on 2026-08-31. The standalone
repository remains the rollback reference and is not part of this Git tree.

## Existing shared

The Android application participates in the platform `mobile` Gradle build and
uses its plugin and dependency-management conventions. No runtime capability in
`mobile/shared` currently has semantics equivalent to SentriCam's product
contracts, so Phase 19 introduces no artificial shared dependency.

## Candidate shared

| Capability | SentriCam implementation | Decision |
| --- | --- | --- |
| Permissions | Activity and camera permission flow | Candidate after the platform has a reusable Android `PermissionController` implementation. |
| Localization | `AppLanguageController` and Android locale adapter | Candidate when shared localization supports persisted Android application locales. |
| Device installation identity | `DefaultDeviceIdentityRepository` | Candidate only if another product needs stable hardware metadata and migration semantics matching SentriCam. |
| Entitlement evaluation | SentriCam capability access and device overrides | Candidate after product entitlement and device-support semantics can be represented without loss. |

## SentriCam-specific

Camera capture/control, monitoring, motion detection, recording and upload,
pairing payload and fingerprint security, Hub device credentials, detailed
SignalR recovery, device snapshots, backend APIs and persistence, the web
dashboard, dynamic advertised Hub identity, and macOS Caddy/launchd deployment
remain owned by SentriCam.

## Deferred

Platform session identity does not represent a paired Hub device credential;
generic realtime state does not preserve SentriCam's validated/local-network and
recovery detail; generic notification inbox/push contracts do not represent the
monitoring foreground service; and the platform UI is Compose while SentriCam is
an established Views application. These remain separate until another consumer
and equivalence tests justify extraction.
