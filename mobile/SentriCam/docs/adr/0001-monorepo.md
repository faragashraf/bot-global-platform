# ADR 0001: Platform-root monorepo

- Status: Accepted
- Date: 2026-07-31

## Decision

Keep SentriCam in one Git repository with independent top-level platform roots: `android/`, `server/`, and future `web/`. Shared documentation, scripts, tools, and repository automation remain at the root. Preserve platform-native build systems rather than introducing a cross-platform build orchestrator.

## Context

Android Gradle files and the .NET solution previously shared the repository root. That worked initially but made ownership, generated-output paths, IDE opening, and platform build instructions ambiguous. The project needs a stable structure before adding a web client or more automation.

## Consequences

- Android and backend developers can open and build only their platform root.
- Platform generated outputs stay contained under the platform directory.
- Root documentation can describe cross-platform contracts and deployment models.
- Existing files move with Git history and keep package/namespace names.
- Root-level scripts and CI must use the new working directories.
- A change spanning contracts may still require coordinated Android and server validation.

## Alternatives

- **Keep mixed build roots:** rejected because ownership and future expansion remain unclear.
- **Split into multiple repositories:** rejected because cross-platform contracts and early coordinated delivery benefit from one history.
- **Adopt a monorepo build tool now:** rejected as unnecessary complexity without current multi-language orchestration requirements.
