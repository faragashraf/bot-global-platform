# ADR 0005: SignalR as a thin adapter

- Status: Accepted
- Date: 2026-07-31

## Decision

Keep `SentriCam.SignalR` as a transport adapter that authenticates, authorizes, maps transport contracts, and delegates to Application services. Do not place device rules, command workflow, persistence, or Android runtime behavior in the hub.

## Context

Real-time connections are useful for future device presence, status, and commands, but connection technology will evolve and is subject to retries, reconnection, and hosting constraints. The same business operations must remain callable from HTTP, jobs, tests, and future automation.

## Consequences

- Application and Domain remain testable without SignalR.
- Commands retain stable IDs, expiry, and retry-safe semantics independent of transport.
- Future Android and web clients can use versioned contracts without server-layer leakage.
- Connection lifecycle and acknowledgement need explicit future client/server milestones.
- No Android SignalR runtime is implied by the existing server adapter foundation.

## Alternatives

- **Implement rules directly in hub methods:** rejected because it couples behavior to one transport and encourages duplication.
- **Reference SignalR from Application:** rejected because dependency direction would invert.
- **Avoid real-time transport entirely:** rejected as a permanent rule, but runtime implementation remains deferred until its lifecycle is designed.
