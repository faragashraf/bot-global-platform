# Controlled notification outbox migration

This is a future operator procedure, not authorization to change production.
Source of truth: `20260830111844_AddNotificationDeliveryOutbox` in the
Notifications module. Apply only from
`20260821164303_InitialNotificationCampaigns`; do not create a replacement
migration, hand-edit a generated artifact, or reset campaign statuses.

## Execution and historical semantics

EF executes individual migration commands sequentially inside its transaction.
The SQL Server script generator can concatenate those commands into one batch.
The migration therefore uses constant `EXEC(N'...')` payloads for both backfills
and the filtered current-attempt index. Their dependent expressions compile
after the new columns exist. Quotes are escaped by the migration; no external
values enter these payloads.

`SET XACT_ABORT ON` makes SQL Server runtime errors abort the transaction. EF
still owns transaction creation and rollback; no transaction is suppressed or
nested by the migration. The generated script includes the migration-history
insert after all schema/backfill/index operations and before its sole commit.
Scripts must also run in a fresh, dedicated connection with a client that exits
on every SQL error and closes the connection. This is necessary for compilation
errors and connection/cancellation failures as well as runtime errors. Never
continue a failed script, execute selected statements, or use `--no-transactions`.

Historical semantics are unchanged:

- Every recipient gets the deterministic lowercase GUID-N
  `application:campaign:device` delivery key (98 characters).
- States `RetryScheduled`, `SignalRDispatched`, `FcmAccepted`,
  `FailedPermanent`, and `SkippedRevoked` get one latest-known attempt and a
  current-attempt pointer. Attempt number is at least one; larger existing
  counts are retained. Earlier attempts are not reconstructed.
- Pending and expired recipients retain a null current-attempt pointer.
- Existing terminal recipient states remain terminal; the backfill does not
  enqueue historical recipients.
- Generated attempt/lease identifiers are synthetic correlation identifiers,
  not recovered historical lease ownership. Provider message IDs stay null.
  Timestamp fallbacks are approximations when the historical timestamps are
  absent; they are not evidence of an actual provider invocation.

## Prepare and review the artifact offline

Run from the repository root using the approved source revision. The module's
design-time factory uses a local design configuration and does not start the API
or its worker. Script generation does not need a database connection.
`BOTGLOBAL_REPO` below is the operator's local checkout directory.

```sh
cd "$BOTGLOBAL_REPO"
dotnet ef migrations script \
  20260821164303_InitialNotificationCampaigns \
  20260830111844_AddNotificationDeliveryOutbox \
  --context NotificationsDbContext \
  --project backend/src/Modules/Notifications/BotGlobal.Notifications \
  --startup-project backend/src/Modules/Notifications/BotGlobal.Notifications \
  --output /tmp/20260830111844_AddNotificationDeliveryOutbox.corrected.sql
shasum -a 256 /tmp/20260830111844_AddNotificationDeliveryOutbox.corrected.sql
```

Record the source revision, EF version, complete SQL review, exact SHA-256,
synthetic test results, and any missing real SQL Server validation in the
operator approval record outside Git. Use that approved artifact unchanged;
regeneration requires a new checksum comparison/review. Parser/unit tests are
not evidence that SQL Server actually executed the migration. If a disposable
SQL Server rehearsal is unavailable, explicitly carry that limitation into the
apply approval; never use production as a test database.

## Pause and preflight: separate approval required

1. User sets only `Notifications__Worker__Enabled=false` and performs one
   normal application restart. Do not deploy binaries or alter provider settings.
2. Prove `NotificationCampaignBackgroundService` is stopped for every serving
   instance. Observe campaign lease expiry/revision metadata at least twice,
   separated by the configured lease duration plus a polling interval. Require
   leases to stop advancing and expire. A stale Admin UI screen is insufficient.
   If claims continue, stop; do not begin DDL.
3. Avoid campaign creation/cancellation during the maintenance window. Confirm
   a fresh, recoverable backup with its time, retention, recovery procedure,
   and operator approval. An unverified backup blocks apply.
4. Resolve the existing approved secure Notifications connection profile.
   Through SELECT only, confirm `@@SERVERNAME` and `DB_NAME()` against the
   approved target. Keep credentials, database extracts, and recipient values
   out of commands/logs/Git. Preserve approved TLS settings.
5. Read `notifications.__EFMigrationsHistory`: require only the expected
   initial migration and no outbox entry. If outbox is already recorded, do not
   rerun it; verify its complete schema instead. Any other history discrepancy
   requires review.
