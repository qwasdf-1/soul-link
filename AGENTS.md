# Soul Link Agent Guide

Soul Link is a server-authoritative Fabric mod for linked player health and
rotating seeded-world runs. It owns run state, gameplay rules, commands, and
player movement on dedicated and integrated servers. The Terra Rasa library
owns world preparation and canonical-world replacement.

## Read first

- `README.md` and `docs/README.md` for setup and navigation.
- `docs/project-reference.md`, `docs/current-state.md`, and
  `docs/architecture.md` for implementation scope and ownership.
- `docs/gameplay.md` and `docs/configuration.md` before changing player-facing
  behavior or operator settings.
- `docs/reference.md` and `docs/publishing.md` for reset semantics, modpack
  compatibility, target builds, and release publication.
- `gradle/minecraft-targets.json` and the matching `src/versions/<target>/`
  overlay for version-specific code.

## Project rules

- Keep shared run and gameplay behavior in common source. Put Minecraft API
  differences in the legacy or modern version overlay, and keep behavior
  equivalent across supported targets.
- Keep world staging, activation, and crash recovery in Terra Rasa. Soul Link
  decides when to prepare or activate a run, where players go, and which game
  rules or session state to reset.
- Treat player health, run completion, restart recovery, and world replacement
  as server-owned state. Do not add client-only authority for those behaviors.
- Keep operator commands available to the dedicated-server console where the
  command's function permits it. Present readable text to players rather than
  raw serialized JSON.
- The exact target matrix is `gradle/minecraft-targets.json`: Minecraft
  1.21.11 and 26.1, 26.1.1, 26.1.2, 26.2, and 26.3. Builds and releases are
  per target; one GitHub release may contain the jars for all targets, while
  Modrinth receives a distinct version for each game version.
- Preserve the pinned reference-modpack evidence. Update the lock and the
  compatibility reference together when intentionally changing its mod set.

## Documentation and operator records

- `AGENTS.md` is canonical; root `CLAUDE.md` is a compatibility symlink.
- Keep implementation claims in maintained reference pages and tie them to
  source, tests, target configuration, or captured integration evidence.
- Keep requested but unimplemented features in `docs/roadmap.md`.
- `docs/issue-ledger.md`, `docs/open-work.md`, `docs/TODOS.md`, and
  `docs/work-register.md` are local operator records, excluded per checkout.
  Record observations separately from confirmed causes; do not close issues
  without operator confirmation.
- Update the documentation map and relevant focused reference whenever runtime
  behavior, target support, ownership, or release behavior changes.

## Verification

Use the focused Gradle or integration checks for changed behavior. Changes to
common compatibility code must be checked on both the 1.21.11 and 26.x source
families. The optional rendered-client test documents the WTHIT/C2ME world
transition reproduction; do not treat it as proof of root cause without an
isolating comparison.
