# Backend Boundaries

Architecture style: Modular Monolith.

## Core rules

1. Build by capability.
2. No generic mega-service.
3. `BotGlobal.Api` is the composition root, not the business layer.
4. Capability modules do not reference other capability modules directly.
5. Capability modules may reference approved building blocks such as `BotGlobal.SharedKernel` and `BotGlobal.Contracts`.
6. `BotGlobal.SharedKernel` stays minimal.
7. `BotGlobal.Contracts` contains cross-module integration contracts only.
8. Reusable realtime infrastructure may live in `BotGlobal.Realtime`, but capability-specific hubs and realtime behavior belong to the capability that owns the behavior.
9. `BotGlobal.Notifications` owns notification persistence, targeting, and notification delivery. It does not own chat, conversation membership, presence policy, or call signaling.
10. SQL Server persistence is introduced behind module boundaries. Each persistence-owning capability owns its database schema and EF migration history.
11. The API may reference modules for composition and endpoint/hub registration, but business rules must remain inside modules.

## Capability module dependencies

Allowed:

```text
Capability Module
    -> BotGlobal.SharedKernel
    -> BotGlobal.Contracts
```

Forbidden:

```text
Capability Module A
    -> Capability Module B
```

Cross-capability collaboration must happen through integration contracts rather than direct project references.

## Realtime ownership

`BotGlobal.Realtime` is a generic technical capability only.

It may eventually own reusable infrastructure such as:

- shared SignalR conventions;
- serialization defaults;
- transport diagnostics;
- genuinely reusable connection plumbing.

It must not become the owner of business-specific realtime behavior merely because
that behavior uses SignalR.

Examples:

- `CommunicationHub` belongs to `BotGlobal.Communication`.
- Conversation typing, receipts, presence policy, and call signaling belong to `BotGlobal.Communication`.
- A future capability-specific hub belongs to the capability that owns its semantics.

## Notification ownership

`BotGlobal.Notifications` is separate from Communication.

It owns:

- notification persistence;
- notification targeting;
- notification delivery transports;
- notification read/unread state if introduced.

It does not own:

- conversations;
- chat messages;
- conversation participants;
- typing;
- communication presence policy;
- voice/video call signaling.

A communication event may later produce a notification through an integration contract,
but Communication and Notifications remain separate module owners.

## Communication ownership

`BotGlobal.Communication` owns:

- authenticated `CommunicationHub`;
- direct/group conversation semantics;
- participant authorization;
- message and receipt semantics;
- communication presence policy;
- call receive preferences;
- call signaling;
- communication-specific realtime contracts.

Media binary content is intentionally not persisted in SQL Server in V1.
Communication may persist media metadata while device-to-device transport remains
outside the database.

## Persistence ownership

A module that owns persistence must:

- use its own `DbContext`;
- use its own SQL Server schema;
- use its own migration history table;
- configure entities inside the module;
- avoid direct EF entity sharing across capability boundaries.

For Communication, the planned database boundary is:

```text
schema: communication
migration history: communication.__EFMigrationsHistory
```

No Communication migration should be created until the domain model, relationships,
constraints, indexes, and EF model tests are approved.

## Extractable deployment boundary

Communication may be hosted in `BotGlobal.Api` and later in an additional
independent internal host.

Its persistence must not require cross-database joins to Identity, Oracle,
Catalog, Realtime, or Notifications.

External users are referenced by provider-agnostic string identifiers.
Deployment-specific directory access belongs behind an adapter.
