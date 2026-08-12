# Communication Persistence Architecture

## Status

This document defines the approved Communication domain and EF model before the
first Communication migration is created.

The current model is intentionally not wired into runtime persistence yet.

## Database boundary

Communication owns:

```text
schema: communication
migration history: communication.__EFMigrationsHistory
```

Communication does not create foreign keys to Identity tables.

Platform user ids are stored as `uniqueidentifier` values owned semantically by
Identity but referenced by value across the module boundary.

## Aggregate boundaries

### Conversation

`Conversation` owns participant membership.

Both direct and group communication use the same conversation abstraction.

Direct conversation:

- exactly two initial participants;
- both initial participants are Members;
- no third participant can be added;
- title is null;
- `DirectKey` is deterministic from the two sorted user ids.

Group conversation:

- requires a title;
- creator starts as Owner;
- participants may be added/rejoined;
- owner removal is blocked until ownership-transfer behavior exists.

The database cannot enforce every aggregate invariant. Direct participant count
and exactly-one-group-owner remain domain/application responsibilities.

### Message

`Message` is a separate aggregate from Conversation.

This avoids loading conversation message history into the Conversation aggregate.

Current creation behavior supports Text and Link messages. Media message kinds
are reserved in the persistence contract for later media-transfer metadata;
binary media remains outside SQL Server.

### MessageReceipt

Receipt uniqueness is:

```text
(MessageId, UserId)
```

Read implies delivered.

The model enforces timestamp ordering in both domain behavior and SQL check
constraints.

### UserCommunicationPreference

One row per platform user.

Defaults:

```text
AllowVoiceCalls = false
AllowVideoCalls = false
```

There is deliberately no FK to Identity.

### CallSession

V1 models one-to-one calls.

A CallSession records signaling/session history only; it does not store audio or
video.

## Direct-conversation uniqueness

A canonical key is generated from sorted platform user ids:

```text
{lower-guid-N}:{higher-guid-N}
```

The column is a normal persisted value, not a computed column.

SQL Server uses a unique filtered index:

```text
UX_Conversations_DirectKey
WHERE DirectKey IS NOT NULL
```

This allows unlimited Group conversations while preventing duplicate Direct
conversations for the same pair.

## Message idempotency

Clients provide `ClientMessageId`.

Uniqueness is:

```text
(SenderUserId, ClientMessageId)
```

This prevents a retry/reconnect from creating the same client message twice.

The server-generated `Message.Id` remains the canonical message identifier.

## Message ordering

Timestamp alone is not accepted as the canonical ordering mechanism.

`Messages.SequenceNumber` is a SQL Server identity-generated bigint.

Ordering inside a conversation is:

```text
ConversationId + SequenceNumber
```

The database enforces a unique index on this pair.

The sequence is globally generated but deterministic within each conversation.

## Tables

### Conversations

Important columns:

- Id
- Type
- Title
- DirectKey
- CreatedByUserId
- CreatedAtUtc
- LastActivityAtUtc

### ConversationParticipants

Composite primary key:

```text
(ConversationId, UserId)
```

Important columns:

- Role
- JoinedAtUtc
- LeftAtUtc

Active membership means `LeftAtUtc IS NULL`.

### Messages

Important columns:

- Id
- ConversationId
- SenderUserId
- ClientMessageId
- SequenceNumber
- Kind
- TextContent
- Url
- CreatedAtUtc

No media binary column exists.

### MessageReceipts

Composite primary key:

```text
(MessageId, UserId)
```

Columns:

- DeliveredAtUtc
- ReadAtUtc

### UserCommunicationPreferences

Primary key:

```text
UserId
```

Columns:

- AllowVoiceCalls
- AllowVideoCalls
- UpdatedAtUtc

### CallSessions

Important columns:

- Id
- ConversationId (optional)
- CallerUserId
- CalleeUserId
- ClientCallId
- Kind
- Status
- EndReason
- StartedAtUtc
- AnsweredAtUtc
- EndedAtUtc

## Delete behavior

Conversation -> Participants:

```text
Cascade
```

Participants are aggregate children.

Conversation -> Messages:

```text
Restrict
```

