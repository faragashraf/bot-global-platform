# Local Hub

SentriCam Hub is the local product that receives device state and recordings, keeps them durable, and presents the browser dashboard. The default Home experience requires no separate database, secret generation, command-line migration, or manual device address.

## Product topology

~~~text
┌──────────────────────── trusted local network ────────────────────────┐
│                                                                      │
│  Browser ── setup / dashboard ──▶ SentriCam Hub                      │
│                                      │                               │
│                                      ├── private data store          │
│                                      ├── recording folder            │
│                                      ├── protected Hub secrets       │
│                                      └── media capability            │
│                                                ▲                     │
│  Android ── one-time QR pairing / secure device connection ──────────┘
└──────────────────────────────────────────────────────────────────────┘
~~~

Home selects SQLite automatically. Office or Enterprise can select SQL Server or PostgreSQL without changing application business logic or repositories.

## First launch

The API can start before a Hub has been configured. It creates safe in-memory bootstrap defaults so the setup UI is reachable. The wizard saves a resumable draft. Final configuration then:

1. verifies the selected storage and data service;
2. creates the recording and data folders;
3. creates or upgrades the selected database with provider-native migrations;
4. persists a random JWT signing key and independent Hub pairing secret;
5. writes public settings separately from secrets;
6. detects installed, missing, or broken media capability;
7. records automatic-start and dashboard preferences.

On later launches, a completed Hub runs non-destructive storage and database health checks. Migrations are not repeated by startup. A failed check moves setup to `NeedsRepair`, keeps `isConfigured` false, and opens the repair wizard instead of a false Hub Ready dashboard.

## Runtime files

The Hub home is selected from SentriCam__Home, then the API content root data/hub folder.

~~~text
hub-settings.json       # non-secret product choices and resume state
hub-secrets.json        # signing key, pairing secret, data credential
data/sentricam.db       # Home data store
certificates/           # generated HTTPS material when selected
~~~

Secret files receive owner read/write permissions on Unix-like hosts. Neither setup nor status contracts return signing keys, pairing secrets, passwords, or connection strings.

The setup lifecycle stored in `hub-settings.json` is `NotStarted`, `InProgress`, `Completed`, `Failed`, or `NeedsRepair`. `Completed` is valid only with a configuration timestamp and complete secrets. Older contradictory state such as `isConfigured: true` with no timestamp is migrated to `NeedsRepair`; a Home draft mixed with an external provider is restored to an isolated SQLite draft for review.

## Hub overview

After setup, the browser gets a short-lived local operator session without showing or requesting a token. The overview displays:

- Hub Ready and the friendly Hub name;
- storage health and policy;
- private Hub data or advanced database health;
- media-engine health;
- waiting or connected device count;
- an expiring one-time pairing QR.

## Pairing

The dashboard creates a five-minute, one-use pairing session. The QR carries a versioned sentricam://pair deep link with the advertised HTTPS Hub origin and a random pairing code. It does not carry a JWT.

The advertised origin is separate from the internal Kestrel listen endpoint. Resolution prefers the optional `SentriCam__Hub__AdvertisedUrl` deployment override, then a stable local machine hostname, then an active non-loopback LAN IPv4 address. Dynamic discovery uses HTTPS on standard port 443; it does not expose the internal Kestrel port. Invalid overrides and hosts without a usable identity fail explicitly instead of emitting an HTTP or localhost pairing payload.

Android validates the payload, configures the Hub address internally, submits its existing registration contract with the pairing code, and stores the returned device credential through Android Keystore-backed storage. A used or expired pairing code is rejected.

Release Android continues to reject cleartext HTTP. A packaged consumer release therefore needs the certificate/trust work described in [Installer Roadmap](INSTALLER_ROADMAP.md); development builds can exercise the LAN flow over their debug network policy.

## Capability degradation

Missing or broken FFmpeg does not stop the Hub, registration, uploads, or playback. Thumbnail work reports a retryable media failure while the overview identifies the media capability state. A future distribution supplies the correct licensed binary rather than asking a home user to install it.
