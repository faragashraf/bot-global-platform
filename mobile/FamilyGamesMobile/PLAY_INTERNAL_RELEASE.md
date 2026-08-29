# Lamma — Google Play internal release readiness

## Immutable Android identity and version

- Application ID: `com.botglobal.familygames`
- Version name: `0.1.0`
- Version code: `1`
- Minimum SDK: 23
- Target SDK: 36
- Compile SDK: 37

Confirm in Play Console that version code `1` has never been uploaded for this application ID before uploading the first bundle. If it has, increment the centralized `lamma-versionCode` value in `mobile/gradle/libs.versions.toml`.

## Release connectivity

Debug builds keep their isolated local endpoint override through `familyGamesDebugApiBaseUrl`. Release builds use the approved canonical Bot Global API base `https://bgapi.challengershoes.com`; the build validates that it is public HTTPS rather than localhost, an emulator address, or a private/LAN address.

HTTP APIs, invitation resolution, and the `/hubs/games` SignalR route are composed from the same normalized environment base. Release does not enable cleartext traffic.

### Android 6 TLS compatibility blocker

Runtime validation on 2026-08-29 found that the Huawei API 23 device cannot establish the deployed endpoint trust chain. The server presents `bgapi.challengershoes.com → YR1 → Root YR`, with Root YR issued by ISRG Root X1, while that device's system trust store has no ISRG Root X1. Samsung Android 12 connects successfully. Before including API 23 devices in Play testing, configure a publicly trusted server chain compatible with the supported Android range or review an explicit app trust-anchor policy; do not bypass certificate validation.

## Upload signing

No upload key or password is stored in Git. Create the upload key in an owner-controlled secure location; the command prompts for secret values rather than embedding them:

```bash
keytool -genkeypair -v \
  -keystore /secure/path/lamma-upload.jks \
  -alias lamma-upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

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

Notification and microphone permissions are not declared in this release because push-provider integration and WebRTC audio transport are not operational. Location and contacts are not declared or requested.

## Data Safety evidence

- Identity: guests send a display name; registered flows send display name, username, email, and password to the central identity API. Session access/refresh credentials are encrypted locally with a non-exportable Android Keystore key.
- Gameplay: the backend receives application-scoped identity, session/join/invitation actions, readiness, moves, and rematch commands.
- QR/camera: scanning is on demand. The QR contains a game invitation/deep link with an opaque invitation token, not account credentials.
- Notifications: shared semantic contracts exist, but FCM registration, push delivery, and a production notification provider are not configured.
- Microphone/voice: shared WebRTC/ICE/signaling contracts exist; Android audio transport and microphone access are not implemented.
- Location/contacts: shared capability contracts exist only; this application does not declare, request, or use them.
- Analytics/crash reporting/advertising identifiers: no analytics, crash-reporting, advertising SDK, or advertising-ID integration is present.
- Purchases: entitlement and billing-provider contracts exist, but no store product, purchase flow, or payment SDK is configured.

Server retention, deletion, encryption in transit/at rest, and third-party sharing answers require the owner/backend policy and must be confirmed in Play Console rather than inferred here.

## Store listing inventory

- Ready: approved adaptive/legacy/round launcher resources and high-resolution Lamma master artwork.
- Development evidence only: real-device launcher and application screenshots; these are not an approved Play screenshot set.
- Missing product/design approval: 512×512 Play icon export, 1024×500 feature graphic, final phone screenshots, short description, full description, support contact, and privacy-policy URL.

This checkpoint prepares Internal Testing only. It does not configure or authorize Production, Open Testing, or Closed Testing.
