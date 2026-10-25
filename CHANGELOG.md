# Changelog

## 0.1.0

The first public release, for Minecraft 26.2 on Fabric, built on Terra Rasa 0.1.0.

- Cooperative runs in freshly seeded canonical worlds, swapped in while players wait in a persistent lobby.
- Shared health, hunger and saturation; any death ends the run; killing the Ender Dragon wins.
- Shared potions, off by default.
- A run timer, late join, and `/start`, `/reset`, `/stoprun` and `/runinfo`.
- The next run is prepared and its area pregenerated in the background; `/start` and `/reset` never wait for pregeneration.
- Runs survive graceful restarts and crashes.
