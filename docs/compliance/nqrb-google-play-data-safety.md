# NQRB Google Play Data Safety draft

Last reviewed: 2026-09-14
App: NQRB
Package: `com.botglobal.nqrb`

This is an engineering draft based on the repository after the NQRB account-deletion compliance change. It is not legal advice. “Shared” follows the Google Play Data Safety concept, which can exclude qualifying service-provider processing; the publisher must confirm the applicable Google/Firebase and network-provider terms before selecting final Console answers.

Sending data to the first-party Bot Global backend is recorded below as collection, not third-party sharing.

| Play data type | Collected | Shared with third parties | Ephemeral | Required / optional | Verified purpose and qualification |
|---|---|---|---|---|---|
| Name | Yes | No for Bot Global backend | No | Required for signed-in account | App functionality; account management. Received through Google Sign-In, stored on the global identity/NQRB membership, and snapshotted in call participants. |
| Email address | Yes | No for Bot Global backend | No | Required for Google-based account | Account management; security. Received through Google Sign-In and stored on the global identity. |
| User IDs | Yes | No for Bot Global backend | No | Required | App functionality; account management; security. Includes Google subject, global user ID, NQRB membership/subject identifiers. |
| Contacts | No off-device collection | No | Yes, on-device runtime only | Optional | Android `READ_CONTACTS` is used to display device contacts locally. No NQRB request model or endpoint uploads them. This local access still needs accurate permission disclosure outside the Data Safety “collected” answer. |
| Audio / voice | Uncertain | Uncertain | Yes | Optional feature | App functionality. Live WebRTC audio leaves the device for the selected participant, but current code does not create a voice recording or send media to a Bot Global storage service. Confirm whether the deployed WebRTC encryption and user-directed transfer meet Play's exclusions, and confirm any configured TURN relay, before selecting the Console answer. |
| Device or other IDs | Yes | Uncertain for Google/Firebase | No | Required after sign-in; notification permission remains optional | App functionality; security. Random installation ID, platform device ID/credential, and FCM registration identifier are processed by Bot Global; Firebase also processes its registration identifier. Service-provider treatment must be confirmed. |
| App/device metadata | Yes | No for Bot Global backend; uncertain for Firebase/network providers | No | Required | App functionality; security. Platform, device name, and app version are sent during device enrollment. |
| App interactions / call activity | Yes | No for Bot Global backend | No | Required when calling is used | App functionality; account management. Call participant, direction, timing, outcome, duration, and media byte counts are stored. |
| Diagnostics | No explicit app collection verified | No explicit sharing verified | No | N/A | No Analytics, Crashlytics, or other crash-reporting SDK is present. Confirm production reverse-proxy/server log fields before final Console submission. |
| Approximate or precise location | No permission or location API collection verified | Uncertain for network-derived IP handling | Yes for connection routing if not logged | N/A | App code does not capture a location or IP field. Bot Global, STUN/TURN, and FCM infrastructure can observe network IP addresses; confirm production logging and Play interpretation. |
| Messages | No | No | No | N/A | No email, SMS/MMS, or in-app message content feature is implemented. Call signaling and push call-control events are treated as app functionality metadata, not user messages. |
| Photos or videos | No | No | No | N/A | No camera permission, QR scanner, image picker, or photo/video upload exists in NQRB. |
| Files and documents | No | No | No | N/A | No file/document access or upload is implemented. |
| Financial information | No | No | No | N/A | No payments or financial fields are implemented. |
| Health and fitness | No | No | No | N/A | No health/fitness fields or permissions are implemented. |
| Calendar | No | No | No | N/A | No calendar access is implemented. |
| Web browsing | No | No | No | N/A | No browsing-history collection is implemented. |

## Purpose selections supported by source

- **App functionality:** identity, sessions, device enrollment, FCM call notifications, contacts display, WebRTC calls, call history, and usage counters.
- **Account management:** Google identity, NQRB membership, sessions, account deletion.
- **Fraud prevention, security, and compliance:** credential hashes, session/device revocation, deletion retry state, and security lifecycle audit events while the device exists.
- **Analytics:** do not select based on current source.
- **Developer communications:** do not select based on current source.
- **Advertising or marketing:** do not select based on current source.
- **Personalization:** do not select based on current source.

## Account deletion disposition

| Data set | Post-deletion treatment |
|---|---|
| NQRB membership and mobile sessions | Revoke before the client treats deletion as accepted, then delete. |
| NQRB mobile devices, credential hashes, installation/device metadata, profile projection, pairing challenges, and device audit rows | Revoke device/push before the client treats deletion as accepted, then delete. Audit rows cascade with their device; no mandatory retention rule exists in source. |
| FCM registration | Invalidate before the client treats deletion as accepted, then delete from Pairing; delete queued/delivery recipient rows tied to the device. |
| Call usage periods/reports owned by the deleted membership | Delete. |
| Calls containing only the deleted membership | Delete. |
| Shared calls | Retain the other participant's call history; irreversibly replace the deleted membership and display-name snapshot, and delete the deleted participant's usage report. |
| Global ApplicationUser and Google login | Delete only when no other application membership remains. Otherwise retain for the other Bot Global application. |
| In-memory calling/realtime state | Block and disconnect; old sessions cannot reconnect after identity revocation. |
| Android local account state | Clear session/device secrets, FCM destination, pending usage outbox, cached contacts/directory/history, and stored call presentation after backend acceptance. |
| Deletion operation state | Temporarily retained only to resume incomplete module steps; removed when deletion completes. |

