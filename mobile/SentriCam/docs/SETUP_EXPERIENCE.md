# Setup experience

## Goal

A person installing SentriCam at home should make product choices, not infrastructure choices. The setup language is Hub name, recording folder, storage policy, automatic start, and dashboard. Technical credentials are never generated, copied, pasted, or displayed by the user.

## State machine

Setup lifecycle and wizard position are separate. The lifecycle is explicit:

~~~text
NotStarted → InProgress → Completed
                 │
                 └──────→ Failed → InProgress (retry)
Completed ── failed startup health check ──→ NeedsRepair → InProgress
~~~

`isConfigured` is derived only from `Completed` with a non-null `configuredAtUtc`. A draft, generated defaults, or generated secrets never imply completion. The public completion marker is persisted only after validation, folders, database creation, migrations, and secret persistence succeed.

~~~text
Welcome
  → Home / Office / Enterprise
  → Hub name
  → Recording folder
  → Storage policy
  → [advanced: database → network → media]
  → Summary
  → Configure
  → Ready
~~~

Home skips the advanced steps entirely. Office and Enterprise add them to the step sequence. Every Continue action saves the current draft and step to the Hub, so a refresh or browser restart resumes in place.

## Validation

Client validation gives immediate product-language feedback. Server validation remains authoritative:

- mode and policy must be supported;
- Hub names are bounded and safe for display;
- recording paths are present and bounded;
- Home must use the built-in data provider;
- retention and reserved-space limits are bounded;
- external providers require server and database names;
- network ports and certificate choices are validated;
- a selected custom media engine requires a path.

Final configuration tests the data destination before changing the configured state. If provisioning fails, the state becomes `Failed`, `isConfigured` remains false, and the user returns to Summary with a structured, product-language repair message. A refresh restores the same repair flow.

## UX behavior

- dark product theme with clear focus and selected states;
- mobile-first single-column layouts below 760 pixels;
- minimum 320-pixel viewport support;
- reduced-motion support;
- no secret values rendered or retained in localStorage;
- browser-tab operator sessions stored only in sessionStorage;
- responsive Hub overview and pairing card.

## API surface

~~~text
GET  /api/v1/hub/setup
PUT  /api/v1/hub/setup/draft
POST /api/v1/hub/setup/database/test
POST /api/v1/hub/setup/configure
POST /api/v1/hub/session
GET  /api/v1/hub/status
POST /api/v1/hub/pairing-sessions
POST /api/v1/hub/pairing-sessions/complete
~~~

Setup mutation is anonymous only until the Hub is configured. Local dashboard sessions are restricted to loopback or private LAN request addresses. Pairing completion is bounded, rate-limited, expiring, and one-use.

## Failure and recovery

The operational database is not used to store setup progress, so a missing or unreachable database cannot prevent the wizard from opening. Public settings and secret settings use separate atomic JSON writes; secrets are committed before the public `Completed` marker. Later startup performs health checks only. It never reruns setup or migrations, and a failed health check changes the lifecycle to `NeedsRepair` before the dashboard can open.

## Safe first-run simulation

From the repository root, run `scripts/server/run-fresh-hub.sh`. The script creates a new temporary Hub home, enables the simulation only for Development, prints the profile path, and leaves it intact for restart verification. Existing Hub settings and databases are never reset or overwritten.

`SentriCam__FreshInstall=true` is rejected outside Development, requires an explicit empty `SentriCam__Home`, and requires that directory to be under the operating-system temporary root. Production exposes no reset endpoint.
