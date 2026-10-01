# ADR 0002: Device as a shared platform domain

- Status: Accepted
- Date: 2026-07-31

## Decision

Treat Device as a durable platform domain across Android and server boundaries. Preserve the Android installation DeviceIdentity as the local stable identity and assign a distinct server Device ID during registration. Exchange versioned contracts rather than sharing runtime models or source dependencies between platforms.

## Context

SentriCam needs recording, monitoring, registration, snapshots, commands, and future remote experiences to refer to the same conceptual device. Android also needs a stable installation identity before any server exists. Collapsing local and server identifiers would make reset, migration, idempotency, and offline behavior unsafe.

## Consequences

- Registration can be idempotent by installation ID.
- Server persistence can use its own aggregate identifier without overwriting Android identity.
- Device capabilities and snapshots can evolve through explicit schema versions.
- Mapping code is required at platform boundaries.
- Pairing, users, and deployment hierarchy remain separate concepts.

## Alternatives

- **Use server Device ID as the Android identity:** rejected because an unregistered/offline installation would have no identity and forgetting registration could corrupt local ownership.
- **Use hardware identifiers:** rejected for privacy, privilege, portability, and reset semantics.
- **Share one compiled domain package across Kotlin and .NET:** rejected because it would couple languages and runtimes instead of stabilizing contracts.