## Play Console decisions still required

1. Confirm that Google Sign-In and Firebase/FCM are used under terms that qualify them as service providers for Play's “shared” definition.
2. Confirm the deployed STUN/TURN configuration and whether TURN relays media; decide Play treatment of user-directed audio transfer and provider processing.
3. Confirm production proxy, hosting, and security-log handling of IP addresses and diagnostics. Source does not define those operational logs.
4. Approve the legal publisher/data-controller name and an official privacy/support email.
5. Confirm the 13+ target-audience configuration in Play Console.
6. Reconcile these answers with every active production build and SDK version before submission.
7. Confirm that broad `READ_CONTACTS` access qualifies under Google Play's current Contacts Permissions policy for NQRB's full on-device contacts list; otherwise replace that experience with the Android Contact Picker before submission.
8. Publish and verify the configured public privacy-policy URL. The app now links to the centralized production Pages URL from Settings.
9. Deploy and verify the Google-authenticated external deletion channel. Source now verifies the Google token against a separate NQRB Web audience before confirmation and deletion, but the backend audience environment value and Pages/backend deployment are still required.
10. `CALLING_HORIZONTAL_SCALE_CONFIRMATION_REQUIRED`: repository deployment/configuration documentation does not establish whether the production Calling runtime is single-instance or horizontally scaled. The in-memory call/session registries must not be described as horizontally coordinated until operations confirms the topology.

## Google Play Console Entry Guide

These are engineering recommendations for the build represented by this document. Recheck the shipped AAB and complete the unresolved legal/provider decisions above before entering final answers.

| Console question | Engineering recommendation |
|---|---|
| Does the app collect or share required user data types? | **Yes — collects.** Use the data-type rows above. First-party Bot Global backend processing is collection. Do not automatically mark it as third-party sharing. |
| Is all user data encrypted in transit? | **Yes for verified app transport:** production API traffic uses HTTPS and live WebRTC media is encrypted in transit. Operations must preserve TLS and verify the deployed TURN configuration. |
| Can users create an account? | **Yes.** NQRB creates/uses an NQRB membership after Google Sign-In. |
| Is account deletion available inside the app? | **Yes in this source.** The signed-in Profile flow has two confirmations and calls the authenticated NQRB-scoped deletion endpoint. Mark this only for a release that includes this change. |
| Account-deletion web URL | Intended URL: `https://faragashraf.github.io/bot-global-platform/nqrb/account-deletion/`. The source contains an owner-verified Google web flow. Publish the page/backend, set the Web audience environment value, and verify the complete production request before entering it. |
| Privacy-policy URL | Intended URL: `https://faragashraf.github.io/bot-global-platform/privacy/nqrb/`. The app links to this centralized URL. Publish and verify it before Console submission. |
| Name | Collected: **Yes**. Shared: **No for the first-party backend**; no other verified recipient. Required. Purposes: app functionality and account management. |
| Email address | Collected: **Yes**. Shared: **No for the first-party backend**; no other verified recipient. Required. Purposes: account management and security. |
| User IDs | Collected: **Yes**. Shared: **No for the first-party backend**; no other verified recipient. Required. Purposes: app functionality, account management, and security. |
| Contacts | Collected off device: **No**. Shared: **No**. Optional local permission. Separately resolve whether broad `READ_CONTACTS` is eligible under the Contacts Permissions policy. |
| Audio/voice | **Uncertain pending final Play interpretation and deployed TURN verification.** Live user-directed WebRTC audio leaves the device but is not recorded by NQRB. Do not select a definitive answer from this draft alone. |
| Device or other IDs | Collected: **Yes**. Sharing with Firebase: **Uncertain pending service-provider classification**. Required for signed-in device lifecycle; notifications remain permission-dependent. |
| App/device metadata | Collected: **Yes**. First-party sharing: **No**. Provider sharing: **Uncertain**. Required for app functionality/security. |
| App interactions/call activity | Collected: **Yes** when calling is used. Shared: **No for the first-party backend**. Purpose: app functionality. |
| Diagnostics | **No explicit SDK collection verified.** Confirm reverse-proxy/hosting logs before the final answer. |
| Location/IP | No location permission or explicit app field. Network-derived IP handling remains **uncertain** pending hosting/STUN/TURN operational review. |
| Messages, photos/videos, files/documents, financial, health/fitness, calendar, web browsing | **No verified collection** in current NQRB source. |
| Data deletion request URL works without the app | **Yes in source; deployment verification pending.** The page obtains a Google ID token with GIS, verifies it server-side using the explicit NQRB Web audience, confirms with the user, and invokes the same NQRB-scoped deletion orchestrator. Do not mark the Console requirement complete until the deployed flow passes an end-to-end test. |
