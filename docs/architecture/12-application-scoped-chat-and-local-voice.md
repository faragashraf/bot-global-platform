# Application-scoped chat and local voice

## Boundary

The Communication module owns the product-neutral chat aggregate, sequencing, receipts,
temporary voice-transfer lifecycle and durable dispatch intent. Every stored key and query
is scoped by the server-derived platform client `ApplicationId`. The core accepts opaque
participant references and canonical subject strings; an application-owned directory
translates references, and an application-owned access policy decides whether a pair may
start or continue sending. A missing, duplicate or inactive adapter fails closed.

Nqrb is the first registered consumer. Identity translates Nqrb membership IDs and Calling
delegates access to the existing contact, call-history and bidirectional-block rules. No
Nqrb identifier parsing or default policy exists in Communication. A future application,
including ENPO Connect, must register its own directory and policy before chat is enabled.

## Identity and synchronization

`/api/mobile/communications/chat` and `/hubs/chat` accept exactly one active application
mobile session or paired-device identity. The actor application is never accepted from a
request body or route. Each live connection retains an ephemeral credential-validation
capability issued by its owning authentication handler. Before each sensitive hint, the
dispatcher revalidates the session/device, application, subject and directory through fresh
services; stale connections are removed/aborted. No unchecked subject-group broadcast is used.

The API is authoritative. Mobile clients synchronize bounded conversation/message pages
on login, resume, reconnect and notification hints. SignalR and FCM carry the same stable
message metadata only; neither carries audio, and provider/socket acceptance is not a
durable voice-download acknowledgement. Requests use immutable credential snapshots and
cancelled account generations; outbound responses never advance fetched-history cursors.
Conversation paging uses timestamp plus ID. Large catch-up persists resumable cursors, and
metadata refresh is separate from append synchronization.

## Voice ownership and retention

Voice notes are AAC in an MPEG-4 container, at most five minutes and 10 MiB. The server
streams to a private opaque file, validates a bounded single AAC-LC audio track, sample
tables/chunk ranges and measured duration, checks exact length, hashes
while writing, and atomically publishes before committing message metadata. The configured
published retention defaults to seven days and is constrained to 1-7 days. An uncertain
database commit never authorizes immediate file deletion; recovery reconciles durable references.

The server copy is delivery storage, not an archive. The first valid recipient installation
ACK records deletion intent before physical deletion. Expired or acknowledged transfers
cannot be downloaded while deletion is pending; the sweeper retries failed deletion and
removes old unreferenced partial/orphan files. Duplicate ACKs are idempotent, including
after completed deletion.

Android stores the sender and recipient copies independently under application- and
subject-scoped directories in `noBackupFilesDir`. It verifies recipient bytes before an
ACK and atomically promotes the sender draft before removing the upload from its outbox.
The account/thread-owned draft survives until that durable state publication succeeds.
Serialized file/index mutations use unique temporary files, fsync and atomic rename;
failed writers never delete another successful writer's file. Cached authenticated identity
bindings restore local history offline, independent of realtime startup.
Ordinary logout keeps these local copies isolated for that account. Explicit account
deletion removes the bound account's chat state and voice directory. Clearing app data or
uninstalling removes local recordings; there is no automatic backup.

### Finalized media duration (r7)

Duration means `ceil(sum(stts.sampleCount * stts.sampleDelta) * 1000 / mdhd.timescale)`
for the finalized AAC-LC bytes. `ChatVoiceDuration` mirrors the backend's bounded
`ChatAacContainer` sample/timebase contract, including the final trimmed packet, single
self-contained audio track, descriptor/sample rate agreement, chunk/sample ranges and
five-minute/10-MiB limits. Integer ceiling is exact within these bounds. Android elapsed
realtime remains recording progress only; stop/fsync then measures the encoded file before
returning a draft. No Android metadata rounding equivalence is assumed. Container preflight
does not decode AAC payloads; established backend decoding remains required and unchanged.

The shared controller measures store-verified bytes before accepting a new queue entry.
Legacy elapsed-duration entries have `encodedDurationVerified=false` by default. Install,
or flush after a temporarily unavailable read, verifies owned bytes/length/digest, measures
duration, then saves normalized duration, digest and the marker atomically before canonical
coalescing, upload or local publication. Android supplies the actual verified digest even
for legacy entries without one. Other voice stores must verify supplied metadata and return
a verified digest when none was saved; absence fails closed. An already-normalized entry
whose byte duration differs fails closed rather than being silently rewritten. Malformed
or unavailable bytes retain queued ownership; a failed state save does not authorize draft
deletion. Exact account/conversation/client ID/kind/digest/length/media-duration agreement
is required. No duration tolerance or digest-less response shortcut remains.

The existing synthetic backend fixture contains 89,224 samples at 44,100 Hz: **2,024 ms**,
distinct from both its two-second input and a 2,179-ms example recorder timer. Mobile tests
embed those exact existing fixture bytes; the backend test derives its oracle from the
actual sample table and asserts the fixture SHA-256. No microphone export or new media file
is needed. Test code is evidence to run, not an executed validation claim.

## Dispatch outcomes

