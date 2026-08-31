# Multi-Camera Live Wall v0.6.0

## Release closure

Status: completed on 2026-08-03.

The v0.6.0 capability closes with multi-camera Live Wall orchestration, per-device Live session ownership, connection-scoped readiness, shared state-aware action eligibility, partial-success group recording controls, Android reconnect and lifecycle diagnostics, consistent Motion presentation, a server-controlled Date/Time Overlay for Live and recorded video, and correlated diagnostics that never enter video pixels.

Automated release validation passed:

- Android: `./gradlew testDebugUnitTest assembleDebug`;
- Server: `dotnet build SentriCam.Server.sln` with zero warnings and errors, then 249 of 249 tests passed;
- Web: 121 of 121 tests passed, then the TypeScript and Vite production build completed successfully.

Known non-blocking limitations remain explicit:

- Live media is LAN-only with host ICE candidates; STUN, TURN, cloud relay, and blocked-LAN fallback are not included.
- Live audio is not implemented.
- Medium is the only currently reported Live profile; Low, High, and adaptive switching are not claimed.
- Sixteen is layout capacity, not validated simultaneous-stream capacity. Current physical acceptance covers two concurrent Medium streams on the representative Samsung and Huawei devices; larger walls require device, browser, and network capacity testing.
- Live sessions and active viewer intent are volatile across Hub restart and browser reload; saved layout remains, but streams do not auto-start after reload.

## Architecture review

Before v0.6, Live View was intentionally a single-camera foundation.

| Layer | Previous assumption | v0.6 decision |
| --- | --- | --- |
| Web | `LiveViewPage` owned one selected device, one session id, one `RTCPeerConnection`, one stream, and one statistics timer. Changing device was disabled while Live was active. | `LiveWallOrchestrator` owns a map of independent tile lifecycles. The page composes reusable tiles and routes session events by device and session id. |
| Hub application | `LiveSessionEngine` stored one global `_active` session. Any second session, even for another device, returned `session_conflict`. | The engine stores sessions by server-issued session id and enforces one active session **per device**. One authorized viewer connection may own many sessions for different devices. |
| Hub signaling | One authenticated Monitoring Hub connection relayed offer, answer, candidates, statistics, preview, and lifecycle events to one exact authenticated device connection. | The signaling contract stays unchanged. Session id routes each message; exact operator subject/connection and device/connection ownership remain mandatory. |
| Android | `LiveSessionManager` and `AndroidWebRtcPublisher` own one active publisher and one CameraX lease in a process. A new assignment closes the previous publisher. | This remains the correct device-local constraint. Each physical phone publishes only its own camera. Multiple phones therefore publish independently; v0.6 does not create multiple camera owners on one phone. |
| Camera resource | Activity, monitoring service, recording, motion, and Live share the existing CameraX ownership/lease path. | Unchanged. Live Wall never binds a second camera pipeline and group recording uses the existing Camera Control and Recording engines. |
| ICE | Host candidates only, empty ICE server list, LAN direct media, trickle signaling. | Unchanged. There is no STUN, TURN, cloud relay, or Internet fallback. A routed LAN/VPN configuration that blocks host candidates remains unsupported. |
| Cleanup | Operator disconnect, device removal, timeout, device disconnect, and explicit close released the single session. | Cleanup now scopes to a session, device, or every session owned by an operator connection. Closing one tile cannot close another device's session. |
| Concurrent viewers | One session globally indirectly prevented every concurrent viewer. | v0.6 permits concurrent sessions for different devices. A device still permits one active viewer session, preventing two publishers or competing CameraX ownership on that phone. |

The previous global assumptions were located in `LiveSessionEngine._active`, every assignment to `_active`, connection cleanup that selected only `_active`, and the single session/media refs in `LiveViewPage`. The Android single-session fields are local to one device and are retained deliberately.

## Selected orchestration model

```text
LiveWallSession (one browser tab, volatile)
 ├─ LiveTileSession Samsung
 │   ├─ device id + viewer intent
 │   ├─ server session id (authoritative generation token)
 │   ├─ browser generation (stale async/event guard)
 │   ├─ one browser peer + statistics timer
 │   └─ independent cancel/retry/cleanup
 └─ LiveTileSession Huawei
     └─ same independent lifecycle
```

