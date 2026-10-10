# Controlled PostgreSQL Chat activation

Status: source preparation only. This document grants no server access, database mutation,
deployment, provider send or public readiness claim. The parent owns builds, isolated
PostgreSQL rehearsal and a fresh independent T4/V4 review before any target operation.

## Scope and artifacts

All Bot Global platform databases target PostgreSQL. This activation adds only:
`communication.ChatConversations`, `ChatMessages`, `ChatReceipts`, `ChatDispatches` and
`ChatVoiceTransfers`, plus their constraints/indexes and a version comment. No legacy
table, Calling schema, provider binding or shared platform default is migrated here.

Review these exact artifacts together:

- `infra/apps-prod-01/postgres-chat/001-application-scoped-chat.sql` (standalone version 001).
- `infra/apps-prod-01/docker-compose.chat.yml` (private volume and disabled Chat workers).
- Accepted backend image digest, containing the matching Chat model and packaged ffmpeg.
- Existing target configuration, Communication database identity, Nqrb app/provider route,
  volume ownership and controlled ingress procedure.

Record SHA-256 of SQL and overlay, branch/full HEAD, full reviewed source/index fingerprint,
image digest, schema version, independent review, operator, approval and target in the
deployment evidence store. Recompute immediately before apply; a byte change invalidates
artifact approval. The checksum is external to the SQL to avoid a self-referential checksum.
Never include passwords, tokens, connection strings, recipients or raw payloads in evidence.

The never-applied Chat EF migration retains ID `20261007153116_AddApplicationScopedChat`.
Its Chat operations match the Npgsql model. Older migrations and non-Chat model fragments
still contain SQL Server types/checks/provider annotations. **Do not run the full EF chain,
full Communication CreateScript, `Migrate`, or Communication `EnsureCreated`.** The snapshot
is not a declaration that the whole historical module has been ported. Do not forge earlier
EF history to make a migration runner appear current. Any later migration must reconcile
standalone Chat history and actual target schema under a new reviewed operation.

## Parent-owned validation before target approval

Provision a new empty PostgreSQL 18 UTF8 database and generated test-only credentials on
`localhost` or `127.0.0.1`, named `chat_rehearsal_<unique_suffix>`. The parent controls its
container, role, lifetime and removal. Tests neither provision nor delete a database.
Set `BOTGLOBAL_CHAT_TEST_POSTGRES` privately in the test process, never print it. Accepted
connection fields are Host, Port, Database, Username, Password, SSL Mode and Include Error
Detail; additional connection options fail closed. Host lists, sockets, production names,
missing explicit test credentials and foreign hosts are rejected before connection.

After inspecting the normal test/build entrypoints, the parent runs:

```sh
cd /Users/ashraffarag/Repo/bot-global-platform
dotnet test backend/tests/BotGlobal.UnitTests/BotGlobal.UnitTests.csproj --filter 'FullyQualifiedName~ChatPostgresTests|FullyQualifiedName~ChatRuntimeRegistrationTests'
```

The connected rehearsal must execute, with **zero skipped integration cases**. Without
the environment variable, only the PostgreSQL integration fact skips with a visible reason;
model, migration, composition and connection-safety tests always run. A configured but invalid
or unreachable database fails rather than skips. Use a fresh empty database for each run;
the successfully created five tables and synthetic records intentionally remain for inspection.

The tests apply the actual SQL file, reject wrong version/partial/history-conflicting states,
verify a late DDL failure rolls back, reject repeated apply without changing records, compare
actual catalog columns/keys/foreign keys/indexes with generated Npgsql Chat-only DDL, and
exercise actual EF writes/reads, retry identity, case-distinct/Unicode opaque values, app
scope, database uniqueness/FK errors, monotonic receipts, optimistic sequence concurrency,
lease claims and voice terminal-state persistence. Business/external authorities are fake;
no provider loop, credentials lookup, audio decoder process or filesystem voice storage runs.

Also run the parent-selected full `backend/BotGlobal.sln` build and relevant Chat, Calling,
Identity and architecture regressions. Retain all results against the final source checksum.
UI/mobile bytes remain unchanged; reuse prior evidence only after hash verification and make
no new native/live claim. A source test definition is not a test PASS.

## Approved target preflight

Obtain separate approval naming environment, server/database, role, SQL checksum, image
digest, overlay checksum, maintenance window, side-effect scope and recovery owner. Then:

1. Verify target database identity, PostgreSQL 18+ version, UTF8 encoding, deterministic
   `pg_catalog.C`, schema owner and least privileges. The operator needs approved DDL rights;
   runtime needs only the appropriate schema/table access. Do not introduce blanket grants.
2. Inventory actual `communication` relations, types, constraints, indexes, comments and
   module migration history. Record the existing history fingerprint and compare it with
   approved evidence; no inference from source or historical deployment notes. The artifact
   rejects any of the five Chat object names, even a supposedly identical/partial schema,
   and an EF row for the new Chat migration. An unexpected state requires separate review;
   do not drop, rename, adopt or rewrite history to get past the guard.
3. Confirm no startup path applies Chat DDL. The existing canary initializer currently touches
   Identity only; this batch does not change it. Keep global canary and unrelated workers
   unchanged. Any target-specific schema/bootstrap behavior needs its own review.
4. Stop Chat ingress under the approved operational mechanism and keep Chat workers disabled.
   Do not infer that `WorkersEnabled=false` blocks HTTP/hub requests: it does not.
