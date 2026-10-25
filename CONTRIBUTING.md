# Contributing

Thanks for helping improve Soul Link. The world swapping lives in [Terra Rasa](https://github.com/qwasdf-1/terra-rasa); changes to preparing, swapping or resetting worlds belong there. This repository holds the game: the lobby, runs, shared vitality, commands and run state, starting at [RunCoordinator](src/main/java/dev/soullink/run/RunCoordinator.java).

## Build And Test

You need Java 25 and a Terra Rasa checkout next to this repository, as `../terra-rasa`. The integration suites also need [uv](https://docs.astral.sh/uv/), which installs Python 3.14 for them.

```sh
./gradlew build             # mod and JUnit tests, built against ../terra-rasa
./gradlew integrationTest   # real dedicated-server suite, about 25 minutes
./gradlew modpackTest       # the same harness with the reference modpack, about 7 minutes
./gradlew integrationTest -Ppytest.args="-s tests/test_02_gameplay.py"   # one module
```

CI runs `./gradlew build` on every push and pull request; both server suites run on manual dispatch. Run them locally before opening a pull request that changes gameplay or the run lifecycle.

## Changes

- Gameplay a player can observe needs an integration test with the headless clients in [testing/tests](testing/tests). Each module fails on unexpected `ERROR` lines in the server log.
- Soul Link's own work per tick is measured (`/soullink status`, `tickCost`) and bounded by `test_10_performance`. Keep idle work event-driven.
- Run state that must survive a restart goes into the saved state, with a case in `test_11_persistence`.
- Record user-visible changes under `Unreleased` in [CHANGELOG.md](CHANGELOG.md).

## Licensing

Contributions are licensed under the Apache License 2.0. Soul Link reimplements the game played by [Soul Link Speedrun](https://github.com/zenzty/Soul-Link-Speedrun), which is licensed under the GPL-3.0. Do not copy or adapt its code; its documented behaviour may guide features.
