# Current state

This page is a compact implementation summary. The exact supported game and
toolchain versions live in `gradle/minecraft-targets.json`.

## Implemented

- Shared vitality defaults on and is an operator-configurable next-run rule;
  health, food, and saturation remain per-player when it is off. Run timer, run
  records, end-of-run outcomes, late-join handling, and restart recovery.
- Death handling preserves the linked-run one-death defeat. In unlinked runs,
  vanilla normal mode permits respawns and vanilla hardcore ends on team wipe;
  the separate operator-configurable Soul Link Hardcore rule ends on first death.
- Persistent lobby, asynchronous preparation, optional pregeneration, and
  replacement of configured canonical dimensions through bundled Terra Rasa.
- The lobby platform has a polished-blackstone edge and a two-block-high
  barrier perimeter just outside it. Void damage immediately returns players
  to spawn; a five-second server tick check is the backstop. Its platform chunks
  remain loaded by a small ticket.
- A new server automatically starts its first run when the prepared spawn is
  ready; later runs still require `/soullink start`.
- `/soullink help`, a consolidated command root with the old top-level aliases removed, readable
  `/soullink status`, phase-based preparation notices, and operator failure
  messages with a recovery command.
- Per-operator first-join onboarding, an operator pregeneration setup command,
  readable damage-sharing notices, and a command for vanilla's persisted
  natural-regeneration game rule.
- FIFO queue of up to three durable prepared future runs, configured with
  `/soullink setup pool <futureRuns>`; the active run is not counted.
- Player-facing notices announce the world swap before activation and the new
  run after players arrive.
- Optional half-heart mode with restoration of participant max-health base
  values, plus operator timer pause/resume commands (world simulation continues).
- Per-target source overlays for the obfuscated 1.21.11 line and the modern
  26.x line.
- One versioned GitHub release with a separate jar and Modrinth version for
  each supported game target.
- Integrated single-player and LAN-host support shipped in 0.3.0 across all six
  targets. Version 0.4.0 adds configurable shared-vitality and first-death
  hardcore rules, with vanilla respawn/team-wipe behavior when vitality is off.
  A rendered 26.2 single-player smoke test passed through first-run activation;
  vanilla LAN guest login remains unverified.

## Known compatibility issue

A rendered-client comparison points to an interaction between WTHIT block-data
requests and C2ME during `/soullink reset`: the WTHIT client completed with C2ME removed,
and C2ME completed without WTHIT client requests (while its server integration
remained installed). WTHIT's singleton fluid provider may dereference a cache
bound to the retired run level. This is a leading hypothesis, not a confirmed
root cause. See the [operations reference](reference.md).
Headless integration clients do not exercise WTHIT's block-tooltip packet.

A separate operator log reported a Terra Rasa preparation timeout in `FLUSHING`.
The matching start and failure timestamps are exactly 900 seconds apart,
confirming the overall timeout fired. Source inspection found a likely edge
case where reaching the deadline during pregeneration enters `FLUSHING`, then
the next tick treats the expired deadline as fatal. The available Soul Link
status predates preparation and no phase-level status was logged, so the phase
at the deadline remains unknown.

The repeated `Preparing worlds… 97%` messages were Terra Rasa's fixed display
value for `FLUSHING`, sent by Soul Link every ten ticks. Preparation now shows
phase descriptions and only repeats a steady phase every 30 seconds. Terra
Rasa lets an already spawn-ready preparation finish flushing after its overall
deadline; staged-level close and manifest publication retain bounded drain
timeouts. Earlier required phases still fail at the overall deadline, which
stops optional pregeneration.

A focused real-server regression passed on Minecraft 26.2 with Java 25. It set
a 120-second preparation deadline and intentionally incomplete 800-block
overworld pregeneration; the run flushed and published after 120,651 ms, with
the manifest showing pregeneration incomplete. This verifies the reported
`FLUSHING` timeout path on 26.2. The test did not include WTHIT, C2ME, or
Waystones. The 1.21.11 target was not run because Java 21 is unavailable in
this environment. The earlier Waystones `Items` NBT type warning came from
C2ME worldgen.

## Requested work

The post-run `/soullink start` destination question and live confirmation of
half-heart composition remain open in `roadmap.md`. The new command, onboarding,
damage, timeout, and progress behavior still need server verification.

The lobby barrier, immediate void-damage rescue, and five-second periodic
backstop are implemented. The integration suite covers all four perimeter
sides and both rescue paths.

Whole-lobby unloading and freezing ended dimensions are not implemented.
The lobby remains a server dimension for transitions. Victory worlds remain
playable so vanilla portal and credits behavior works. Defeat freezing is
deferred: in both the 1.21.11 and 26.2 server implementations, `ServerLevel.tick`
drives the entity tick list (including players), chunk ticking, scheduled block
and fluid ticks, and block entities. Skipping that method would stop spectator
movement too; selectively pausing world simulation while preserving player and
mod behavior requires deeper, cross-version hooks that are not safe to ship
without broader compatibility testing. A 26.2 two-client comparison showed no
loaded-chunk or measurable tick-time reduction when Soul Link's lobby ticket
was disabled; keep the ticket because its explicit platform-load guarantee
remains useful.
