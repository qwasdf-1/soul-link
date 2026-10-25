# Publishing to Modrinth

The GitHub release workflow builds the mod jar and creates a GitHub Release. The Modrinth workflow takes that same release jar, checks it against the attached SHA-256 file, and publishes it as a Modrinth version. The GitHub release notes become the Modrinth version changelog.

The workflow does not edit the Modrinth project page description, gallery, categories, or other project settings. Keep that project information up to date on Modrinth.

## One-time setup

1. Create and configure both Modrinth projects. Set their source links to:
   - Soul Link: https://github.com/qwasdf-1/soul-link
   - Terra Rasa: https://github.com/qwasdf-1/terra-rasa
2. Create a Modrinth token with the VERSION_CREATE permission.
3. In the Soul Link GitHub repository, add the Actions secret MODRINTH_TOKEN with that token.
4. Add these repository Actions variables:
   - MODRINTH_PROJECT_URL with the full Soul Link Modrinth URL.
   - MODRINTH_TERRA_RASA_PROJECT_URL with the full Terra Rasa Modrinth URL.
5. Run the Publish to Modrinth workflow from Actions and enter an existing release tag, such as v0.1.0.

The workflow resolves each URL through Modrinth, checks that each project links back to its expected GitHub repository, and prints both project IDs in the run summary. Then set MODRINTH_PROJECT_ID and MODRINTH_TERRA_RASA_PROJECT_ID to those IDs. Future publishes use the IDs directly, even if the URL variables remain set. IDs remain stable if project slugs change.

## Future releases

Push a version tag such as v0.2.0. The release workflow builds and attaches the jar and SHA-256 checksum, then dispatches the Modrinth publishing workflow with that tag. The publisher uploads the runtime jar with the matching Minecraft version, Fabric loader, dedicated-server environment, and Fabric API dependency. It also marks Terra Rasa as an embedded dependency because Soul Link bundles the library in its jar.

If a GitHub Release already exists and needs to be published on Modrinth, run Publish to Modrinth manually with its tag. Each version number can only be published once on Modrinth.

## Checks and failures

The workflow stops if the tag does not match the mod version at that tag, if the expected runtime jar or checksum is missing, if the checksum fails, or if either Modrinth project's source URL points elsewhere. The Modrinth token is only used for version creation; project lookups are public API requests.
