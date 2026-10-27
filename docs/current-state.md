# Current state

This page is a compact implementation summary. The exact supported game and
toolchain versions live in `gradle/minecraft-targets.json`.

## Implemented

- Shared health, food, saturation, damage, run timer, run records, end-of-run
  outcomes, late-join handling, and restart recovery.
- Persistent lobby, asynchronous preparation, optional pregeneration, and
  replacement of configured canonical dimensions through bundled Terra Rasa.
- The lobby platform currently has a polished-blackstone edge, its platform
  chunks are kept loaded by a small ticket, and a server tick check rescues lobby
  players below Y=0 every five ticks.
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
- Optional half-heart mode with restoration of participant max-health base
  values, plus operator timer pause/resume commands (world simulation continues).
- Per-target source overlays for the obfuscated 1.21.11 line and the modern
  26.x line.
- One versioned GitHub release with a separate jar and Modrinth version for
  each supported game target.
- Integrated single-player and LAN-host support is prepared for the coordinated
  0.3.0 release across all six targets. A rendered 26.2 single-player smoke
  test passed through first-run activation; vanilla LAN guest login remains
  unverified.

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
stops optional pregeneration. These changes are implemented but have not been
verified in a server run. A separate Waystones `Items` NBT type warning came
from C2ME worldgen.

## Requested work

The post-run `/soullink start` destination question and live confirmation of
half-heart composition remain open in `roadmap.md`. The new command, onboarding,
damage, timeout, and progress behavior still need server verification.

The chosen lobby-safety follow-up is a barrier perimeter, void-damage rescue,
and a slow periodic rescue backstop. It is not implemented yet.

Whole-lobby unloading and freezing ended dimensions are not implemented.
The lobby remains a server dimension for transitions. Victory worlds remain
playable so vanilla portal and credits behavior works. The desired defeat
behavior is to stop world simulation while keeping spectator movement; the
implementation needs to establish whether that can be done without disabling
player ticks. A 26.2 two-client comparison showed no loaded-chunk or measurable
tick-time reduction when Soul Link's lobby ticket was disabled; keep the ticket
for now because its explicit platform-load guarantee remains useful.
