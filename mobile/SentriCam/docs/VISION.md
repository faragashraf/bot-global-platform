# SentriCam vision

SentriCam turns supported Android devices into privacy-conscious cameras that can record, detect motion, and participate in a managed monitoring system without making cloud connectivity mandatory.

## Principles

1. **Local-first operation.** Recording and monitoring must remain useful on the local device and local network.
2. **Stable device identity.** An installation has one durable identity; server identity is separate and assigned through registration.
3. **Explicit capability boundaries.** Implemented capabilities are advertised honestly and transport concerns do not own business rules.
4. **Secure defaults.** Credentials are protected, production transport uses HTTPS, and secrets stay outside source control.
5. **Optional expansion.** Local Hub, future cloud relay, and future web experiences build on the same domain rather than creating competing device models.
6. **Independent platform delivery.** Android, server, and future web developers can build within platform roots while sharing contracts and architecture documentation.

## Current platform

The repository contains the Android application and the SentriCam.Server foundation, including device registration. SignalR command delivery, pairing, user login, a web dashboard, cloud relay, and Smart Detection runtime remain future work unless a later milestone states otherwise.
