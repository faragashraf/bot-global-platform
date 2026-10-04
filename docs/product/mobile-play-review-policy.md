# Mobile Google Play Review Policy

This policy defines the shared in-app review behavior for Bot Global Android
products. It uses Google Play's native in-app review API through the shared
`ReviewCoordinator`; non-Android consumers receive a no-op launcher through the
same contract.

## Shared Rules

- Automatic review prompts are product-neutral infrastructure. Each product owns
  its own meaningful business events.
- A meaningful event is counted once by a stable event id. Recomposition,
  process restart, duplicated snapshots, or replayed foreground handling must not
  increment the same event again.
- The default threshold is at least 3 genuine actions spanning at least 3 days.
  ENPO is the only current exception because successful pairing is a rare durable
  service-completion action rather than a repeatable screen visit.
- A 90-day durable cooldown starts only when a real native Play review launch is
  about to happen. Fetching Play review info, missing or stale activity state,
  non-Android no-op launchers, or deferred busy UI must not consume cooldown.
  The app must not claim that the user rated or completed a review.
- Only one review launch may run at a time. Cancellation is propagated, and stale,
  missing, or non-resumed foreground activity state must not launch a review flow.
- Eligible product events remain pending across unsafe UI states and are retried
  from settled foreground/product transitions instead of being dropped.
- Automatic review never falls back to the Store listing and never gates on
  sentiment. A separate explicit manual rating action may open the Store directly.
- Review requests are never triggered from OS `onPause` or `onStop`.

## Product Triggers

| Product | Meaningful event | Threshold |
| --- | --- | --- |
| LAMMA | Authoritative completed round result, any outcome, keyed by game session and match number. Delivery waits until the result UI is visible and no voice, consent, invitation, deletion, camera, or busy modal is active. | Default: 3 completions over at least 3 days. |
| NQRB | Connected call completion with at least 30 connected seconds. Missed, rejected, errored, or unconnected calls do not count. Delivery waits until the live call overlay is gone. | Default: 3 calls over at least 3 days. |
| ENPO | Successful validated pairing/service completion. Startup, unpaired screen visits, and already-paired no-ops do not count. Delivery waits until PairingSuccess has settled and the paired shell is foregrounded. | 1 successful pairing because the action is infrequent and durable, while retaining the default 3-day first-use age. |
| SentriCam | Successful finalized recording with at least one saved segment. Failed or stopped recordings before finalization do not count. | Default: 3 recordings over at least 3 days. |

## Validation Procedure

1. Run the shared coordinator tests for age, cooldown, deduplication,
   cancellation, and concurrency.
2. Run product tests for each trigger's success and rejection gates.
3. Build each Android debug app with the official `com.google.android.play:review`
   dependency resolved from the version catalog.
4. On a Google Play test track build, verify that eligible flows can request the
   native review API without blocking the product workflow.

Google controls review prompt quota and UI display. A successful native API call
does not prove a dialog was shown or a review was submitted. Final runtime
acceptance requires owner-controlled Play track, device, signing, and OAuth
configuration evidence.