6. Compare metadata to the exact reviewed script. Require:
   - both new recipient columns and the attempts table absent;
   - the two new recipient indexes, new current-attempt check, attempt PK/FK,
     attempt checks and attempt indexes absent;
   - the three replaced checks present, trusted/enabled, with old status
     ranges `1..7` and the old next-attempt rule;
   - existing recipient/campaign keys, FK, and unique campaign/device index
     intact, with no unexpected default constraint or name collision.
   Partial or conflicting state blocks apply; do not add manual `IF EXISTS`
   repairs or mark a migration applied by hand.
7. Capture aggregate counts only: historical recipients per status, active or
   leased recipients, orphans, projected duplicate/invalid delivery keys,
   rows requiring attempt-count normalization, and projected attempt count.
   Require no orphans, key collisions, or prospective constraint violations.
   Reconcile any data changes since the previous preflight before approval.
8. Confirm required effective permissions: database `CREATE TABLE`, schema and
   table `ALTER`, recipient `REFERENCES`, backfill SELECT/UPDATE, and INSERT for
   the attempts/history tables. No test DDL is permitted in production.

## Apply once: separate approval required

1. Reserve one executor and keep every campaign worker paused. Generated SQL
   does not acquire EF's migration lock. Obtain the exact reviewed SHA-256 from
   the approval record; stop if the artifact differs.
2. Use the approved secret-backed profile in a new `sqlcmd` process/session.
   Credentials are supplied securely through the existing environment/profile,
   never echoed, committed, or placed on the command line. For the reviewed
   sqlcmd client, the required execution behavior is:

   ```sh
   cd "$BOTGLOBAL_REPO"
   sqlcmd -b -V 11 -r 1 -x -l 15 -t 120 \
     -i /tmp/20260830111844_AddNotificationDeliveryOutbox.corrected.sql
   ```

   Supply the separately approved target/authentication/TLS profile to that
   invocation. `-b -V 11` stops on SQL errors; `-x` disables script variable
   expansion. Preserve the script's transaction and `XACT_ABORT ON`. Do not
   wrap it in another transaction or enable automatic replay.
3. Any SQL error, nonzero exit, timeout, cancellation, or lost connection stops
   the operation. Close the dedicated connection so an uncommitted transaction
   rolls back. Keep the worker paused. Re-read history and metadata using a new
   read-only connection before deciding anything further. If commit outcome is
   uncertain, do not retry blindly or execute the Down migration.
4. After successful execution, SELECT-only verification must confirm:
   - outbox history entry exactly once, after the initial migration;
   - `DeliveryKey varchar(100) NOT NULL`, nullable `CurrentAttemptId`, the
     attempts table, its rowversion and intended column types/nullability;
   - unique delivery key, filtered unique current attempt, and unique
     recipient/attempt-number indexes; both attempt query/recovery indexes;
   - trusted/enabled status, next-attempt, current-attempt, attempt-number,
     invocation and completion checks; intended cascade FK to recipients;
   - original recipient count and statuses preserved, exact delivery-key
     expression compatibility, expected attempt count and current pointers,
     no missing/duplicate key or invalid pointer, unchanged attempt numbers
     except the documented minimum-one normalization;
   - no reconstructed provider message IDs or newly pending historical rows.
5. A failed verification blocks worker resume and requires review. Do not
   manually alter campaigns, recipients, attempts, or migration history.

## Resume and observe: separate approval required

1. After verification, user restores only `Notifications__Worker__Enabled=true`
   and performs one normal application restart.
2. Verify API health and worker activity through runtime evidence and advancing
   leases. Existing unexpired ENPO `PreparingAudience` campaigns are eligible
   for automatic reclaim after lease expiry. Do not reset their status/cursor.
3. Observe recipient expansion, then `Dispatching`, then `Completed` or
   `CompletedWithFailures` according to actual outcomes. Expired campaigns may
   become `Expired`; provider acceptance is not proof of device display.
4. Verify application/provider isolation, bounded retries and no repeated
   duplicate-delivery loop. Use aggregate counts and safe error categories;
   never print tokens or recipient PII. Do not create extra test notifications.

The separate NQRB zero-recipient `Dispatching` summary defect is deferred.
This migration does not remove the summary query's `Recipients.Any(...)`
condition and does not change audience, expiry, reclaim, or dispatch lifecycle.
