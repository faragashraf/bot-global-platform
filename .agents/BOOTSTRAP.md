# Supervisor bootstrap record

Bootstrap date: 2026-09-07. Scope: project configuration only; no feature executor,
application edits, DB/provider requests, deployment, push, merge or history rewrite.

## Installed contracts and local discovery

- Global Core: `~/.agents/skills/supervisor-core`, metadata **1.4.0**; manifest schema
  **1**; compatible major `1.x`, minimum `1.4.0` for external run storage.
- Official Impeccable: `~/.agents/skills/impeccable`, metadata **4.2.2**. Referenced
  directly; no UI critique or helper download/run was needed for this config bootstrap.
- Local binary: `codex-cli 0.153.4`. `codex debug models --bundled` supplied exact IDs,
  efforts and advertised service tiers without network refresh or model execution.
- Callable native child selections intersect the catalog at `gpt-6-astra`,
  `gpt-5.6-sol`, `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5`.
  Other bundled entries: `gpt-daybreak-blue-latest`, `gpt-daybreak-red-latest`,
  `gpt-5.4`, `gpt-5.4-mini`, `gpt-5.2`, `codex-auto-review`; not project mappings.
  Catalog presence alone does not establish entitlement or live dispatch availability.
- Astra/Sol/Terra: `low`, `medium`, `high`, `xhigh`, `max`, `ultra`.
  Luna: the same through `max`; GPT-5.5: the same through `xhigh`.
  No role uses `ultra` or automatic delegation; one-child policy remains binding.
- Catalog: mapped families advertise `priority`/Fast; Sol also advertises `ultrafast`.
  Current child interface exposes priority. No FAST override or latency/price guarantee.
- Native protocol schema exported locally with `codex app-server generate-json-schema
  --experimental --out <temporary-directory>`. Native fields were checked using a
  strict app-server startup with stdin EOF and no agent/model request, in a disposable
  credential-free CLI configuration context. An unknown-key negative control is required.
  This avoids conflating project validity with existing unrelated global configuration.
  The current global config's `model_supports_reasoning_summaries` is rejected by strict
  mode in this CLI; no global repair was attempted or required for isolated project checks.
- `.codex/config.toml` is native trusted-project configuration; `.agents/supervisor.json`
  and the role pool are Core inputs, not native configuration extensions. Existing parent
  model/effort is not changed by writing this profile; reconcile effective settings when
  starting the next run. The bootstrap parent's exact model/effort was not independently
  observable; recommendations are not execution telemetry.

## Validation contract

Core 1.4.0 supplies no dedicated full-manifest validator executable/schema. Its
`scripts/supervisor_store.py` validates storage/schema-version/paths, and its supplied
tests validate storage, policy gate preservation, monitor and notification behavior.
Use those unchanged, plus explicit project-contract JSON/TOML/model-mirror checks.
Do not call a partial storage check a full global-manifest validation.

Acceptance checks: manifest paths resolve inside this repository; Core version/features
compatible; required fields/types/enums valid; pool/native defaults agree; every model
and effort appears in both catalog and callable selection; strict native parse succeeds
and unknown-key control fails; risk evidence traces to project sources; all routing
simulations below meet global floors; supplied tests pass; exact feature hashes and Git
diff remain unchanged; staged/committed paths contain only the five bootstrap files.
No new validator/orchestration implementation is installed in the repository.

Bootstrap verification results: project contract/path/risk/type checks PASS; complete
pool/catalog/effort/native-mirror checks PASS; exact written native profile strict parse
PASS, unknown-field negative control PASS; global read-only storage resolver PASS.
Supplied Core tests: storage 14/14, policy 2/2, monitor with this pool 13/13, notifications
with fake delivery 12/12 (41 passed). The opt-in actual-workspace bookkeeping test was
skipped: it creates a live run record; no live run is required for bootstrap compatibility.
The disposable storage tests cover that contract without registering a project run.
Routing simulations A-J: 10/10 qualitative contract review PASS, no model execution.
Final config scope/security review: PASS; no credentials, permission expansion, copied
Core implementation or application behavior changes. Pre-commit preservation check:
all 16 feature SHA-256 hashes and the original binary Git diff match; index was empty
before explicitly staging bootstrap paths. `git diff --check`: PASS.

