# ADR 0004: Durable integration events through an outbox

- Status: Accepted
- Date: 2026-07-31

## Decision

Use an application-owned outbox abstraction with infrastructure persistence as the future durable path for publishing domain-derived integration events. Keep event creation in domain/application workflows and keep dispatcher/transport selection outside those layers.

## Context

Future SignalR updates, notifications, cloud synchronization, analytics, and audit consumers may react to the same device changes. Publishing directly from request handlers or domain entities risks lost events, duplicated rules, and transaction gaps between SQL persistence and external transports.

## Consequences

- Business state and pending integration messages can be committed together.
- Future publishers can retry independently and apply idempotency.
- SignalR and cloud adapters do not become sources of domain truth.
- Outbox cleanup, ordering, poison-message handling, and observability require later operational design.
- The current foundation does not start a dispatcher or publish events.

## Alternatives

- **Publish directly from controllers/handlers:** rejected because persistence and publication cannot be made reliably atomic.
- **Put transport calls in domain entities:** rejected because the domain must remain infrastructure-independent.
- **Adopt a message broker immediately:** deferred because the current milestone needs the boundary, not a production broker choice.
