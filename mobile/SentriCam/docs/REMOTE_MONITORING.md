# Remote Monitoring V1

Remote Monitoring V1 is the first operator-facing SentriCam experience. It combines persisted
device snapshots with realtime invalidation and four bounded commands: Ping, Refresh status,
Start monitoring, and Stop monitoring.

## Runtime boundaries

```text
Authorized dashboard
  ├── HTTPS API: authoritative device state + command submission
  └── SignalR MonitoringHub: device/command change notifications
                           │
                           ▼
RemoteMonitoringService ──┬── durable DeviceCommand state
                          └── IDeviceCommandTransport
                                      │
                                      ▼
                                DeviceHub connection
                                      │
                                      ▼
DeviceConnectivityService → existing DeviceCommandHandler
          │                           │
          └── MonitoringCameraSession┘
                         │
                         ▼
                 typed command result
```

`DeviceHub` and `MonitoringHub` are transport adapters. They contain no repositories or device
business rules. The application service owns authorization, state transitions, idempotency,
timeouts, and query mapping. The dashboard treats the REST API as authoritative; SignalR events
tell it when to refetch instead of duplicating server state in the transport.

## Command lifecycle

Commands are persisted before dispatch. A command exposed to the dashboard is in one of four
states: Pending, Succeeded, Failed, or Timeout. `Dispatched` remains an internal durable state and
maps to Pending in the public contract. A 30-second deadline is enforced by a bounded server
worker. Correlation IDs preserve idempotent submission, and repeated device results do not repeat
state transitions.

Android processes received commands sequentially. The existing `DeviceCommandHandler` performs
capability checks and monitoring transitions; the SignalR adapter only maps wire contracts. If a
connectivity stop is explicitly requested while a result is in flight, connection shutdown waits
for result delivery. Stop Monitoring never requests connectivity shutdown: camera, motion, and
monitoring-owned recording resources are released, the device remains online, and heartbeat and
command reception continue.

## Android lifecycle boundary

`DeviceConnectivityService` is the one foreground service and the sole owner of SignalR,
heartbeat, reconnect, and remote-command reception. `MonitoringCameraSession` is an independently
started and stopped capability hosted by that service. While monitoring is stopped, the service
uses only the `connectedDevice` foreground-service type. Camera and microphone types are added
only while those resources are actually owned; microphone type additionally requires enabled
audio and permission.

Android 14 and later enforce while-in-use camera permission when a foreground service acquires the
camera type. A remote Start Monitoring request received while the app is backgrounded can therefore
be rejected by the OS unless the app is currently eligible for a camera foreground-service start.
SentriCam reports `foreground_camera_start_failed` in that case and keeps connectivity alive; it
does not retain a false camera service type to bypass the platform rule.

## Dashboard security

The monitoring API and hub require the existing `DeviceOperator` role. Remote Monitoring V1 does
not create production user login or operator-token issuance. The development web client keeps the
supplied short-lived operator token in `sessionStorage` only, so it survives a reload in the same
tab and is removed on sign-out or session end. It is never placed in local storage, URLs, visible
UI, or logs.

## Deliberate exclusions

Remote recording, live streaming, remote commands beyond the four V1 operations, pairing,
notifications, cloud upload, billing, and AI are not implemented.