## Risk basis

`high` / `conservative`: public credential and application trust boundaries plus shared
multi-product runtime and SQL Server/provider blast radius justify a confidence margin.
This does not make every edit T4. Clearly isolated documentation remains T0. There is
no verified safety-critical/legal domain requiring a blanket critical classification.
Current runtime source takes precedence over older "foundation"/single-profile wording
in README or architecture records; recovery history is identified as history.

## Safe routing simulations

These are Supervisor qualitative contract simulations, not dispatched agents or measured
performance. A-O are the installed Core's fifteen dimensions; no arithmetic score is
used. Each scenario assumes only the concrete effects below. Unmentioned dimensions
are not silently low: their grouped reasons are stated in the following evidence table.

| Scenario | Tier / role / model / effort | Verification and decision | Permission gate |
| --- | --- | --- | --- |
| A. Exact synchronization of an approved descriptive config heading | T0 / mechanical / Luna / low | V0 exact comparison; no behavioral config keys | Authorized local config only |
| B. Small established display-safe mapper with one consumer and fixtures | T1 / fast / Luna / medium | V1 targeted tests + Supervisor diff review | Authorized local implementation |
| C. Bounded shared summary formatter retaining accepted contracts | T2 / standard / Terra / medium | V2 relevant regression + Supervisor; no persisted semantics | Accepted bounded scope |
| D. Cross-module lifecycle refactor with accepted architecture | T3 / senior / Sol / high | V3 independent Astra/xhigh; decompose invariants first | Shared consumer/compatibility gate |
| E. Change pairing/device trust enforcement | T4 / critical / Astra / xhigh | V4 independent Astra/xhigh; Supervisor retains decision before implementation | Explicit auth/security change scope |
| F. Material bilingual responsive admin UX on existing APIs | T2 / standard / Terra / medium, Impeccable hosted by Sol/high when sufficient | V2 technical + interactive runtime + Impeccable pre/final; raise tier for discovered complexity | Runtime tool absence pauses; no provider mutations |
| G. Mapper initially appears isolated, then ENPO/NQRB/LAMMA consumers and lifecycle coupling emerge | T1 -> T3 / fast -> senior / Luna medium -> Sol high | V1 -> V3 independent Astra/xhigh; stop expansion and re-score | Reconcile consumer scope; no implicit new-path approval |
| H. Accepted critical decision leaves exact mechanical adapter updates | Parent T4; child T0 / mechanical / Luna / low | Retain parent V4 and independent review; accepted invariants constrain exact diff | Original auth/shared gates retained |
| I. Critical role unavailable and actual Supervisor also insufficient/unavailable | T4 / no executor/model/effort selected | BLOCKED / SUFFICIENT_AGENT_NOT_AVAILABLE; no weaker fallback or self-verifier | Restore sufficient role/context |
| J. Production deployment, SQL mutation or live provider request | T4 / critical decision / Astra / xhigh | V4; accepted artifact/target/recovery proof before proposal | PAUSED_AT_GATE without explicit operation approval, irrespective of model |

Model shorthand in the table resolves only through `model-pool.json` to exact IDs.
Independent reviewer is always a separate non-implementing context, sequential within
one-child capacity. Critical role unavailability may be retained by a demonstrably
sufficient actual Supervisor per Core, but that context cannot verify its own decision.

