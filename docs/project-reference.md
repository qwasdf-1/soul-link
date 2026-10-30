# Project reference

Soul Link is a server-side cooperative challenge mod. Participants share
vitality, play a timed seeded run, and return to a persistent lobby when a run
ends or is replaced. The mod bundles Terra Rasa to prepare and replace game
worlds while keeping vanilla's canonical dimension identities.

## Supported targets

The active version-support branch builds one jar per exact target. The target
matrix in `gradle/minecraft-targets.json` is the authority for Minecraft, Java,
Fabric Loader, Fabric API, and Loom versions. Minecraft 1.21.11 uses the legacy
compatibility source set; 26.x uses the modern source set. Do not infer support
for an unlisted release from a matching major/minor version.

## Runtime behavior

- Participants share one health, food, and saturation pool. Incoming damage and
  food changes add together; simultaneous healing takes only the largest
  individual heal.
- Runs use fresh seeded Overworld, Nether, and End levels. The lobby and any
  explicitly persistent dimensions remain loaded.
- Terra Rasa prepares the next run and performs canonical world replacement.
  Soul Link owns the run state, player flow, rules, outcomes, and shared pool.
- The mod runs on dedicated servers and integrated servers (single-player and LAN). LAN participants can connect with vanilla clients; the host runs the mod.

## Where truth lives

| Need | Start here |
|---|---|
| Player-facing behavior and commands | `gameplay.md`, root README |
| Operator settings | `configuration.md` |
| Lifecycle, persistence, performance, compatibility | `reference.md` |
| Per-target toolchain | `gradle/minecraft-targets.json` |
| Shared world-preparation API | Terra Rasa's host integration and library reference |
| Requested product improvements | `roadmap.md` |
| Release, Modrinth, and CurseForge publishing | `publishing.md` |

Source, tests, target configuration, and actual release assets take precedence
over summaries and proposals.
