# ADR 0003: Local Hub as the default deployment model

- Status: Accepted
- Date: 2026-07-31

## Decision

Support a Local Hub in which SentriCam.Server runs on an operator-controlled laptop, Mac, or LAN host and Android devices connect locally. Core local recording, monitoring, and registration must not require a mandatory SentriCam cloud. Any cloud relay is future, optional, and opt-in.

## Context

Camera systems handle sensitive media and must remain useful when internet access is unavailable. The existing server and Android registration flow can operate on a LAN, while remote access introduces additional identity, authorization, relay, retention, and threat-model requirements.

## Consequences

- Local deployments retain operational control and can function without cloud dependency.
- Operators own Local Hub database availability, networking, backups, and secrets.
- Development may explicitly allow debug HTTP, while production still requires HTTPS.
- Remote access and discovery require a separate Cloud Mode design.
- Product experiences must communicate Local Hub reachability and offline state clearly.

## Alternatives

- **Mandatory cloud control plane:** rejected because it weakens offline operation and privacy goals.
- **Device-only operation with no server:** rejected because multi-device coordination and durable management need a server boundary.
- **Expose the Local Hub directly to the internet:** rejected because secure relay, identity, and network-edge controls require dedicated design.
