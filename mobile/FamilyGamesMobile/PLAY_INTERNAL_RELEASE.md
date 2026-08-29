# Lamma — Google Play internal release readiness

## Immutable Android identity and version

- Application ID: `com.botglobal.lamma`
- Version name: `0.1.0`
- Version code: `2`
- Minimum SDK: 23
- Target SDK: 36
- Compile SDK: 37

Version code `1` was used for the first Internal Testing upload. This corrected bundle uses version code `2` while retaining version name `0.1.0`.

## Release connectivity

Debug builds keep their isolated local endpoint override through `familyGamesDebugApiBaseUrl`. Release builds use the approved canonical Bot Global API base `https://bgapi.challengershoes.com`; the build validates that it is public HTTPS rather than localhost, an emulator address, or a private/LAN address.

HTTP APIs, invitation resolution, and the `/hubs/games` SignalR route are composed from the same normalized environment base. Release does not enable cleartext traffic.

### Android 6 TLS compatibility blocker

Runtime validation on 2026-08-29 found that the Huawei API 23 device cannot establish the deployed endpoint trust chain. The server presents `bgapi.challengershoes.com → YR1 → Root YR`, with Root YR issued by ISRG Root X1, while that device's system trust store has no ISRG Root X1. Samsung Android 12 connects successfully. Before including API 23 devices in Play testing, configure a publicly trusted server chain compatible with the supported Android range or review an explicit app trust-anchor policy; do not bypass certificate validation.

## Upload signing

The first Internal Testing bundle uses one dedicated Lamma upload key. The upload key is not the Google Play app-signing key: Google Play App Signing may manage the latter after enrollment. No keystore or password is stored in Git. Keep the upload keystore in an owner-controlled private location, keep its password in the operating-system credential store, and maintain a secure owner backup for future uploads.

Supply all four values using environment variables or the equivalent Gradle properties:

```text
LAMMA_UPLOAD_STORE_FILE        / familyGamesUploadStoreFile
LAMMA_UPLOAD_STORE_PASSWORD    / familyGamesUploadStorePassword
LAMMA_UPLOAD_KEY_ALIAS         / familyGamesUploadKeyAlias
LAMMA_UPLOAD_KEY_PASSWORD      / familyGamesUploadKeyPassword
```

Then build the owner-reviewed upload artifact:

```bash
cd mobile
./gradlew \
  :FamilyGamesMobile:androidApp:bundleRelease
```

Never commit the key, its passwords, or a local properties file.

## Permission findings

- `INTERNET`: identity, version policy, sessions, invitations, authoritative gameplay, and SignalR.
- `VIBRATE`: semantic XO and invitation haptics.
- `CAMERA`: QR invitation scanning. It is requested only after the player chooses scanning and confirms an in-app explanation.

- `ACCESS_NETWORK_STATE`: detects connectivity loss and recovery without polling private network details.
- `RECORD_AUDIO`: outgoing WebRTC voice after both players explicitly consent and the local player confirms the in-app microphone explanation. It is requested Just-In-Time, never at startup.
- `MODIFY_AUDIO_SETTINGS`: temporary hands-free communication routing while a voice session is active; prior audio state is restored during cleanup.

Notification, biometric/fingerprint, location, contacts, storage, SMS, phone, and advertising-ID permissions are not declared or requested. The unused biometric compatibility permissions contributed by the platform dependency are explicitly removed from the merged manifest. Push-provider integration is not configured in this Android application.

## Voice status for Internal Testing

Consent-gated, two-player WebRTC voice, mute/unmute, and cleanup are operational and have been proven on the current Samsung test devices with bidirectional RTP evidence. Voice remains **LIMITED** for this release: operational TURN and forced-relay testing are deferred, so some carrier, symmetric-NAT, restricted-Wi-Fi, or UDP-blocked environments may not establish media. Voice failure remains independent from gameplay.

## Firebase status

The Android application has no Firebase SDK/plugin dependency and no `google-services.json`; the release build does not require Firebase. Generic notification capability exists elsewhere in the platform, but Lamma Android push registration/delivery is not configured. If push notifications are enabled later, register a distinct Firebase Android app for `com.botglobal.lamma` and supply its approved configuration without committing private service-account material.

## Data Safety evidence

- Identity: guests send a display name; registered flows send display name, username, email, and password to the central identity API. Session access/refresh credentials are encrypted locally with a non-exportable Android Keystore key.
- Gameplay: the backend receives application-scoped identity, session/join/invitation actions, readiness, moves, and rematch commands.
- Notifications: shared semantic contracts exist, but this Android application has no notification permission, FCM configuration, registration, or push-delivery integration.
- Microphone/voice: after an explicit opponent request/accept flow and local Just-In-Time permission, microphone audio is transmitted to the other game participant through WebRTC. Audio frames are not carried by the Bot Global API or SignalR and are not recorded or stored by the application. Signaling and session/participant identifiers pass through the backend.
- Camera/QR: the camera is used only after the player chooses QR scanning and grants permission. Camera frames are processed for scanning and are not uploaded or stored; the resulting opaque invitation token—not account credentials—is sent to the backend for resolution.
- Location/contacts: shared capability contracts exist only; this application does not declare, request, or use them.
- Analytics/crash reporting/advertising identifiers: no analytics, crash-reporting, advertising SDK, or advertising-ID integration is present.
- Purchases: entitlement and billing-provider contracts exist, but no store product, purchase flow, or payment SDK is configured.

Server retention, deletion, encryption in transit/at rest, and third-party sharing answers require the owner/backend policy and must be confirmed in Play Console rather than inferred here.

## Content rating facts

- Lamma is an online multiplayer game platform currently launching with XO.
- Players interact with another real player in a game session and may see player-selected display names.
- There is no free-form text chat, image posting, public feed, or other general content-posting feature.
- Two-player voice communication exists only after an explicit in-game request and acceptance; either player can decline, mute, or leave.
- No advertising, purchases, gambling, simulated gambling, violence, sexual content, or controlled-substance content is implemented.
- These are implementation facts only. The owner must answer Google Play's questionnaire and select the resulting rating without treating this document as a rating decision.

## Internal Testing release notes

### English

First Lamma Internal Testing release: play online XO as a Guest, invite another player with QR codes or native sharing, use consent-based voice requests/chat, and recover from temporary connection loss. Includes Arabic and English. Voice is proven in the current test environment, but some carrier/NAT networks may remain unsupported until TURN relay rollout.

### Arabic

أول إصدار من لَمّة للاختبار الداخلي: العب XO عبر الإنترنت كضيف، وادعُ لاعبًا آخر عبر رمز QR أو المشاركة، واستخدم طلبات المحادثة الصوتية بعد موافقة الطرفين، مع استعادة الاتصال بعد الانقطاع المؤقت. يدعم العربية والإنجليزية. تم إثبات الصوت في بيئة الاختبار الحالية، وقد لا يعمل على بعض شبكات المحمول أو NAT حتى إضافة ترحيل TURN.

## Store listing inventory

- Ready: approved adaptive/legacy/round launcher resources and high-resolution Lamma master artwork.
- Development evidence only: real-device launcher and application screenshots; these are not an approved Play screenshot set.
- Missing product/design approval: 512×512 Play icon export, 1024×500 feature graphic, final phone screenshots, short description, full description, support contact, and privacy-policy URL.

This checkpoint prepares Internal Testing only. It does not configure or authorize Production, Open Testing, or Closed Testing.
