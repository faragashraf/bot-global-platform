# Family Games Mobile and online XO

## Decision

Family Games is the first application inside the repository-owned Bot Global mobile platform. Kotlin Multiplatform and Compose Multiplatform were selected after validating the established `BotGlobalMobile` pattern. Product code uses a new `com.botglobal.familygames` namespace and contains no reference branding, credentials, endpoints, or business behavior.

The backend remains a modular monolith. `BotGlobal.Games` owns generic game sessions, player lifecycle, recovery, semantic notifications, and its capability-specific SignalR hub. XO owns only deterministic board rules and accepted move history. Capability modules do not reference one another; contracts and authenticated claims cross module boundaries through building blocks and API composition.

## Identity and isolation

ASP.NET Identity remains the global registered-user authority and password hasher. Mobile access uses random opaque access/refresh tokens; only SHA-256 hashes are stored. A mobile session belongs to an `ApplicationMembership`, which binds one global user or guest subject to one server-owned application key.

Family Games routes and policies supply or require `family-games`. Clients never submit an application identifier for authorization. Every game query includes the authenticated application claim, and session participants are identified by membership IDs. Guest upgrade preserves the application membership ID, but no unsupported business-state migration is invented.

## Authoritative XO sequence

```text
client command {commandId, coordinate, expectedVersion}
  -> authenticated Family Games membership
  -> participant + session/application validation
  -> duplicate command check
  -> expected version check
  -> deterministic XO engine
  -> SQL row-version / unique-command persistence
  -> authoritative snapshot
  -> SignalR group broadcast
```

The server rejects nonparticipants, wrong turns, invalid coordinates, occupied cells, stale versions, duplicate commands, post-completion moves, and concurrent writes. Clients render server snapshots and never publish optimistic winner/result state.

## Recovery

The Android session token is restored from Keystore-encrypted storage. Startup then asks for the membership's active persisted game. The shared coordinator reconnects SignalR, invokes `Rejoin`, replaces local state with the authoritative snapshot, and discards older realtime versions. The same sequence runs after bounded SignalR reconnect. UI-only state is not a recovery source.

## Reusable capability boundaries

- `mobile/shared`: identity/session vault, biometrics, semantic haptics, permissions, location, notifications/inbox, realtime lifecycle, update decisions, semantic entitlements, billing provider, and WebRTC/ICE voice-room contracts.
- `mobile/FamilyGamesMobile/composeApp`: Family Games navigation/state, API DTOs, game realtime adapter, localization, design system, and screens.
- `mobile/FamilyGamesMobile/androidApp`: Android process/activity, API build configuration, deep-link manifest, and native platform construction.
- iOS: the same framework compiles for device/simulator. Swift must provide the native Keychain/session and app shell integrations; APNs, StoreKit, permissions, and native WebRTC remain platform adapters rather than shared business code.

## External configuration boundaries

Production API URLs, Android/iOS signing, Firebase/APNs projects, store destinations/product IDs, and TURN servers are intentionally absent. Server version policy is configured per application/platform; stores are never scraped. Free classic XO has no billing dependency.