The shared mobile outbox assigns a common per-account monotonic enqueue ordinal to text
and voice under the controller mutex. Saving the ordinal and entry is the ownership
acceptance boundary. `ChatSnapshot.pendingActivity` and the dispatcher use the same generic
projection, so type does not reorder sends after Back, restart or reconnect. A transient
failure blocks newer messages in that conversation for the pass; unrelated conversations
continue. Terminal failed entries remain available for recovery and are not retried by flush.
ACK and read receipts remain separate from the message order. Voice download ACK flushing
does not wait for unrelated outgoing uploads.

New serialized fields have defaults for existing JSON. One-time durable normalization
preserves order within each legacy list, then deterministically places legacy text before
legacy voice; historical interleaving was never recorded and cannot be recovered. It does
not fabricate timestamps. An optional creation-time clock supplies display time only;
ordering never compares device and server clocks. A durable monotonic local read watermark
survives successful read ACK and stale server refresh; it never changes the counterpart's
canonical read receipt.

### Canonical and pending timeline (r6)

`ChatSnapshot.timeline(conversationId)` is the generic account/conversation projection used
by the Nqrb thread and `conversationActivity`. Canonical rows retain server sequence order.
Each new queue entry saves the highest known canonical sequence (including a conversation
summary whose page is not yet cached) as its lower anchor. Before removing any reconciled
local entry, the controller copies its proven server sequence onto earlier local entries
as an upper anchor and later local entries as a lower anchor. Pending entries occupy the
earliest known canonical gap, in persisted enqueue order. Thus a retained old failure stays
before a later successful local send or newly observed incoming sequence, through restart,
metadata refresh and partial history. Canonical order has precedence if server acceptance
order contradicts local intent; pending gaps are clamped and nondecreasing in enqueue order.
No clock comparison or guessed historical interleaving resolves that uncertainty.

Legacy entries without anchors migrate to gap zero, preserving their already-normalized
local order. This explicitly treats their relation to cached canonical history as unknown,
placing them before that history rather than claiming they are the newest activity. Legacy
canonical history has no invented local enqueue identity. Two sequence bounds per remaining
pending entry replace any need for a permanent sent-message ordering ledger; removal of the
queue entry removes its bounds. JSON fields have defaults and normalize durably on install.

List preview and display time come from the final projected activity. A newer conversation
summary without its message page produces an unknown preview until fetched, never the stale
failure's content. Across conversations, canonical rows always sort by known canonical
server recency, with descending conversation ID as the server's stable tie-breaker. First hydration
of a conversation records its known sequence with ordinal zero; later historical pages
cannot manufacture newer activity. The canonical timestamp uses the newer of the summary
and latest cached canonical message, so partial message pages cannot lower known recency.
Only a sequence advance on an already-known conversation allocates an observation ordinal;
advances in one batch follow canonical timestamp/tie order. Old persisted r6 ordinals no
longer sort canonical rows ahead of timestamps.

Explicit pending local activity retains its durable enqueue chronology without comparing
device and server clocks. Pending rows are inserted into the canonical ordering after all
canonical conversations observed to advance later, while preserving descending pending
ordinal order. This keeps canonical-to-canonical recency intact even during mixed known/new
hydration. The same thread anchors still prevent an old terminal failure from masking a
newer canonical preview. Read/receipt-only refreshes do not bump activity. There is one
bounded sequence/ordinal record per existing conversation, no historical ledger or invented
wallclock, and state is account-scoped. Unread retains
the server sequence plus durable local read-watermark contract. Thread date separators and
visible/latest tracking are built from projected rows; separator keys belong to following
rows, since clock-skewed local dates can repeat without determining ordering.

Coalescing requires this account's subject, conversation, client ID, kind and compatible
content: exact text, or known SHA-256, length and duration for voice. A single validated
canonical match shares the queue row's scoped stable key. Ambiguous duplicate matches,
wrong senders/conversations/kinds/content and legacy voice without a digest cannot borrow
that identity; canonical fallback keys remain distinct. No queue data is deleted by this
presentation operation. A coalesced voice row exposes canonical sent/read/voice-ACK metadata
and separate localized, polite local-publication recovery with queued-draft playback.

After lost upload response, canonical sync may establish that exact voice identity while
the outbox still owns its bytes. Flush can then promote the verified draft using canonical
transfer metadata without another upload. A failed promotion or durable state save retains
the queue and draft; retry/restart publishes the sender key before deleting the draft. The
legacy path first persists verified media normalization and then uses the same exact
canonical agreement as new drafts, with final byte validation delegated to the voice store.
These sender-copy operations never create a recipient download ACK. Generation and account
fences, transient conversation head-of-line blocking and unrelated-conversation progress
remain in the controller. Backend production, provider and consumer wiring are unchanged.

Forbidden text stays saved with a typed failure. An explicit edit atomically replaces that
definitely rejected entry with a new identity/ordinal. Conflict text is retained while a
bounded canonical lookup matches account sender, conversation, client ID, kind and exact
content. Only that match removes an ambiguous entry; offline/incomplete lookup is never
proof of success. Nqrb offers copy/remove and delivery checking for conflict, and edit for
definite rejection. It does not automatically resubmit a conflict under a new identity.

