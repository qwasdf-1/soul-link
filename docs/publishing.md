# Publishing to Modrinth

The release workflow builds one jar per supported Minecraft version and attaches all six jars plus a SHA-256 checksum file to one GitHub Release. The Modrinth workflow verifies each jar and creates one Modrinth version per game target. Each Modrinth version uses a unique number such as `0.2.0-mc1.21.11` or `0.2.0-mc26.2`; GitHub release notes become each version's changelog.

The workflow does not edit the Modrinth project page description, gallery, categories, or other project settings. Keep that project information up to date on Modrinth.

## One-time setup

1. Create and configure both Modrinth projects. Set their source links to:
   - Soul Link: https://github.com/qwasdf-1/soul-link
   - Terra Rasa: https://github.com/qwasdf-1/terra-rasa
2. Create a Modrinth token with `PROJECT_READ` and `VERSION_CREATE` permissions. `PROJECT_READ` lets the publisher verify the project source links; `VERSION_CREATE` lets it upload versions.
3. In the Soul Link GitHub repository, add the Actions secret MODRINTH_TOKEN with that token.
4. Add these repository Actions variables with the project IDs:
   - `MODRINTH_PROJECT_ID` for Soul Link.
   - `MODRINTH_TERRA_RASA_PROJECT_ID` for Terra Rasa.
5. Run the Publish to Modrinth workflow from Actions and enter an existing release tag.

The workflow checks that each project links back to its expected GitHub repository. IDs remain stable if project slugs change.

## Future releases

The `v0.1.0` release already exists, so bump `mod_version` before publishing this multi-version update; do not reuse that tag.

Push a version tag that matches `mod_version`, such as `v0.2.0`. The release workflow builds all target jars, attaches them with a SHA-256 checksum file, then dispatches the Modrinth publisher. The publisher creates one version per Minecraft target, with the matching game version, Fabric loader, dedicated-server environment, and Fabric API dependency. It also marks Terra Rasa as an embedded dependency because Soul Link bundles the library in each jar.

If a GitHub Release already exists and needs to be published on Modrinth, run Publish to Modrinth manually with its tag. A retry skips a target version that already matches its release jar and metadata; it stops if the existing version differs.

## Checks and failures

The workflow stops if the tag does not match the mod version at that tag, if the expected runtime jar or checksum is missing, if the checksum fails, or if either Modrinth project's source URL points elsewhere. The Modrinth token is used to read the project metadata and create versions.
