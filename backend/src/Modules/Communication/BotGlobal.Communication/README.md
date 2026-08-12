# BotGlobal.Communication

Owns Bot Global real-time user communication.

## Current foundation

- authenticated `CommunicationHub`;
- user connection tracking;
- conversation SignalR group naming;
- typing/presence contracts;
- text/link message contracts;
- delivered/read contracts;
- call preference contracts;
- voice/video signaling contracts.

## Security

The sender identity always comes from the authenticated connection.

Clients never provide a trusted sender user id.

Conversation group membership must be authorized by backend persistence.

The foundation deliberately denies conversation access until persistence exists.

## Media

Bot Global does not store image/video/voice/file binary content in V1.

Planned media flow:

Sender device -> WebRTC DataChannel -> Recipient device

SignalR is the control/signaling plane only.

## Calls

SignalR transports call state and WebRTC signaling only.

Actual voice/video media is planned through WebRTC.

Voice and video receive permissions are independent.

## Deferred

- persistence;
- conversation participants;
- text/link message persistence;
- delivery/read persistence;
- call sessions/preferences persistence;
- P2P media-transfer signaling;
- WebRTC client implementation;
- STUN/TURN;
- FCM;
- mobile code.

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