5. Take a verified recoverable Communication backup and schema/history capture. If any Chat
   data already exists, stop this initial activation and review recovery/migration separately.
   Rehearse restore into a separate isolated target; record restoration time, checksum and
   verification. Never overwrite the active database as an unreviewed rollback.

## Private voice volume and decoder

The overlay adds the durable named volume `botglobal-chat-voice`, mounted only by backend at
`/var/lib/botglobal/chat-voice`. Do not mount it into Caddy/static content or publish files.
For each approved host/replica, verify the same intended durable storage and writable runtime
UID/GID, restrictive directory/file permissions (e.g. directory 0700, files 0600), free space,
inode capacity, retention capacity and restore ownership. This Compose named volume is local
to its Docker host; it is not distributed storage. Multi-host replicas require an explicitly
reviewed shared-storage/routing plan before activation. Do not use `down -v` during recovery.

The existing backend Dockerfile packages ffmpeg. Verify the accepted image actually contains
`/usr/bin/ffmpeg`, can decode the synthetic AAC fixture under the runtime identity, and enforces
the existing decoder time/output/container bounds. Inspect the private path and image without
exporting user audio. A mount declaration does not prove permissions, durability or decoding.

Keep `PublishedRetentionDays=7` (validated 1–7), `SweepMinutes=15` (validated 1–60), and the
absolute storage/decoder paths explicit. No static/public storage path is acceptable.
Application options validate bounds/path presence; private access, actual permissions and
capacity are deployment preflight responsibilities, not inferred from a string.

Backups of DB metadata and transfer bytes must be consistent. During backup/recovery quiesce
ingress and workers to avoid publication/ACK/deletion races. Securely retain only the approved
backup window; restoring expired or acknowledged bytes must never make terminal metadata
downloadable. Do not automatically delete transfer rows/files to manufacture a healthy state.

## Controlled apply and activation order

1. Reconfirm backups, approved target and checksums with workers off and ingress quiesced.
2. In an authenticated session established through the approved secret mechanism, set
   `botglobal.chat_activation_database` to the exact approved `current_database()` name and
   `botglobal.chat_activation_version` to `001`. These are operator acknowledgements, not
   security credentials. Execute the exact reviewed SQL with error-stop behavior (for psql,
   `ON_ERROR_STOP=1`). Do not wrap it in a runner that retries automatically.
3. The artifact owns its transaction, five-second lock timeout, sixty-second statement timeout
   and transaction advisory lock. It checks version/encoding/target/history/absence, creates
   exactly five tables and eleven secondary indexes, writes the standalone version comment,
   then commits. On any error, issue ROLLBACK or disconnect; inspect without continuing.
4. Capture resulting schema, constraints/indexes, version comment and unchanged EF history.
   Record the exact SQL checksum and successful commit in external operation history. There
   is deliberately no sixth history table and no fabricated EF migration row. Repeating
   this artifact rejects safely, including after a successful first application.
5. Start the accepted image with the reviewed overlay, private volume and
   `BOTGLOBAL_CHAT_WORKERS_ENABLED=false`. Verify health and schema connectivity without
   real-provider test traffic. Schema application is never an API startup side effect.
6. Confirm exactly one existing Nqrb directory/access adapter and Nqrb's configured provider
   route/application identity. The overlay adds no provider defaults and no cross-app route.
   The base compose already has unrelated notification/Firebase settings; this overlay is
   not an isolated-provider test environment. Do not start it for automated rehearsal.
7. After explicit worker/provider/recipient activation authorization, set
   `BOTGLOBAL_CHAT_WORKERS_ENABLED=true` and recreate only the approved backend instance(s).
   Both Chat dispatch and maintenance register regardless of global canary mode. Verify
   expected backlog, route isolation, bounded dispatch, sequence/idempotency/read behavior,
   durable voice ACK/expiry/deletion retry and volume survival across approved restart.
   Restore ingress in the approved order. Provider/device acceptance requires its own evidence.

## Health, deactivation and recovery

Monitor DB errors/latency, optimistic concurrency retry exhaustion, dispatch pending/retry/
terminal counts and leases, voice available/terminal deletion-pending counts, sweep errors,
decoder rejection/timeout and storage capacity without logging subjects, recipients, file
payloads or secrets. Readiness requires actual runtime behavior, not just a health URL.

For an activation failure before SQL COMMIT, rollback preserves the previous database state;
investigate the guard/error and do not auto-retry. For a failure after COMMIT, keep the schema
and records. Quiesce Chat ingress, set workers false and perform an approved graceful backend
stop/recreate. Existing in-flight work can finish before shutdown; the toggle is not a live
cancellation or revocation boundary. Preserve outbox/lease state and private volume. Review
pending/in-flight/unknown provider outcomes before reactivation; never clear intents or
manually advance status. Existing unknown-outcome rules prevent blind notification resend.

While maintenance is disabled, physical expiry deletion pauses and files may exceed retention;
terminal/expired metadata must still deny download. Bound the outage, monitor disk, and restore
maintenance promptly under approval. API uploads can still create data unless ingress is
quiesced. Changing image/config alone does not undo schema or erase these obligations.

There is no automatic SQL down/drop rollback. The EF `Down` refuses destructive removal.
Prefer a reviewed forward fix. If restore is necessary, restore DB plus matching private bytes
to an isolated target, reconcile terminal/expired metadata and dispatch outcomes, prove schema
and integrity, then obtain separate cutover approval. Never discard newer accepted messages or
replay sent provider routes as an implicit rollback. Keep evidence and backups until approved
recovery verification and retention closeout.
