# Symphony execution and Critiques acceptance contract

This is the repository-local workflow contract for Bot Global Platform. It binds project
policy to Symphony execution evidence and Critiques final acceptance. It is declarative:
it does not install, fork or imitate Symphony or Symphony+, dispatch a worker, or create a
PASS automatically.

## External contracts reused

Symphony owns orchestration and execution. Symphony+ may normalize that work using its
existing strict contracts:

- `symphony-plus.execution-contract.v0.1`
- `symphony-plus.completed-run.v0.1`
- `symphony-plus.quality-review.v0.1`
- `symphony-plus.assessment.v0.1`

Symphony+ remains separately installed and upgradeable. This repository stores no copy of
its scheduler, lifecycle, assessment, storage, monitor or notification implementation.
The schemas are evidence formats, not proof that execution or review occurred.

## Required Symphony evidence

Each capability has a scoped execution contract. Every completed execution unit identifies
the project, task, contract, unit, run and revision and records its canonical workspace,
executor actor/session, effective sandbox and permissions, validation results and failure
state. Requested model or effort is not runtime proof.

Execution evidence is acceptable only when it matches the authorized capability and owned
paths and contains no tokens, credentials, raw provider payloads or unnecessary PII.

## Required Critiques evidence

Critiques is a non-implementing review context. Its final decision must record:

1. The correlated Symphony contract, unit, run and revision.
2. Reviewer actor/session and the executor actor/session against which independence is
   checked. They must differ for V3/V4 and wherever practical at lower risk.
3. The reviewed branch and full HEAD SHA.
4. The review base and revision kind: committed diff or staged index.
5. SHA-256 of the exact committed binary diff (`git diff --binary <base>...<head>`) or
   exact staged binary diff (`git diff --cached --binary`), plus the exact reviewed path
   list.
6. The validation commands/results relied upon, their freshness and any relevant artifact
   fingerprints.
7. All blocking findings and whether each is closed.
8. An explicit final verdict: PASS or FAIL.

The corresponding Symphony+ quality review must correlate to the same contract, unit,
run and revision. When Symphony+ assessment is used, final acceptance requires outcome
`ACCEPTED` with reason `ALL_REQUIRED_EVIDENCE_PASSED`. That assessment supplements the
Git-state binding above; it does not replace it.

## Freshness and invalidation

A Critiques PASS authorizes only the recorded state. It becomes stale immediately if any
of these changes:

- branch or HEAD;
- review base;
- revision kind;
- diff/index SHA-256 or reviewed path set;
- Symphony run or revision;
- validation evidence on which PASS relied;
- a previously closed blocking finding is reopened.

After invalidation, the changed delta requires a new Critiques review and PASS. A copied,
renamed, synthetic or self-authored PASS has no authority. Critiques never repairs the
delta it accepts; corrections return to Symphony execution and produce a new revision.

## One-time initial migration transition

The only exception to pre-existing Symphony execution evidence is the initial commit that
introduces this workflow contract. It is limited to branch
`chore/symphony-critiques-workflow`, base/current HEAD before commit
`284b8394acc16665e8cf3843d5621e24e948dd46`, and exactly these staged paths:

- `AGENTS.md`
- `.agents/SYMPHONY_CRITIQUES.md`
- `.agents/symphony-critiques.json`
- `.agents/model-pool.json`
- `.codex/config.toml`
- deletion of `.agents/supervisor.json`
- deletion of `.agents/BOOTSTRAP.md`

The exception requires explicit repository-owner migration and commit authorization; an
independent Critiques PASS for the final staged-index SHA-256, exact branch/HEAD and path
set; `git diff --cached --check` PASS; and proof that application source is untouched. No
Symphony evidence may be fabricated, synthesized or backfilled for this transition.

Creating the first qualifying commit exhausts the exception permanently. It cannot be
used for an amend, replacement, replay, cherry-pick or later commit, including after a
revert. Merge and push remain separately authorized. All post-migration commits use the
normal Symphony execution and Critiques acceptance contract without exception.

## Operation gates

Technical PASS and operation authorization are separate. A local commit additionally
requires explicit human authorization and an inspected index containing only owned paths.
Merge, push, deployment, database/provider writes, credentials operations and destructive
actions retain their separate authorization gates in `AGENTS.md`.
