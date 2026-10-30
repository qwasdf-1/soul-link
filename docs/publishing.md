# Publishing

The release workflow builds one jar per supported Minecraft version and attaches all target jars plus a SHA-256 checksum file to one GitHub Release. After creating the GitHub Release, it automatically dispatches both the Modrinth and CurseForge publishing workflows. Both publishers verify each jar, attach release notes as the changelog, configure dependencies, and can also be triggered manually via `workflow_dispatch`.

Neither workflow edits project page descriptions, galleries, categories, or other project settings. Keep that project information up to date on Modrinth and CurseForge.

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

The workflow checks that each project links back to its expected GitHub repository. IDs remain stable if project slugs change.

### Versioning and compatibility

The existing `v0.1.0` and `v0.2.0` versions remain `dedicated_server_only`; `v0.3.0` remains `client_and_server`. Starting with `v0.4.0`, new versions use Modrinth's `server_only` environment: Soul Link runs its gameplay on the server and also supports integrated single-player servers. Soul Link bundles Terra Rasa; keep `terra_rasa_version` in `gradle.properties` matched to the Terra Rasa release and merge and release the library update before the Soul Link update.

Push a version tag that matches `mod_version`. The release workflow builds all target jars, attaches them with a SHA-256 checksum file, then dispatches the Modrinth publisher. The publisher creates one version per Minecraft target, with the matching game version, Fabric loader, `server_only` environment, and Fabric API dependency. It also marks Terra Rasa as an embedded dependency because Soul Link bundles the library in each jar. The workflow does not change project-level Modrinth settings; update the project environment separately when needed.

If a GitHub Release already exists and needs to be published on Modrinth, run Publish to Modrinth manually with its tag. A retry skips a target version that already matches its release jar and metadata; it stops if the existing version differs.

### Checks and failures

The workflow stops if the tag does not match the mod version at that tag, if the expected runtime jar or checksum is missing, if the checksum fails, or if either Modrinth project's source URL points elsewhere. The Modrinth token is used to read the project metadata and create versions.

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

The CurseForge publisher resolves target game versions (Minecraft version and Fabric loader, Java versions where registered, and mandatory Environment tags Client and Server) against CurseForge's game versions API. Each target jar is published as a distinct release file with display name `Soul Link <version> (Minecraft <target>)`.

Dependencies are configured in file metadata:
- Fabric API (`fabric-api`) is declared as a `requiredDependency`.
- Terra Rasa (default slug `terra-rasa`) is declared as an `embeddedLibrary` because Soul Link bundles the library in its jar. If the related project is not accessible on CurseForge (e.g. still under review), the upload retries safely without the embedded library relation.

### Checks and failures

Publishing stops if the tag does not match `mod_version`, an expected target jar or SHA-256 checksum is missing, checksum verification fails, the CurseForge token is invalid or unauthorized for the project, or the CurseForge upload API rejects an upload. Retrying an upload safely skips target jars that were already uploaded to CurseForge.
