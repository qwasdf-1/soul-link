# Product and gameplay backlog

This document tracks requested Soul Link improvements and their implementation status. The 26.2 WTHIT/C2ME transition issue remains open pending further diagnosis.

## Operator onboarding and first run

### Agreed behavior

- Onboarding and setup prompts are for server operators.
- On a new server, Soul Link prepares the first run automatically and starts it as soon as the spawn is ready. This automatic start applies only to the initial run; later runs still wait for `/soullink start`.
- Operators receive a one-time onboarding message on first join.
- `/soullink setup` shows and updates optional pregeneration radii per dimension and the prepared-run pool size. The active run is excluded from the pool.

### Implemented in current work (server verification pending)

- The initial run auto-starts after preparation.
- `/soullink help` and the consolidated `/soullink` command tree cover start, reset, info, status, stop, prepare, and rules. The former top-level `/start`, `/reset`, `/stoprun`, and `/runinfo` aliases are removed.
- `/soullink setup` displays and updates per-dimension pregeneration radii and a FIFO pool of one to three prepared future runs. The default is one; `/soullink setup pool <futureRuns>` changes the pool size. Teammates receive a health-loss message naming affected players and showing the change and resulting shared health.
- `/soullink rules naturalRegeneration <true|false>` toggles vanilla's world-persisted game rule immediately without creating a second Soul Link setting.
- `/soullink rules halfHeart <true|false>` saves the next-run option. Half-heart mode sets each participant's maximum and the shared pool to 1 HP; prior max-health base values are restored when returning to the lobby.
- `/soullink pause` and `/soullink resume` are operator-only and pause only the timer. World simulation and shared vitality continue normally, including across server restarts.
- `/soullink status` is readable; machine-readable lifecycle output remains under `/soullink debug lifecycle`.
- Preparation progress names its current phase. Soul Link reports on phase changes and repeats an unchanged phase every 30 seconds rather than every ten ticks. Operators receive a recovery hint and issue-report link when preparation fails.
- A fresh run shows a clear ready message when it starts.
- Terra Rasa no longer lets the overall preparation deadline interrupt flushing once the usable spawn is ready. Each staged-level close and manifest publication still have bounded drain timeouts; the overall timeout stops optional pregeneration and remains fatal in earlier required phases.

### Requested behavior

- Offer an operator setup flow for optional pregeneration, including which dimensions to pregenerate and how much work to schedule. The current command shows and changes per-dimension radii; the first-join message links operators to `/soullink help` and `/soullink setup`.

### Remaining setup work

- An interactive prompt for pregeneration choices is not part of the current command flow. Operators can set per-dimension radii and pool size directly; revisit an interactive wizard separately if needed.

## Command experience

### Requested behavior

- Make `/soullink` the single command root, with subcommands for existing actions such as `start`, `reset`, `stop`, `info`/`status`, `prepare`, and `rules`.
- `/soullink help` lists each command's purpose, permission, arguments, defaults, and examples.
- Present status and other command results as readable text instead of raw JSON.
- Send progress updates at regular intervals while a run is being prepared, and a clear completion message. Include pregeneration progress when it is enabled.
- Report failures prominently to operators with the failed step and a useful recovery action; include a project issue-report link when appropriate.
- Add a pause/resume capability.
- Consider clearer shared-damage messages.

### Decisions to settle during implementation

- Whether damage messages should identify the attacker; current messages identify affected player(s), health lost, and resulting shared health.

## Gameplay rules

### Requested behavior

- Add a half-heart mode by setting each participant's maximum health to one health point (half a heart), while keeping the team health behavior understandable and consistent.
- Let operators enable or disable natural regeneration using Minecraft's vanilla `naturalRegeneration` game rule.

### Decisions to settle during implementation

- Confirm half-heart behavior in a live run, including healing, late joins, respawns, modded max-health bases, and restoration on lobby return.
- Revisit the half-heart default and migration behavior if operators want it enabled by default; current default is off to preserve existing gameplay.

## Platform support

- Integrated-server host support is included in the coordinated 0.3.0 release across all six targets in `gradle/minecraft-targets.json`. Vanilla LAN guest login and its custom-dimension/world-swap synchronization still need a rendered-client check. Keep server-owned run state scoped to each world's `LevelResource.ROOT`.

## Lobby and ended-run performance

### Chosen lobby recovery behavior

- Add a barrier perimeter around the lobby platform to prevent ordinary falls.
- Keep a low-frequency rescue backstop that teleports lobby players below the
  platform back to spawn after unusual movement or future platform changes. Do
  not rely on players falling forever.
- Rescue a player when the void-damage event occurs, with lobby damage
  protection adjusted so this rescue is not canceled.
- This is a design choice; implementation and rendered-client verification are
  still pending. Current code builds the platform with a polished-blackstone
  edge and rescues lobby players below Y=0 every five server ticks.
- The periodic backstop should run much less often than the current five-tick
  check; choose its cadence during rendered-client verification.

### Open performance questions

- The lobby remains a registered dimension so it is available for the first
  run, transitions, and `/soullink stop`. A two-client 26.2 comparison found
  that disabling Soul Link's platform ticket did not change the lobby's 25
  loaded / 1 block-ticking chunk counts or improve measured server percentiles.
  Retain the ticket; the source of the loaded chunks without it is still unknown.
- Ended dimensions currently continue normal gameplay until the next world
  replacement. Keep post-victory exploration and vanilla exit portal/credits
  behavior. After defeat, the desired behavior is to stop world simulation
  while allowing spectators to fly; establish whether selective simulation
  pausing preserves player ticks and mod safety before implementation.

## Run transition feedback

- A swap moves players to the lobby, saves and retires the old dimensions,
  promotes prepared world data, reconstructs and validates the new dimensions,
  then sends players into the new run. This is a synchronous server-thread
  operation; the server cannot process ordinary game ticks during activation.
- Add clear player-facing status before the blocking part and after the new run
  is ready. Current operator logs already include phase timings. A separate
  message for every internal phase may not be visible until the blocked server
  thread resumes, so validate client delivery before promising live progress.
- The operations reference records the paired measurement and its limits. The
  26.2 two-client probe measured Soul Link idle work at about 15.8 microseconds
  mean and 112 microseconds p99; treat it as a local baseline, not a guarantee
  for other servers or modpacks.

## Acceptance notes

- All operator setup and administration features must be usable from the dedicated server console as well as in-game by an operator where applicable.
- Player-facing messages should avoid raw JSON and explain the next action plainly.
- Automated preparation must not silently leave players waiting: progress, completion, and failure need distinct outcomes.
- Add integration coverage for onboarding state, command permissions and output, pause/resume behavior, rule behavior, and first-run auto-start as those features are implemented.
