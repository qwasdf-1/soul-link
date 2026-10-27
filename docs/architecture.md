# Architecture map

Soul Link coordinates a run; Terra Rasa owns safe staging and canonical level
replacement.

1. `SoulLinkInitializer` loads config, initializes the run coordinator, and
   registers commands and lifecycle callbacks.
2. `RunCoordinator` persists lifecycle state, tracks the active and prepared
   runs, and drives the shared-vitality and timer behavior.
3. `LobbyService` keeps participants in the persistent lobby during a swap and
   moves them into the prepared run after activation.
4. Terra Rasa prepares staged levels, preloads spawn areas, optionally
   pregenerates, and promotes levels under vanilla dimension keys.
5. `src/versions/legacy` and `src/versions/modern` isolate Minecraft API and
   mapping differences. Shared game and run logic belongs under `src/main`.

Read `gameplay.md` for player flow, `configuration.md` for operator settings,
and `reference.md` for reset, persistence, performance, and compatibility
contracts. Keep world lifecycle ownership in Terra Rasa; avoid duplicating its
staging or recovery mechanisms in Soul Link.