`LiveWallOrchestrator` is the browser capability boundary. It owns desired Live intent, actual media state, recovery state, generation, cancellation, diagnostics, and cleanup. React components render immutable snapshots and do not implement signaling rules.

A Start Live operation remains pending after the browser offer is routed. It succeeds only after the current Android connection reports a state for that exact session, proving that the device received the assignment and started its publisher. A negative device report or a 15-second acknowledgement timeout closes the partial peer/session and returns the exact stable failure category.

The Hub remains the authoritative session owner. A session binds:

- server-issued session id;
- device identity and current authenticated device connection;
- viewer subject and current authenticated operator connection;
- requested supported profile;
- activity/negotiation deadline;
- reconnect count and measurable first-frame/bandwidth data.

The session id is the cross-process generation token. The browser adds a monotonically increasing tile generation around async creation and peer callbacks. Both must match before an answer, candidate, state event, stream, or statistic can mutate a tile. Old session events return `session_not_found` at the Hub or are ignored in the browser.

## Lifecycle and recovery

```text
Idle → Connecting → Streaming
          │             │
          └──────→ Reconnecting
                         │
              viewer intent + fresh device availability
                         │
                         └→ Connecting → Streaming

Any state → operator stop / hidden or removed tile / page exit → cleanup → Idle
```

Device availability always comes from the v0.5 effective health resolver:

- **Healthy/Degraded and connected:** a selected tile may start.
- **Recovering:** retain volatile viewer intent, show amber, and wait for recovery or the Hub's preserved-session reassignment.
- **Offline:** retain viewer intent, show red, disable dependent start/control actions, and do not create connection attempts.
- **Reconnect:** only a tile with viewer intent restarts. The tile remains Connecting/Reconnecting until a current browser media track arrives; a connected control channel alone is not displayed as Streaming.

One device transport loss affects only sessions for that device. If the Hub preserves the session during the v0.5 reconnect grace, it sends a new assignment to the new authenticated device connection and the browser replaces its stale peer under a new browser generation. If a session has expired, the orchestrator creates a clean new session only while viewer intent still exists.

Retryable media, device, timeout, and routing failures use bounded browser delays of 1, 2, 5, then 10 seconds, in addition to the existing Android/Hub recovery policy. Ownership and in-flight start guards suppress parallel attempts. Capability, authorization, ownership, and same-device session conflicts are not blindly retried.

## Grid and interaction

The saved capacity options are 1, 2, 4, 6, 9, and 16. Capacity is independent from active stream count. Unassigned capacity renders honest empty placeholders.

- Desktop uses one, two, three, or four columns according to capacity.
- Medium viewports cap dense layouts at two columns.
- Narrow viewports use one column with no horizontal page overflow.
- Video uses `object-fit: contain` in a 16:9 tile and letterboxes instead of distorting.
- Native fullscreen targets one tile. Exit returns to the unchanged grid and tile order.
- Tile selection supports pointer, checkbox, Enter/Space, and additive keyboard modifier behavior. `F` opens the focused tile fullscreen.
- State is expressed with text, iconography, border treatment, and color. Reconnect animation honors reduced-motion preferences.
- Scoped wall tokens provide explicit Light and Dark modes.

The compact status strip renders Live, Hub link, Recording, Motion, overall device health, and Battery. Start/Stop Live, recording, controls, and details remain accessible without covering the video with a permanent panel. Normal UI copy does not expose SignalR, ICE, SDP, or WebRTC terminology.

## Smart activation and quality policy

Saved layout and active intent are separate:

- assigning a camera never starts it;
- only explicit per-tile or selected-group actions create Live intent;
- removing or hiding a tile stops and cleans its session;
- leaving the wall stops every session;
- browser reload restores capacity/order/selection but never restores active intent;
- a hidden browser tab releases live media while retaining only in-memory intent, then restarts eligible tiles when visible;
- automatic recovery happens only while that volatile viewer intent remains true.

Profile selection is capability-driven and locked for a session to avoid oscillation:

1. fullscreen requests High only when the Hub reports High available;
2. 2–4 active intents prefer Medium;
3. 6–16 active intents prefer Low;
4. when the preferred tier is unavailable, the selector uses an actually reported available profile and says so.

