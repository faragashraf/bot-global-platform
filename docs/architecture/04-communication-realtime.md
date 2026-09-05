# Communication Realtime Architecture

## Ownership

Bot Global communication is implemented as a capability inside the modular
monolith:

`BotGlobal.Communication`

It is not a separate backend service.

## Control plane

`/hubs/communications` is the authenticated SignalR endpoint.

It will carry:

- presence;
- conversation routing;
- typing;
- message delivery/read events;
- media-transfer negotiation;
- call lifecycle;
- WebRTC offer/answer/ICE signaling.

## Data plane

Large media binary content does not travel through SignalR and is not stored
in the Bot Global database in V1.

Planned transport:

Device A <-> WebRTC <-> Device B

TURN may relay traffic where direct peer connectivity is impossible, but is
not durable media storage.

## Conversations

Direct and group messaging share one conversation abstraction.

A direct conversation is a conversation with two participants.

SignalR groups are transport routing only. Persistent conversation
participants remain the source of truth.

## Identity and security

- hub authentication is mandatory;
- sender identity comes from claims;
- clients cannot self-authorize into conversation groups;
- target-user and conversation authorization remain backend responsibilities.

## Calls

V1 targets one-to-one voice/video signaling.

Preferences:

- `AllowVoiceCalls`
- `AllowVideoCalls`

Group calling is deferred.

## Push

FCM is deferred and, if introduced, will be a push/wake transport only.
It will not become the media transport or canonical message store.

## Foundation safety

The current foundation uses safe-deny authorization until persistence-backed
conversation membership is implemented.

## Foundation identity and presence decision

The canonical realtime user identity is `HubConnectionContext.UserIdentifier`.

The Communication capability does not parse alternate identity claims or
accept a sender identity from client payloads. If SignalR cannot resolve a
user identifier for an authenticated connection, communication operations
fail closed.

Presence connection state is tracked internally by `UserConnectionTracker`,
but the foundation does not broadcast global online/offline events.

Presence audience authorization will be introduced with persisted
conversation participants. Only users with an authorized relationship will
be eligible to receive another user's presence state.

## Final foundation ownership and security decisions

### Module ownership

`BotGlobal.Communication` owns communication-specific realtime behavior,
including `CommunicationHub`, conversation routing contracts, typing,
receipts, presence policy, and call signaling.

`BotGlobal.Realtime` remains a generic technical capability placeholder.
Communication-specific hub behavior must not be moved there merely because
it uses SignalR.

`BotGlobal.Notifications` remains a separate notification/delivery capability.
It is not the owner of chat, conversation membership, or call signaling.

### Canonical realtime identity

`HubConnectionContext.UserIdentifier` is the single canonical realtime user
identity.

The Communication module does not parse alternate user-id claims and never
accepts a trusted sender user id from client payloads.

If SignalR cannot resolve the authenticated user identifier, the operation
fails closed.

### Presence privacy

`UserConnectionTracker` may track online connection state internally.

The foundation does not broadcast global presence.

Presence fan-out is deferred until persisted conversation participants or
another explicit authorization relationship can determine which users are
allowed to observe another user's presence.

### Foundation authorization posture

Until persistence exists:

- conversation access is denied;
- direct-user contact authorization is denied;
- voice-call receiving is disabled;
- video-call receiving is disabled.

This safe-deny posture is intentional and must remain until the persistence
slice introduces authoritative membership and preference data.

## Persistence model

The approved pre-migration Communication persistence design is documented in:

`docs/architecture/05-communication-persistence.md`

Realtime and persistence remain separate concerns inside the same Communication
capability. `CommunicationHub` does not own EF entities or database behavior.

## Realtime transport verification endpoint

A temporary authenticated HTTP endpoint is available **only in Development**
for local end-to-end transport verification. It is not mapped in Production,
Staging, or other environments. Cookie-authenticated requests also require the
platform antiforgery proof:

```text
POST /api/communication/test/send-to-user
```

Request:

```json
{
  "targetUserId": "<SignalR UserIdentifier>",
  "text": "hello"
}
```

The endpoint derives the sender identity from the authenticated HTTP principal.
Clients cannot provide a trusted sender id.

The endpoint calls `ICommunicationDelivery`, whose SignalR implementation uses:

```text
IHubContext<CommunicationHub, ICommunicationClient>
    -> Clients.User(targetUserId)
    -> RealtimeTestMessageReceived
```

This test event is intentionally non-persisted. Its purpose is to prove:

```text
Authenticated HTTP request
    -> Communication application boundary
    -> typed SignalR delivery
    -> target connected user
```

It is not the production chat-message endpoint. Persistent messaging is the next
capability after transport verification.