Message history must not disappear accidentally with a conversation record.

Message -> Receipts:

```text
Cascade
```

Receipts are message children.

Conversation -> CallSessions:

```text
Restrict
```

Call history remains explicit.

No conversation/message delete capability exists in the current product scope.

## Indexes

### Conversations

- unique filtered DirectKey
- LastActivityAtUtc

### ConversationParticipants

- active membership lookup by UserId (`LeftAtUtc IS NULL`)

### Messages

- unique `(ConversationId, SequenceNumber)`
- unique `(SenderUserId, ClientMessageId)`
- `(ConversationId, CreatedAtUtc)`

### MessageReceipts

- `(UserId, ReadAtUtc)`

### CallSessions

- unique `(CallerUserId, ClientCallId)`
- `(CalleeUserId, StartedAtUtc)`
- `(ConversationId, StartedAtUtc)`

## Deliberately deferred

The current model does not introduce:

- media attachment tables;
- server-side media binary storage;
- WebRTC data transport;
- TURN/STUN;
- FCM;
- message editing/deletion;
- conversation deletion;
- group ownership transfer;
- group-call topology;
- audit framework;
- row-version concurrency.

## Migration gate

Before creating the first migration:

1. Domain tests must pass.
2. EF model tests must pass.
3. Architecture tests must pass.
4. Build must pass.
5. Model must contain only `communication` schema objects.
6. No Identity FK may exist.
7. Direct-key and message-idempotency indexes must be verified.
8. Message sequence must be database generated.
9. Check constraints must be verified.
10. Documentation and EF model must agree.

Only after this gate should `InitialCommunication` be generated.

## External user identity boundary

Communication stores platform user identifiers as provider-agnostic strings.

SQL Server target type:

```text
nvarchar(128)
```

This supports Bot Global identifiers, corporate `nvarchar(20)` identifiers,
and future enterprise/OIDC subject identifiers.

Communication trims surrounding whitespace but does not lowercase identifiers.

There is no database FK to Bot Global Identity, Oracle user tables,
linked-server projections, Catalog, or Notifications.

`IPlatformUserDirectory` is the application boundary for user lookup and
active-state resolution. Its implementation is deployment-specific.

An internal deployment may use Oracle directly or an existing
SQL Server-to-Oracle linked server, but Oracle/linked-server access must remain
outside the Communication domain and EF model.

Independent Communication instances keep independent Communication databases.
They are additional deployments, not migrations or replacements of the public
Bot Global instance.

## Initial migration generation

`InitialCommunication` is generated from the approved Communication model.

The migration is generated through a Communication-owned design-time DbContext
factory.

The design-time factory configures:

```text
communication.__EFMigrationsHistory
```

The design-time connection string is a non-production placeholder used only so
EF tooling can construct the SQL Server model. Migration generation and SQL
scripting do not require a live database connection.

The generated SQL must pass static review before any runtime connection string
or database update is introduced.

The migration gate explicitly verifies:

- six Communication tables only;
- `communication` schema ownership;
- external user identifiers are `nvarchar(128)`;
- no Identity or Oracle foreign key/cross-database dependency;
- deterministic DirectKey uniqueness;
- message retry idempotency;
- database-generated message ordering;
- expected check constraints;
- no media-binary storage.

## Runtime persistence wiring

The Communication module uses one dedicated configuration key:

```text
ConnectionStrings:Communication
```

`CommunicationDbContext` is registered by the Communication module itself.

Runtime SQL Server options use:

```text
schema: communication
migration history: communication.__EFMigrationsHistory
```

The Communication connection string is deliberately independent from Identity,
Catalog, Notifications, and any default database connection.

Different deployments may point `ConnectionStrings:Communication` to different
SQL Server databases without changing Communication domain/application code.

Examples:

```text
Public Bot Global instance
    ConnectionStrings:Communication -> Public Communication DB

Internal organization instance
    ConnectionStrings:Communication -> Internal Communication DB
```

The internal instance may resolve platform users from Oracle through a separate
directory adapter, while Communication persistence remains SQL Server-owned and
independent.

The application fails fast during startup if the dedicated Communication
connection string is missing.

No migration is applied automatically by this registration.
