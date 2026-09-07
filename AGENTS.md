# Bot Global Platform project policy

## Supervisor integration

Ordinary work remains OFF. Explicit AUTO/SUPERVISED and run controls use the global
`~/.agents/skills/supervisor-core/SKILL.md` through `.agents/supervisor.json`.
Load that installed contract; do not copy its orchestration, scoring, storage,
monitor, notification or VPN implementation into this repository. Bootstrap
discovery and operator details are in `.agents/BOOTSTRAP.md`.

In SUPERVISED mode the Supervisor owns capability decomposition, benchmark scoring,
selection, verification, escalation/down-shift, rework, acceptance and Project Gates.
Use the project pool, never another project's mapping. Reasoning autonomy is HIGH;
permission boundary is RESTRICTED. Model strength never changes authority. Native
session/sandbox restrictions remain effective; this policy grants no host access.
Limits: one active executor, one active child total, no nested agents, two executor
rework cycles TOTAL per original capability, including replacements and UX rework.

Before work, report recommended model/effort, limit strategy, reason and branch
decision. Every terminal command block starts with `cd` to this repository (or the
explicitly authorized relevant project path). After an agent result, decide commit
or revise, merge or wait, and next step.

## Recovery and Git

Inspect branch, HEAD, status and worktrees first. Read-only tasks preserve and report
dirty work without changing Git. File-changing tasks require a clean tree unless
the user explicitly approves identified existing work as a baseline; use the Core's
hash/status snapshot and reconciliation for that exception. Unknown/unrelated changes
pause mutation. Never reset, stash, restore, clean or accidentally commit baseline work.
Create a feature branch before changing files on main. Do not destructively switch
branches. Stage explicit owned paths only; inspect the index before every commit.

The 2026-09-07 bootstrap user explicitly approved preserving 15 modified backend
Shared Notification Lifecycle files and one untracked test on
`fix/shared-notification-lifecycle` at `615de7cfc0f758b1d728311c97a1e432ea4bd9a4`.
Bootstrap owns ONLY `AGENTS.md`, `.agents/supervisor.json`, `.agents/model-pool.json`,
`.agents/BOOTSTRAP.md` and `.codex/config.toml`. Its one authorized commit must contain
only those files. This exception does not approve unrelated dirty work or authorize
future bootstrap repetition, feature implementation, push, merge or deployment.

## Capability and architecture acceptance

Build one bounded capability with business value, owned paths, dependencies,
acceptance evidence and demo readiness. Supervisor/critical reasoner owns architecture;
mechanical executors receive accepted contracts and invariants, not boundary decisions.

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

Verification follows the global independent benchmark, including retained parent risk
after down-shift. Shared cross-module regressions need V3; trust/persistence/production
decisions need V4 and a strong independent non-implementing review context. No self-review
claim of independence. Scope checks to changed consumers and actual hazards; inspect
scripts before tests so local validation cannot silently reach real providers/databases.
Backend source checks normally use `backend/BotGlobal.sln` and relevant tests; frontend
and mobile checks are selected from actual package/Gradle scripts, not stale README claims.

## Project Gates

Technical acceptance and user operation authorization are separate. Existing exact
authorization persists; otherwise prepare a reviewable operation and PAUSE AT GATE.
No generic AUTO/SUPERVISED request authorizes the following operations.

| Gate | Required evidence/authority before action |
| --- | --- |
| Local commit | Supervisor final acceptance PASS and explicit scoped commit authorization; owned paths/index reviewed. Bootstrap has the single config-only authorization above. |
| Push / merge | Separate explicit authorization, exact branch/diff/remote and passing required validation. No automatic main integration or blanket normal-push permission. |
| Production deployment / worker control | Explicit target/artifact approval, accepted code, health/rollback plan, approved configuration and worker semantics; then separate runtime evidence. Restart/worker toggles are mutations too. |
| Staging deployment with remote mutation | Explicit environment/artifact/side-effect scope and rollback; staging is not assumed disposable. |
| Database mutation / migration | Explicit target and reviewed script checksum, module history/preflight, transaction/recovery plan and applicable rehearsal evidence. Generation/static review is separate from apply. No startup auto-migration. |
| Auth/security/trust-boundary change | Supervisor/critical decision, explicit acceptance criteria and authorized change scope before implementation; V4 independent security/invariant review before acceptance. Local explanatory text does not change enforcement. |
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
For material UI: pre-implementation critique/shape -> Supervisor UX criteria -> executor
-> technical and interactive runtime verification -> official final critique/audit
-> bounded rework -> Supervisor acceptance. Apply its current native setup and relevant
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

## Observability and next capability

Use global canonical storage, live monitor and L2 notification contracts; no local
implementations/hooks/daemons. The manifest enables both features. Run storage is outside
Git under the declared workspace; validate actual approved roots and ownership first.
Actual model/effort, child state and timestamps must be evidence-backed or unknown.
Bootstrap uses simulations only; it does not dispatch a feature executor or start a
feature run. Notification/monitor compatibility is not proof of OS delivery or runtime UX.

After bootstrap, the next SUPERVISED capability is Shared Notification Lifecycle FINAL
CHECKPOINT REVIEW and acceptance, not reimplementation. Historical tests are evidence
to reconcile, not fresh PASS. Reverify proportionately; feature commit needs its existing
conditional authorization plus final acceptance PASS. Merge/deployment remain gates.
Production closeout must prove ENPO bounded terminal delivery/no duplicate loop, NQRB
automatic zero-recipient Completed with zero counts/no manual DB update, and provider
isolation. Only after genuine lifecycle closeout and production validation, recover the
actual Astra audit/roadmap artifacts and choose ONE Phase 1B Shared Foundations capability.
