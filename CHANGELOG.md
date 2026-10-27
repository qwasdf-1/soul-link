# Changelog

## Unreleased

## 0.2.0

- Add builds for Minecraft 1.21.11 and 26.1 through 26.3, with one release jar per game version.
- Expand the player, operator, and configuration documentation, including the available run rules and restart behavior.
- Group mod commands under `/soullink` and add `/soullink help`; remove the old root aliases.
- Start the first run automatically when its spawn is ready, with phase-based preparation updates and operator notices for failures.
- Add timer pause/resume, half-heart mode, natural regeneration control, shared damage messages, and configurable pregeneration radii.
- Add a durable FIFO pool of up to three prepared future runs, with `/soullink setup pool <futureRuns>` to choose its size.
- Reduce staged-world preparation spikes with Terra Rasa's optional C2ME integration, which skips diagnostic density-function graph generation while keeping the compiler enabled.
- Build against Terra Rasa 0.2.0 and publish separate target versions for each supported Minecraft release.

## 0.1.0

The first public release, for Minecraft 26.2 on Fabric, built on Terra Rasa 0.1.0.

- Cooperative runs in freshly seeded canonical worlds, swapped in while players wait in a persistent lobby.
- Shared health, hunger and saturation; any death ends the run; killing the Ender Dragon wins.
- Shared potions, off by default.
- A run timer, late join, and `/start`, `/reset`, `/stoprun` and `/runinfo`.
- The next run is prepared and its area pregenerated in the background; `/start` and `/reset` never wait for pregeneration.
- Runs survive graceful restarts and crashes.
