# Controlled notification outbox migration

This is a future operator procedure, not authorization to change production.
Source of truth: `20260830111844_AddNotificationDeliveryOutbox` in the
Notifications module. Apply only from
`20260821164303_InitialNotificationCampaigns`; do not create a replacement
migration, hand-edit a generated artifact, or reset campaign statuses.

## Execution and historical semantics

EF executes individual migration commands sequentially inside its transaction.
The SQL Server script generator can concatenate those commands into one batch.
The migration therefore uses constant `EXEC(N'...')` payloads for both backfills,
the filtered current-attempt index, and the current-attempt CHECK constraint.
Their dependent expressions compile after the new columns exist. Quotes are
escaped by the migration; no external values enter these payloads.

On SQL Server 2019, an existing table followed by `ADD CurrentAttemptId` and a
bare `ADD CHECK (... CurrentAttemptId ...)` in one batch fails with Msg 207,
Level 16, State 1 before the column is added. Delaying the CHECK through EXEC
succeeds. Creating the table in that same batch can hide this defect through
deferred binding; a reproduction must initialize the old table in an earlier
batch. See the [synthetic reproduction](https://dbfiddle.uk/QB3WTaPy) and the
[remaining DDL binding rehearsal](https://dbfiddle.uk/OD3hTDhi), both on a
disposable SQL Server 2019 engine. These generic examples contain no application
schema or data and do not constitute execution of the complete migration.

The binding audit covers all dependencies:

| Dependency | Execution boundary |
| --- | --- |
| DeliveryKey UPDATE | Constant EXEC after ADD COLUMN |
| DeliveryKey default-constraint lookup/drop | Metadata string lookup; dynamic DDL for the discovered constraint |
| DeliveryKey ALTER COLUMN NOT NULL and ordinary unique index | Ordinary DDL, verified in the synthetic same-batch rehearsal |
| Attempts table's columns, inline PK/checks, recipient FK | CREATE TABLE definition; referenced recipient Id already exists |
| CurrentAttemptId UPDATE and attempts INSERT/SELECT | Constant EXEC after both columns and the attempts table exist |
| CurrentAttemptId filtered index and CHECK | Constant EXEC after ADD COLUMN/backfill |
| Attempts lookup/recovery/recipient-number indexes | Ordinary DDL after CREATE TABLE, verified in the rehearsal |
| Replaced status/next-attempt checks | Expressions reference pre-existing columns only |
| Migration-history INSERT | Pre-existing history table; last operation before commit |

`SET XACT_ABORT ON` makes SQL Server runtime errors abort the transaction. EF
still owns transaction creation and rollback; no transaction is suppressed or
nested by the migration. The generated script includes the migration-history
insert after all schema/backfill/index operations and before its sole commit.
Scripts must also run in a fresh, dedicated connection with a client that exits
on every SQL error and closes the connection. Compilation errors are not covered
by XACT_ABORT, and a client stop is not a server-side TRY/CATCH. An unchanged
database after an earlier failure is an observation, not a guarantee for every
failure. Always verify the outcome using a new read-only connection. Never
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
  --output /tmp/20260830111844_AddNotificationDeliveryOutbox.corrected-v2.sql
shasum -a 256 /tmp/20260830111844_AddNotificationDeliveryOutbox.corrected-v2.sql
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
2. Use the approved secret-backed profile in a new `sqlcmd` process/session and
   the protected capture procedure below. Credentials are supplied through the
   existing environment/profile, never echoed, committed, or placed on the
   command line. Preserve the approved TLS options, script transaction and
   `XACT_ABORT ON`. Do not wrap it in another transaction or enable replay.
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

### Protected capture for the future approved execution

This procedure is prepared only; it must not run during diagnosis or review.
Require new approval for the v2 checksum. The earlier artifact/checksum is not
approval for this version. Reserve one executor and complete every gate above.

For Go sqlcmd 1.9.0, **omit `-r` entirely**, including `-r 0`: that option's
[error callback](https://github.com/microsoft/go-sqlcmd/blob/v1.9.0/cmd/sqlcmd/sqlcmd.go#L779-L790)
can bypass the default formatter and print only the message body. The
[default formatter](https://github.com/microsoft/go-sqlcmd/blob/v1.9.0/pkg/sqlcmd/format.go#L199-L222)
preserves SQL Server Msg number, Level (severity), State and Line. `-b -V 11`
stops the client on SQL errors; in this version an exit code of 16 with these
options denotes SQL Server severity 16, not error number 16. Re-review capture
behavior if the binary/version changes.

The approved secure loader must already have supplied `SQLCMDSERVER`,
`SQLCMDDBNAME`, `SQLCMDUSER` and `SQLCMDPASSWORD` to the child environment. Set
`OUTBOX_APPROVED_SHA256`, `OUTBOX_APPROVED_SERVER` (the expected `@@SERVERNAME`)
and `OUTBOX_APPROVED_DATABASE` from the separate approval record. Do not place
credentials in this document or log the environment. `-N -C` below preserves
the previously reviewed TLS profile; a changed profile needs separate review.

```sh
cd "$BOTGLOBAL_REPO"
python3 - <<'PY'
import datetime, hashlib, json, os, pathlib, re, shutil, subprocess, tempfile

os.umask(0o077)
sql = pathlib.Path('/tmp/20260830111844_AddNotificationDeliveryOutbox.corrected-v2.sql')
payload = sql.read_bytes()
digest = hashlib.sha256(payload).hexdigest()
if digest != os.environ['OUTBOX_APPROVED_SHA256']:
    raise SystemExit('STOP: SQL checksum mismatch')
if os.environ.get('SQLCMDINI'):
    raise SystemExit('STOP: unexpected SQLCMDINI startup script')
for name in ('SQLCMDSERVER', 'SQLCMDDBNAME', 'SQLCMDUSER', 'SQLCMDPASSWORD'):
    if not os.environ.get(name):
        raise SystemExit('STOP: approved secure profile incomplete')
binary = shutil.which('sqlcmd')
if not binary:
    raise SystemExit('STOP: sqlcmd unavailable')

evidence = pathlib.Path(tempfile.mkdtemp(prefix='notification-outbox-apply-', dir='/tmp'))
evidence.chmod(0o700)
def save(name, data):
    with (evidence / name).open('xb') as handle:
        handle.write(data)
    (evidence / name).chmod(0o600)

# Freeze the reviewed bytes for execution and later statement/line correlation.
save('reviewed.sql', payload)
save('sql.sha256', (digest + '\n').encode())
def capture(label, arguments, timeout):
    record = {'binary': binary, 'arguments': arguments,
              'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'exitCode': None, 'sha256': digest}
    try:
        with (evidence / (label + '.stdout')).open('xb') as stdout, \
             (evidence / (label + '.stderr')).open('xb') as stderr:
            # Raw bytes go directly to protected files, even on timeout/error.
            result = subprocess.run([binary, *arguments], stdin=subprocess.DEVNULL,
                                    stdout=stdout, stderr=stderr, timeout=timeout)
            record['exitCode'] = result.returncode
            if result.returncode != 0:
                raise SystemExit('STOP: ' + label + ' failed; retain both output files')
    except subprocess.TimeoutExpired:
        record['timedOut'] = True
        raise SystemExit('STOP: timeout; no retry; worker remains paused')
    finally:
        record['endedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        save(label + '.json', json.dumps(record, indent=2).encode())

print('Protected evidence directory:', evidence, flush=True)
capture('version', ['--version'], 10)
version = (evidence / 'version.stdout').read_text().strip()
if not re.search(r'^Version: 1\.9\.0$', version, re.MULTILINE):
    raise SystemExit('STOP: sqlcmd version needs review')

common = ['-N', '-C', '-b', '-V', '11', '-x', '-l', '15', '-w', '65535']
capture('target', common + ['-t', '30', '-h', '-1', '-y', '0', '-Q',
        'SELECT @@SERVERNAME AS ServerName, DB_NAME() AS DatabaseName '
        'FOR JSON PATH, WITHOUT_ARRAY_WRAPPER;'], 50)
raw = (evidence / 'target.stdout').read_text()
target = json.JSONDecoder().raw_decode(raw[raw.index('{'):])[0]
save('target.identity.json', json.dumps(target, indent=2).encode())
if (target['ServerName'].casefold() != os.environ['OUTBOX_APPROVED_SERVER'].casefold()
        or target['DatabaseName'] != os.environ['OUTBOX_APPROVED_DATABASE']):
    raise SystemExit('STOP: target mismatch')

# Exactly one apply process. No loop, fallback, selected fragments or retry.
capture('apply', common + ['-t', '120', '-i', str(evidence / 'reviewed.sql')], 145)
print('Client completed; SELECT-only schema/history/backfill verification is still required.')
PY
```

All files inherit mode `0600` from the restrictive umask inside the new `0700`
directory. Preserve both raw streams and each exit record, including after
timeout/interruption. Do not replace them with regex summaries. Retain any
unparsed output for authorized review; absence of a parsed Msg is not success.
Keep this evidence outside Git and do not publish it without redaction.

Use `reviewed.sql` to map outer-batch line numbers. For an error inside EXEC,
decode that constant payload with ScriptDom and preserve its inner line numbers
and outer EXEC location. Do not assume an inner Line number is an artifact line
or guess between multiple possible payloads. The frozen artifact and raw error
streams must survive even when automated context extraction fails.

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
