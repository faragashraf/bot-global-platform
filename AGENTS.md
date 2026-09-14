# Bot Global Platform project policy

## Symphony orchestration and Critiques acceptance

Bot Global Platform uses Symphony for orchestration and execution, and Critiques for
independent review and final acceptance. The repository binding and evidence contract
are defined by `.agents/symphony-critiques.json` and
`.agents/SYMPHONY_CRITIQUES.md`. Reuse the external Symphony and Symphony+ contracts;
do not copy their schedulers, worker lifecycle, assessment engine, storage, monitoring
or notification implementation into this repository.

Symphony owns capability decomposition, execution-unit selection, dispatch evidence,
validation collection, bounded correction and escalation. Critiques owns the independent
review and final PASS/FAIL decision. Critiques does not execute or repair the reviewed
delta. Permission authority remains RESTRICTED: model strength, Symphony execution or a
Critiques PASS grants no operation that the human owner did not authorize. Native
session/sandbox restrictions remain effective. Limits remain one active executor, one
active child total, no nested agents and two executor rework cycles per capability.

Before work, report recommended model/effort, limit strategy, reason and branch
decision. Every terminal command block starts with `cd` to this repository (or the
explicitly authorized relevant project path). After an agent result, decide commit
or revise, merge or wait, and next step.

## Recovery and Git

Inspect branch, HEAD, status and worktrees first. Read-only tasks preserve and report
dirty work without changing Git. File-changing tasks require a clean tree unless
the user explicitly approves identified existing work as a baseline. For that exception,
record the branch, full HEAD, `git status`, exact owned/reviewed paths and SHA-256 of the
approved diff or index before execution; compare the same evidence before acceptance and
commit. Any mismatch pauses mutation. Unknown/unrelated changes pause mutation. Never
reset, stash, restore, clean or accidentally commit baseline work.
Create a feature branch before changing files on main. Do not destructively switch
branches. Stage explicit owned paths only; inspect the index before every commit.

## Capability and architecture acceptance

Build one bounded capability with business value, owned paths, dependencies,
acceptance evidence and demo readiness. Symphony records architecture decisions and
invariants before execution; executors do not silently redefine boundaries. Critiques
independently verifies those decisions and invariants before final acceptance.

- Backend: thin endpoints/composition root; reusable capability engines/services;
  centralized validation, workflow rules and result/error handling. Follow
  `docs/architecture/03-backend-boundaries.md`: no direct module-to-module project
  references, no shared EF entities, module-owned DbContexts/schemas/history tables,
  minimal SharedKernel and explicit cross-module Contracts. Infrastructure stays separate.
- Frontend: pages compose shared/business components and shared models/utilities;
  reusable tokens/status rules; no duplicated UI logic/CSS. Preserve Arabic/English,
  RTL/LTR, themes, keyboard/accessibility and mobile-first responsive behavior.
- Mobile: protect existing consumers of `mobile/shared` and shared Firebase/calling
  code. Keep product branding/configuration local; do not move product semantics into
  Shared without a demonstrated reusable boundary. Retain backward/update compatibility.
- Notifications: server-derived application context throughout audience, destinations,
  delivery and summaries. ENPO uses ENPO's provider; NQRB uses NQRB's; FamilyGames/LAMMA
  uses its configured provider. No fallback across applications. Preserve bounded retry,
  revocation/replacement safety, idempotency and no duplicate-delivery loop.
- Public security: preserve machine/human/device/session distinctions, credential
  revocation, capability authorization, application scope and Phase 1A HTTP protections.
  No tokens, provider credentials, raw recipient payloads or PII in diagnostics/evidence.

Verification follows the global independent benchmark, including retained original risk
after down-shift. Shared cross-module regressions need V3; trust/persistence/production
decisions need V4 and a strong independent Critiques context. The Critiques actor/session
must differ from every executor actor/session where practical and always for V3/V4; no
self-review claim of independence. Scope checks to changed consumers and actual hazards;
inspect scripts before tests so local validation cannot silently reach real providers/databases.
Backend source checks normally use `backend/BotGlobal.sln` and relevant tests; frontend
and mobile checks are selected from actual package/Gradle scripts, not stale README claims.

### Critiques final-acceptance contract

A valid Critiques decision identifies the Symphony execution contract, unit, run and
revision; the reviewed branch and HEAD; whether the subject is a committed diff or staged
index; a SHA-256 fingerprint of that exact diff/index and its owned path list; validation
evidence; all blocking findings; and an explicit PASS or FAIL. A PASS is valid only when
all blocking findings are closed. Any later change to HEAD, the reviewed path set, the
diff/index fingerprint, the Symphony run revision or relied-on validation makes the PASS
stale and requires a new Critiques review. See `.agents/SYMPHONY_CRITIQUES.md` for the
canonical evidence rules.

### One-time workflow-migration transition

The repository owner authorized the initial Symphony + Critiques policy migration on
branch `chore/symphony-critiques-workflow` from HEAD
`284b8394acc16665e8cf3843d5621e24e948dd46`. Because Bot Global Platform was not yet
bound to Symphony when that delta was authored, only its initial policy-migration commit
may omit pre-existing Symphony execution evidence. It still requires an independent
Critiques final-acceptance PASS correlated to that exact branch, HEAD, final staged-index
SHA-256 and reviewed path set; `git diff --cached --check` PASS; proof that application
source is untouched; explicit repository-owner authorization for the migration; explicit
scoped commit authorization; and a reviewed index containing only the seven paths listed
in `.agents/SYMPHONY_CRITIQUES.md`.