Remote voice loading is published by transfer ID before network I/O. Concurrent callers
share one operation in the captured account generation. Local bytes are verified and
durable state is saved before ACK; cancellation/failure leaves a keyed recovery state.
Nqrb separately fences playback intent by account generation, thread and lifecycle.
Back, background, calls, Stop, another playback request, recording and authority changes
invalidate/cancel pending intent; a late transfer must not autoplay. A server-rejected
local recording remains playable, while missing/corrupt bytes have distinct recovery copy.

Both Nqrb list and thread use the existing bounded pane. Recency reuses the platform call
calendar formatter: today's clock, Yesterday, otherwise a localized date. Stable message
keys and per-message polite metadata live regions expose pending/failure transitions
without announcing the whole list. The composer starts at one line, grows through four
lines and then scrolls, preserving newline input, 48dp actions, RTL and the existing theme.
Actual TalkBack, large-font/IME and device behavior require the separate native review.

Chat dispatch runs separately from the slower voice sweep. Atomic database claims and a
bounded lease guard dispatch across instances; batches drain up to 500 intents per pass.
Each device route records its in-flight and accepted/transient/permanent/unknown outcome.
Accepted routes are not retried when another route fails. After an uncertain send/crash,
the in-flight route is marked unknown instead of blindly repeating an external notification.
This can lose a hint; canonical synchronization remains authoritative. It is not an
exactly-once provider guarantee. Stable message/event IDs deduplicate local notifications.
Provider acceptance is displayed as sent; only durable voice ACK or foreground read
progress supports stronger recipient states. Provider configuration remains app-scoped.

## Activation

PostgreSQL is the authoritative database for every Bot Global platform module. This batch
corrects only the five new Chat tables in the module-owned `communication` schema. UUIDs,
bounded Unicode strings, bigint sequences, booleans and UTC timestamps map to PostgreSQL
native types. Opaque subjects, client IDs, pair keys and file keys use deterministic `C`
collation for exact case-sensitive equality; no case folding or Unicode normalization is
introduced. Pair construction still uses the domain's ordinal comparison. SQLite uses its
`BINARY` collation solely for existing local tests, not as deployment evidence.

The unapplied migration ID remains `20261007153116_AddApplicationScopedChat`. Its Chat
operations and Chat portions of the designer/snapshot are corrected. Legacy Communication
fragments still contain SQL Server types/check expressions and historical provider metadata;
the full model's CreateScript and full EF migration chain are **not PostgreSQL-safe**.
Older migrations, legacy tables and the Calling schema are unchanged. Do not use
`Database.Migrate`, Communication `EnsureCreated`, startup migration, or a full-chain script
to activate Chat. The design-time factory builds an offline Npgsql model without credentials.

The controlled deployment artifact is
`infra/apps-prod-01/postgres-chat/001-application-scoped-chat.sql`. It creates exactly five
Chat tables with their existing scoped uniqueness, composite foreign keys and indexes.
It requires an explicit target/version acknowledgement, PostgreSQL 18+ with UTF8 and
deterministic `C`, and rejects existing/partial Chat objects or conflicting Chat EF history.
A transaction and advisory lock bound application. A table comment records standalone
version 001; it does not manufacture legacy EF migration history. Reapplication rejects
rather than adopting unknown objects. Automatic `Down` is disabled to preserve records.

`Communication:ChatRuntime:WorkersEnabled` defaults to `false`. Explicit `true` registers
Chat dispatch and voice maintenance in either global canary mode, requires PostgreSQL and
an absolute voice path, and does not change other workers or authorize provider activity.
This switch controls workers only; existing API authorization is unchanged. Disabling it
does not block writes or erase dispatch intents, and pauses expiry deletion until maintenance
resumes. Activation/deactivation therefore requires controlled ingress and backlog handling.

`infra/apps-prod-01/docker-compose.chat.yml` prepares the private named volume
`botglobal-chat-voice` at `/var/lib/botglobal/chat-voice`, seven-day retention, a 15-minute
sweep and `/usr/bin/ffmpeg`. Workers remain off unless explicitly enabled. Provider routing
stays in the existing app-scoped configuration: Nqrb's route only for Nqrb, no fallback.
The overlay and SQL are not applied automatically and are not readiness evidence.

Follow [the PostgreSQL activation runbook](../operations/chat-postgresql-activation.md) for
target approval, artifact checksums, schema/history preflight, consistent backup/restore,
decoder/volume checks, isolated rehearsal, activation and recovery. Real deployment and
provider/device acceptance remain separate gates.

## Local demo

Use a local API/database and the Nqrb debug endpoint only. Sign in as two active Nqrb
members who satisfy the existing contact policy, open Messages from Home or the message
action in People, exchange text, then record/preview/send a voice note. Verify the receiver
stores and ACKs before the server copy becomes terminal, both devices retain independent
local playback, read progress is monotonic, and blocking prevents new sends/downloads
without removing already stored local files. Do not point a debug APK at canary or public
production APIs and do not use a real provider for automated tests.
