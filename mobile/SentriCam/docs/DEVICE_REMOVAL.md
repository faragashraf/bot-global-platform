# Paired device removal

Camera Control Center V1 treats **Remove device** as an administrative unpairing action, not as historical-data deletion.

## Authorization and confirmation

- Only an authenticated Hub administrator can inspect the removal impact or remove a paired device.
- The confirmation shows the current device name, manufacturer/model, last connection, operational conflicts, and retention behavior.
- The administrator must explicitly acknowledge the effect and confirm the current device name.
- An active recording blocks removal until it has been stopped and finalized. Active Live sessions and executing commands are disclosed and safely closed or terminally failed during removal.

## Removal behavior

The Hub disables the device, invalidates its current registration credential, closes its active Realtime transport, releases Live ownership and cached capabilities, terminally resolves queued/executing commands, clears connection mappings, and records a `device.unpaired` audit event. The Android app clears only its encrypted Hub credential, stops monitoring, and returns to pairing-required setup. Installation identity and local recordings remain intact.

## Retention policy

Normal unpairing preserves:

- uploaded recording files;
- recording metadata and library visibility;
- device audit history, including the unpair event;
- historical device events, registrations, snapshots, and connection history.

Permanent media or history deletion is a separate, explicit operation and is never implied by **Remove device**.

## Re-pair policy

A removed phone can reconnect only by consuming a new short-lived, single-use pairing QR. Re-pairing reactivates the existing device row matched by its installation ID and creates a new registration record and credential. The old credential remains invalid because only the latest registration ID is accepted. This prevents duplicate stale device rows while preserving history.