This transition is consumed permanently when the first qualifying policy-migration commit
is created. It cannot authorize an amendment, replacement, replay, cherry-pick or any
later commit, even if the original commit is reverted. It grants no merge or push authority.
After that one commit, every local commit requires normal correlated Symphony execution
evidence and a current Critiques PASS.

## Project Gates

Technical acceptance and user operation authorization are separate. Existing exact
authorization persists; otherwise prepare a reviewable operation and PAUSE AT GATE.
No Symphony run or Critiques review authorizes the following operations by itself.

| Gate | Required evidence/authority before action |
| --- | --- |
| Local commit | Correlated Symphony execution evidence, a current Critiques final-acceptance PASS for the exact reviewed Git state, explicit scoped commit authorization, and reviewed owned paths/index. |
| Push / merge | Separate explicit authorization, exact branch/diff/remote and passing required validation. No automatic main integration or blanket normal-push permission. |
| Production deployment / worker control | Explicit target/artifact approval, accepted code, health/rollback plan, approved configuration and worker semantics; then separate runtime evidence. Restart/worker toggles are mutations too. |
| Staging deployment with remote mutation | Explicit environment/artifact/side-effect scope and rollback; staging is not assumed disposable. |
| Database mutation / migration | Explicit target and reviewed script checksum, module history/preflight, transaction/recovery plan and applicable rehearsal evidence. Generation/static review is separate from apply. No startup auto-migration. |
| Auth/security/trust-boundary change | Symphony contract with explicit acceptance criteria and authorized change scope before implementation; V4 independent Critiques security/invariant review before acceptance. Local explanatory text does not change enforcement. |
| Credentials/secrets | Explicit scope for provisioning/rotation/revocation/configuration; approved secret mechanism, no values in Git/prompts/logs. |
| External-provider side effect | Explicit application/provider/recipient/environment authorization, bounded operation and idempotency proof. Do not send test FCM, calling or machine-client requests by implication. |
| Broad shared-platform change | Accepted consumer inventory, contract/compatibility plan and regression scope before executor dispatch. Newly discovered consumers require reassessment, not automatic scope expansion. |
| Production/internal reads | Explicit approved target and data minimization; no credential discovery or live access inferred from old operational documents. |
| Destructive DB, filesystem/system, history rewrite | Prohibited by default. Any requested exception requires separate explicit destructive-operation review; never force-push, rebase/rewrite shared history or delete unrelated work as routine recovery. |

Never merge before build/validation PASS. Deployment is never implied by commit/merge.
The outbox operations document is a procedure, not standing production authorization;
conversation evidence says its corrected migration was already applied. Verify current
history/state before any future proposal; never replay it merely because the file says
"future". No manual campaign/recipient/history status repair to manufacture acceptance.

## Frontend specialist and runtime

Official Impeccable is the global specialist (4.2.2 observed during bootstrap).
For material UI: pre-implementation critique/shape -> Symphony UX criteria -> executor
-> technical and interactive runtime verification -> official final critique/audit
-> bounded rework -> Critiques acceptance. Apply its current native setup and relevant
platform playbook at execution, honoring its stricter review-pass ceiling. P0/P1 block;
P2 is fixed unless explicitly justified/deferred with owner/follow-up; P3 may be backlogged.
One-child limit still applies: no parallel specialist fan-out. Disclose sequential
review when required; unavailable required independence stays blocked.
Impeccable does not own business, auth, API, database, integration or platform architecture.
Skip UX gates for non-UI work. Detect Computer Use through actual callable runtime access
to the authorized target; CLI/App labels prove nothing. Missing required interaction
means PAUSED_AT_GATE, never screenshot/source-only PASS.

## Internal connectivity

No repository-wide VPN requirement is verified. Public mobile profile reads use stored
Pairing projections and never call upstream/internal systems
(`docs/architecture/10-mobile-profile-projection.md`). Internal producers publish through
machine-authenticated public boundaries; the optional internal directory/Oracle adapter
described in the architecture is not evidence that this public runtime needs Oracle/VPN.
Real SQL Server/provider/integration validation still needs its specific connectivity.
For a capability with a verified internal target, record that dependency and preflight
approved connectivity before interpreting failures. On timeout/outage preserve a safe
checkpoint, distinguish infrastructure from application failure, and consume no executor
rework for infrastructure retries. Use a global VPN guard only if actually available and
applicable; never implement one here or store passwords, OTPs or secrets in profiles.

## Orchestration evidence and next capability

Use global canonical storage, live monitor and L2 notification contracts; no local
implementations/hooks/daemons. Symphony run storage remains outside Git under its declared
workspace; validate approved roots and ownership before treating it as evidence. Actual
model/effort, child state and timestamps must be evidence-backed or unknown. Discovery or
monitor compatibility is not execution, Critiques acceptance, OS delivery or runtime UX.

The next orchestrated capability is Shared Notification Lifecycle FINAL CHECKPOINT REVIEW
and acceptance, not reimplementation. Historical tests are evidence to reconcile, not a
fresh PASS. Reverify proportionately; its feature commit still needs the existing scoped
authorization and a current Critiques final-acceptance PASS. Merge/deployment remain gates.
Production closeout must prove ENPO bounded terminal delivery/no duplicate loop, NQRB
automatic zero-recipient Completed with zero counts/no manual DB update, and provider
isolation. Only after genuine lifecycle closeout and production validation, recover the
actual Astra audit/roadmap artifacts and choose ONE Phase 1B Shared Foundations capability.