The current Android/Hub protocol reports Medium as the only available profile. Therefore every current session remains Medium (1280×720 target, 30 fps target); the UI does not claim Low or High support. Adding adaptive profiles later requires extending the existing Live/Camera Control capability contracts and Android publisher, not creating a second quality engine.

## Group actions

Start/Stop Live executes through the shared `LiveWallOrchestrator` once per selected tile. Start/Stop Recording calls the authorized `CameraControlGroupEngine`, which delegates each device request to the existing `CameraControlEngine`.

`liveWallEligibility.ts` is the shared presentation/action policy used by tiles, the group toolbar, Device Details Live actions, and bulk dispatch. It combines current transport, connection-scoped Live readiness, tile session ownership, the current Camera Control capability report, recording telemetry, and in-flight ownership. The toolbar enables an action when at least one target is eligible, shows an eligible count, dispatches only eligible devices, and reports every skipped device with a reason. Immediate locks suppress duplicate and opposing commands.

The group boundary does not duplicate capability validation, change settings, dispatch commands, retry, persist, or audit. The existing Camera Control engine remains authoritative for all of those behaviors. Its capability snapshot is bound to the exact reporting device connection; a replacement connection is online but not control-ready until it republishes state. Each device receives a stable group-derived correlation id. Recording results remain pending until the existing command engine records Android acknowledgement. Results are returned per device, partial success and skips are explicit, and a failure never rolls back successful devices.

Bulk settings editing is not part of v0.6.

## Security and ownership

- The Monitoring Hub continues to require `DeviceControl` authorization for availability, metrics, session creation, signaling, statistics, preview, and close.
- Camera Control and group recording endpoints require the same `DeviceControl` policy.
- Device signaling methods continue to require the device policy and authenticated device identifier.
- Every session message is checked against the exact viewer connection or exact current device connection.
- A browser cannot apply signaling to another viewer's session, and a replaced device connection cannot mutate a current session.
- Session conflicts remain per device. Unauthorized devices, streams, and controls are never enumerated by the Live Wall itself; it only consumes the already-authorized monitoring device list.

## Failure and cleanup policy

| Failure | Tile behavior | Cleanup/recovery |
| --- | --- | --- |
| Device Recovering/Offline | Amber then red from v0.5 state | Keep volatile intent; no connection thrash while unavailable |
| Camera busy/publisher failure | Failed with product-safe reason | Close peer/session; retry only if classified retryable |
| Browser media failure | Reconnecting | Dispose peer, close authoritative session, bounded clean retry |
| Session conflict | Failed | No automatic retry; other tiles continue |
| Stale answer/candidate/event | No visible change | Generation/session mismatch ignored |
| Hub restart/operator transport loss | Reconnecting | Existing sessions are process-local and disappear; clean sessions start after Hub and device health recover if intent remains |
| Browser refresh/page exit | Wall closes | All owned Hub sessions and browser peers close; saved layout remains |
| User stops while Connecting | Idle | Generation invalidates the pending result; a late created session is immediately closed |
| Device removed | Empty/removed tile | Device session and media close; other tiles continue |

No tile can remain Connecting indefinitely: Hub negotiation expires after 45 seconds, active sessions require activity within 30 seconds, and the five-second timeout worker closes expired sessions.

## Observability

Development-only browser events record tile device id, session id, generation, state, start request, duplicate suppression, first-frame duration, reconnect scheduling, stale-event suppression, and cleanup failures. Normal UI copy remains product-oriented.

The Hub emits structured lifecycle and aggregate metrics:

- active viewer connections;
- active tile sessions;
- active publishers;
- reconnect count;
- first-frame duration when browser receive evidence arrives;
- cleanup duration;
- aggregate estimated receive bandwidth when consecutive browser byte samples make it measurable.

Authorized diagnostics can query `GetLiveWallMetrics`; no tokens, SDP, candidates, IP addresses, or video data are included.

## Audio and physical capacity limits

Live audio is not implemented by the current Android publisher. v0.6 does not invent it. A future audio policy can keep every tile muted and allow one selected audible tile, but that requires an explicit device capability and product acceptance.

Sixteen is a layout capacity, not a promise of sixteen simultaneous streams on the current hardware. Current physical acceptance uses Samsung and Huawei for two concurrent Medium streams. Higher counts require representative Android publishers plus browser CPU, decoder, memory, and network measurements before support can be claimed. Host-only ICE and available LAN bandwidth remain material limits.
