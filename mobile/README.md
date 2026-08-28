# Bot Global Mobile

This directory is the shared Kotlin Multiplatform home for Bot Global mobile applications.

## Structure

```text
mobile/
├── shared/                         # Product-neutral capabilities and decision engines
└── FamilyGamesMobile/
    ├── composeApp/                 # Shared Family Games state, networking, and Compose UI
    └── androidApp/                 # Android application shell and platform integrations
```

`composeApp` targets Android, iOS device, iOS simulator, and JVM tests. The Android app is the first runtime target. The iOS framework exposes `MainViewController`; the native shell must inject a Keychain-backed `SessionVault` and platform haptics.

## Local Android build

```bash
cd mobile
ANDROID_HOME=/path/to/Android/sdk ./gradlew --no-configuration-cache \
  :shared:jvmTest \
  :FamilyGamesMobile:composeApp:desktopTest \
  :FamilyGamesMobile:androidApp:assembleDebug
```

The default emulator API URL is `http://10.0.2.2:5062`. A physical-device debug build can use an ADB reverse tunnel and:

```bash
./gradlew -PfamilyGamesDebugApiBaseUrl=http://127.0.0.1:5062 \
  :FamilyGamesMobile:androidApp:assembleDebug
```

Release builds use the approved public HTTPS Bot Global API base `https://bgapi.challengershoes.com`. Debug builds remain injectable through `familyGamesDebugApiBaseUrl` for local/LAN development.

Invitation links default to the development-safe `familygames://invite` scheme. Override both the server `FamilyGames:Invitations:DeepLinkBase` and mobile `familyGamesInvitationLinkBase` together only when a verified public HTTPS association is available.

## Platform capability status

- Identity: guest, login, registration, logout, refresh, restoration, and upgrade-compatible models are implemented against central backend identity.
- Secure storage: Android uses AES-GCM with a non-exportable Android Keystore key. iOS exposes the `SessionVault` injection boundary and requires a native Keychain adapter before an iOS shell ships.
- Localization/design: Arabic-first RTL and English LTR UI, centralized text, product-neutral shared capability types, and Family Games-specific tokens/components.
- Realtime/recovery: one Android SignalR lifecycle with bounded reconnect/rejoin, foreground revalidation, and authoritative REST recovery. Snapshot ordering uses match number plus move version so rematch version resets are valid while delayed prior-match events are discarded.
- Haptics: semantic Android implementation; UI never vibrates directly.
- Biometrics: shared contract and Android biometric/device-credential gate. It unlocks an existing session only; enablement preferences are a later slice.
- Permissions/location: centralized least-privilege contracts; Family Games does not request location.
- Invitations: shared locale-invariant link/message contracts, server-issued opaque tokens, Android QR scanning/rendering, incoming deep links, and the native system share sheet are implemented. Camera permission is requested only after the player selects scanning and confirms the explanation.
- Notifications: semantic inbox/push/foreground contracts are present. FCM/APNs product registration is intentionally not configured without production projects/credentials.
- Updates: shared decision engine plus server-owned Android/iOS version policy and required/optional UI.
- Entitlements/billing: semantic entitlement engine and provider boundaries; free classic XO is not blocked by billing.
- Voice: WebRTC/ICE/signaling contracts exist. Audio transport, native WebRTC adapters, and production TURN configuration are not implemented.

No Google service file, APNs key, signing credential, store product ID, price, or TURN secret belongs in source control.

## Locale-invariant game geometry

Arabic changes the surrounding application chrome to RTL, but XO coordinates never mirror. The board establishes a local LTR layout boundary and renders the server's row-major cells as `index = row * boardSize + column`. Automated presentation tests assert identical Arabic/English placement and winning-line geometry.
