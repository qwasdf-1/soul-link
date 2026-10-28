# Changelog

## Unreleased

## 0.4.0

### Added

- Add an operator-configurable shared-vitality rule. With it off, normal worlds use vanilla respawns and vanilla hardcore ends after a team wipe; the separate Soul Link Hardcore rule ends any run on the first participant death.

### Changed

- Refresh the displayed run timer every 0.1 seconds.
- Bundle Terra Rasa 0.3.1, including the Minecraft 1.21.11 legacy Overworld activation fix.

### Fixed

- Report asynchronous state-save failures in the server log and show the correct continuation message after an unlinked normal-mode death.

## 0.3.0

### Added

- Enable the shared server entrypoint on integrated-server hosts for single-player and LAN play across all six supported Minecraft targets. A rendered 26.2 single-player smoke test passed through first-run activation.

### Changed

- Publish each target as client-and-server compatible on Modrinth so the integrated-server host can install Soul Link in its client profile.
- Bundle Terra Rasa 0.3.0, which enables the shared lifecycle entrypoint on integrated servers.

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
