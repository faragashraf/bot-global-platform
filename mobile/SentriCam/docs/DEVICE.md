# Device architecture

`Device` is a platform domain concept represented independently on Android and the server. The Android installation identity is stable and local; the server Device ID is assigned by registration. Neither replaces the other.

## Lifecycle

```text
Android installation
      │ creates or loads stable DeviceIdentity
      ▼
Unregistered
      │ registration request
      ▼
Registered ── activity/snapshots ──▶ Online / Monitoring / Recording
      │
      ├── credential expiry ──▶ Token expired ──▶ safe re-registration
      └── local forget ───────▶ Unregistered
```

Server lifecycle states and Android registration UI states serve different purposes. Transport failures must not overwrite the stable installation identity.

## Identity

Android DeviceIdentity includes a generated installation UUID plus non-privileged platform metadata. It excludes IMEI, privileged hardware serials, MAC address, phone number, and advertising ID.

Registration maps that identity to the server request. The server resolves uniqueness by installation ID and returns its own Device ID. Friendly name and mutable metadata may be refreshed while the installation ID remains immutable.

## Registration

Registration creates or refreshes the server Device, synchronizes advertised capabilities, records initial or renewal history, and returns an access token. See [Registration](REGISTRATION.md).

## Snapshot

A DeviceSnapshot is the versioned observation of current device state: identity, capabilities, connectivity, health, recording, motion, and monitoring information as supported by the current contracts. A snapshot is not the durable identity and must not become a command channel.

## Commands

Server command contracts use stable command and correlation IDs, origin, creation time, and expiry metadata. Submission is designed to be retry-safe. Actual Android remote-command delivery and execution are not implemented; a future transport must invoke the existing application/device command boundaries rather than embed business rules in SignalR.

## Monitoring

Android monitoring runs through a foreground service and coordinates camera ownership with interactive recording. Monitoring remains device-local today. A future remote control path must preserve foreground-service, permission, cancellation, and camera-ownership constraints.

## Capabilities

Devices advertise stable identifiers for implemented capabilities only. UI-only state and Coming Soon features are not advertised as supported hardware behavior. Capability metadata allows future server and web experiences to adapt without guessing from model names.