| Scenario | A-O evidence (L/M/H = low/moderate/high; C is determinism, not risk) |
| --- | --- |
| A | A/B low and C high: exact approved text. D/E/F/G/H/I/J/K/M low: one reversible nonbehavioral artifact, no persistence/security/remote effects. L/N low: exact diff/local context. O low: no UI/domain review. |
| B | A low/B low/C moderate: known mapper pattern with small judgment. D/E/F/G/H/I/J/K/M low: one reversible consumer, no boundary/data/remote effect. L low/N moderate: fixtures and module conventions. O low: no material UI or domain gap. |
| C | A moderate/B low/C moderate: defined shared formatting semantics. D/J/N moderate: known bounded consumers. E/F/G/H/I/K low: reversible existing contract, no storage/trust/provider change. L/M moderate: consumer regression matters. O low: no material UI/domain gap. |
| D | A/D/J/L/N high: interacting module lifecycle invariants and regression burden. B/C/F/K moderate: bounded gaps/adapted implementation, accepted boundaries. E moderate: coordinated rollback. G/H moderate: existing scoped delivery and persisted semantics; no new trust/schema decision. I low: local tests only. M high: shared delivery disruption. O moderate: security/data review scope assessed. New trust/persistence boundary would raise to T4. |
| E | A/F/G/M/N high: changed trust decisions with security failure cost. B/J/L high: enforcement interactions and negative-path proof. C low: design judgment. D high: shared identity consumers. E/H/K moderate: coordinated compatibility, existing persistence, adapted enforcement. I low: local design/tests only. O high: independent security/invariant review. |
| F | A/D/F/J/L/N moderate: established admin composition with several UI states/consumers. B low/C moderate: clear UX brief with design judgment. E/G/H/I/K/M low: reversible display-only work, existing APIs and mocked effects. O high: mandatory Impeccable + runtime accessibility/RTL/responsive checks. |
| G | Initially B evidence; discovered D/J/N/M high and L high: multiple products and lifecycle regressions. A/B/F/K become moderate/high from interacting invariants; C becomes mixed. E/H/G moderate pending investigation; I low for local work. O moderate: assess domain review. Consequential unknown trust/data boundary retains provisional T4 until resolved; T3 applies only after proving boundaries unchanged. |
| H | Child A/B low/C high, E/L low: exact updates after accepted design. Child D/J/N moderate: explicit adapters with fixed invariants. Child F/G/H/I/K low: no remaining decision or live action. Parent D/F/G/M/N high retained for V4. O high at parent: independent decision/security verification remains required. |
| I | E scenario risk dimensions retained; L/O high additionally because required proof/context is unavailable. High C or lower-cost availability cannot cancel F/G/M. No assignment occurs. |
| J | D/E/H/I/M high: production/remote mutation with costly recovery. F/G high when trust/schema enforcement changes, otherwise moderate. A/B/J/K/N moderate/high until exact target, artifact and semantics resolved; C high only after commands fixed. L/O high: real-state proof and independent critical review. No mutation is simulated as executed. |

Routing results are reviewed against Core `references/agent-selection.md`, including
escalation, down-shift with retained verification, and permission independence.

## Operator handoff

Recovery baseline: branch `fix/shared-notification-lifecycle`, HEAD/main/cached origin/main
`615de7cfc0f758b1d728311c97a1e432ea4bd9a4`, no staged files, 15 modified backend files plus
`backend/tests/BotGlobal.UnitTests/Pairing/PushDestinationLifecycleTests.cs` untracked.
The authorized bootstrap commit changes branch HEAD only; main and the feature bytes
remain untouched. Preserve the feature until its own final acceptance/commit gate.

The global monitor consumes canonical external run state under
`/Users/ashraffarag/Repo/.codex-runs/bot-global-platform/<run-id>/`. Future enabled runs
must use the Core resolver with actual approved workspace roots; publish actual/unknown
agent telemetry, not fabricated simulation rows. No live feature registry is created by
bootstrap. Synthetic monitor tests use this pool. L2 sounds remain global: COMPLETED
Glass; action/pause Ping; blocked/escalation Funk; failed-safe Basso. Fake-delivery tests
establish compatibility, not actual banner/sound delivery. No project sound overrides.

No blanket VPN dependency: internal producer/optional adapter boundaries are documented
in AGENTS.md. No remote connectivity test occurred during bootstrap.

Historical lifecycle evidence (not rerun by bootstrap): Lifecycle 57/57, Notifications
145/145, Backend 534/534, Architecture 8/8, Outbox 76/76, Migration/ScriptDom 53/53,
Security PASS; no migration, mobile or web source changes expected.

Next run: Shared Notification Lifecycle final checkpoint review and acceptance under
SUPERVISED mode. Do not restart implementation or select Phase 1B before lifecycle code
and its separately gated production validation genuinely close. The bootstrap stops here.
