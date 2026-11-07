# Publishing

The release workflow builds one jar per supported Minecraft version and loader and attaches all target jars plus a SHA-256 checksum file to one GitHub Release. After creating the GitHub Release, it automatically dispatches both the Modrinth and CurseForge publishing workflows. Both publishers verify each jar, attach release notes as the changelog, configure dependencies, and can also be triggered manually via `workflow_dispatch`.

Neither workflow edits project page descriptions, galleries, categories, or other project settings. Keep that project information up to date on Modrinth and CurseForge.

## Artifacts per target

Each target row in `gradle/minecraft-targets.json` carries its loader, and the built artifact keeps that loader: the Fabric jar is `soullink-mc<minecraft>-<mod version>.jar` and the NeoForge jar is `soullink-mc<minecraft>-neoforge-<mod version>.jar`. The Gradle `archivesName` follows the same split, and each row builds with its own toolchain properties (Loom, Fabric Loader and Fabric API for Fabric rows; ModDevGradle and NeoForge for NeoForge rows).

Fabric jars bundle Terra Rasa as a jar-in-jar through Loom's `include`, exactly as before. NeoForge jars cannot: ModDevGradle has no jar-in-jar equivalent of `include`, and one jar cannot declare two mods, so `META-INF/neoforge.mods.toml` declares `terrarasa` as a required dependency and the NeoForge file is installed as its own mod.

## Modrinth

### One-time setup

1. Create and configure both Modrinth projects. Set their source links to:
   - Soul Link: https://github.com/qwasdf-1/soul-link
   - Terra Rasa: https://github.com/qwasdf-1/terra-rasa
2. Create a Modrinth token with `PROJECT_READ` and `VERSION_CREATE` permissions. `PROJECT_READ` lets the publisher verify the project source links; `VERSION_CREATE` lets it upload versions.
3. In the Soul Link GitHub repository, add the Actions secret `MODRINTH_TOKEN` with that token.
4. Add these repository Actions variables with the project IDs:
   - `MODRINTH_PROJECT_ID` for Soul Link.
   - `MODRINTH_TERRA_RASA_PROJECT_ID` for Terra Rasa.
5. Run the Publish to Modrinth workflow from Actions and enter an existing release tag.

The workflow checks that each project links back to its expected GitHub repository. It also resolves Fabric API's project ID for Fabric files and NeoForge's project ID for NeoForge files from Modrinth's public API. IDs remain stable if project slugs change.

### Versioning and compatibility

The existing `v0.1.0` and `v0.2.0` versions remain `dedicated_server_only`; `v0.3.0` remains `client_and_server`. Starting with `v0.4.0`, new versions use Modrinth's `server_only` environment: Soul Link runs its gameplay on the server and also supports integrated single-player servers. Soul Link builds against Terra Rasa, bundled on Fabric and a required dependency on NeoForge; keep `terra_rasa_version` in `gradle.properties` matched to the Terra Rasa release and merge and release the library update before the Soul Link update.

Push a version tag that matches `mod_version`. The release workflow builds all target jars, attaches them with a SHA-256 checksum file, then dispatches the Modrinth publisher. The publisher derives its targets from the attached jars, split by Minecraft version and loader, and creates one version per target: the version number is `<mod version>-mc<minecraft>` for Fabric files and `<mod version>-mc<minecraft>-neoforge` for NeoForge files, so both loaders of one Minecraft version stay separate Modrinth versions, and the display name carries the same `NeoForge` marker. `loaders` is `["fabric"]` for Fabric files and `["neoforge"]` for NeoForge files, `game_versions` lists the single Minecraft version, and the required dependency is Fabric API on Fabric files and the NeoForge loader itself on NeoForge files. Terra Rasa is marked as an embedded dependency for Fabric files because Soul Link bundles the library in those jars, and as a required dependency for NeoForge files because those jars declare it instead. The workflow does not change project-level Modrinth settings; update the project environment separately when needed.

If a GitHub Release already exists and needs to be published on Modrinth, run Publish to Modrinth manually with its tag. A retry skips a target version that already matches its release jar and metadata; it stops if the existing version differs.

### Checks and failures

The workflow stops if the tag does not match the mod version at that tag, if the expected runtime jar or checksum is missing, if the checksum fails, if the attached jars do not match the Minecraft and loader targets in the tagged `gradle/minecraft-targets.json`, or if either Modrinth project's source URL points elsewhere. On a retry, an existing target version is skipped only if its metadata — including its loader and required dependency — and its file hash match the release asset; a conflicting version fails instead of being overwritten. The Modrinth token is used to read the project metadata and create versions.

## CurseForge

### One-time setup

1. Create and configure the Soul Link project on CurseForge. Note its numeric Project ID from the project overview page.
2. Generate an API token from your CurseForge account settings under **API Tokens**.
3. In the Soul Link GitHub repository, add the Actions secret `CURSEFORGE_TOKEN` with that token.
4. Add the numeric project ID as the Actions variable `CURSEFORGE_PROJECT_ID`.
5. (Optional) If the Terra Rasa project slug on CurseForge differs from `terra-rasa`, set the Actions variable `CURSEFORGE_TERRA_RASA_SLUG`. If Terra Rasa is not published or should not be linked, set it to `none`.
6. (Optional) If custom game version ID mappings are required, set `CURSEFORGE_GAME_VERSIONS` as a JSON map of Minecraft versions to integer arrays (e.g. `{"1.21.11": [1234, 5678]}`).
7. (Optional) If the mod should be tagged exclusively for client or server environments, set the Actions variable `CURSEFORGE_ENVIRONMENT` to `client` or `server` (defaults to both `client,server`).
8. Run the Publish to CurseForge workflow from Actions and enter an existing release tag, or allow the Release workflow to dispatch it automatically on new release tags.

### Release behavior and dependencies

The CurseForge publisher resolves target game versions (the Minecraft version, the matching mod loader — Fabric for Fabric builds and NeoForge for NeoForge builds — Java versions where registered, and mandatory Environment tags Client and Server) against CurseForge's game versions API. Each target jar is published as a distinct release file with display name `Soul Link <version> (Minecraft <target>)` for Fabric builds and `Soul Link <version> (Minecraft <target> NeoForge)` for NeoForge builds.

Dependencies are configured in file metadata:
- On Fabric builds, Fabric API (`fabric-api`) is declared as a `requiredDependency`.
- On NeoForge builds, the NeoForge loader (`neoforge`) is declared as a `requiredDependency`, and Terra Rasa is a required rather than embedded dependency because those jars do not bundle it.
- On Fabric builds, Terra Rasa (default slug `terra-rasa`) is declared as an `embeddedLibrary` because Soul Link bundles the library in those jars. If the related project is not accessible on CurseForge (e.g. still under review), the upload retries safely without the embedded library relation.

### Checks and failures

Publishing stops if the tag does not match `mod_version`, an expected target jar or SHA-256 checksum is missing, checksum verification fails, the attached jars do not match the Minecraft and loader targets in the tagged `gradle/minecraft-targets.json`, the CurseForge token is invalid or unauthorized for the project, or the CurseForge upload API rejects an upload. Retrying an upload safely skips target jars that were already uploaded to CurseForge.
