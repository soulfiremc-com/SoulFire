# Publish the Vulkan runtime

The Vulkan runtime has a separate release version in `gradle.properties`:

```properties
vulkan_runtime_version=1.0.0
```

Soulfire releases change `maven_version`. They do not change this runtime version.
Bump the runtime version when its native binaries, dependencies, or package layout change.
Published versions are immutable.

## Check the publishing allowance

The six reference packages before native size optimization totaled about 342 MB (326 MiB).
Linux x86-64 now uses a smaller static LLVM build.
Rebuilds can change the total, so inspect the staged publication before each release.
Central counts all published files against the organization's monthly release-size allowance.
The total does not reset for each platform, classifier, or artifact.
An 80 MB monthly allowance cannot accommodate this publication within its limit.
The separate 1 GB upload limit does not increase the monthly allowance.

Before publication, check the organization's [Central publishing usage](https://central.sonatype.com/publishing/usage).
If its allowance is insufficient, request a [higher limit or community-project exemption](https://central.sonatype.org/publish/maven-central-publishing-limits/).
A suitable paid plan or another artifact host are alternatives.
Google's mirror serves downloads after publication and does not change Central's upload accounting.

## Configure Maven Central

1. Verify the `com.soulfiremc` namespace in the [Central Portal](https://central.sonatype.com/).
2. Create a [Central Portal user token](https://central.sonatype.org/publish/generate-portal-token/).
3. Create a GPG signing key and [publish its public key](https://central.sonatype.org/publish/requirements/gpg/).
4. Add these GitHub Actions secrets to the Soulfire repository:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | The username from the Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | The password from the Portal user token |
| `MAVEN_CENTRAL_SIGNING_KEY` | The complete ASCII-armored GPG private key |
| `MAVEN_CENTRAL_SIGNING_PASSWORD` | The private key password, if the key has one |

The publishing plugin reads the signing key into memory.
The workflow does not write the key to the repository.

## Publish a release

1. Update `vulkan_runtime_version` and the native build inputs for the release.
2. Commit the changes to the branch that will publish the release.
3. Run **Publish Vulkan runtime** from GitHub Actions on that branch.

The workflow builds and smoke-tests all six platforms through **Native Vulkan runtime**.
It publishes the platform JARs, a metadata JAR, sources, documentation, POM, checksums, and GPG signatures.
It releases the version automatically after Central accepts the deployment.
It does not create a Soulfire release or update `maven_version`.

The [artifact reference](ARTIFACTS.md) lists the coordinates and package layout.

## Inspect a publication locally

The native build workflow produces six `soulfire-vulkan-<platform>.jar` files.
Place those files in `build/vulkan-prebuilt` before these steps.

1. Export the signing key through the environment variables that the publishing plugin reads:

   ```bash
   export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys YOUR_KEY_ID)"
   read -rs ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
   export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
   ```

2. Build the publication in the local staging repository:

   ```bash
   ./gradlew :vulkan-runtime:publishAllPublicationsToStagingRepository \
     -PvulkanPlatforms=all \
     -PvulkanRuntimeDirectory=build/vulkan-prebuilt
   ```

3. Inspect the files under `vulkan-runtime/build/publishing/staging/com/soulfiremc/vulkan-runtime/`.
4. Run `./gradlew :buildSrc:test` before publication.

The staging task does not upload files to Central.
Publication requires all six classifiers, even when the staging repository is local.

## Use Google's mirror

New Central releases take time to reach [Google's Maven Central mirror](https://storage-download.googleapis.com/maven-central/index.html).
The launcher asset generator checks the mirror's checksum against each native package before it uses a remote URL.
It embeds packages that are not available on the mirror yet.
It rejects packages whose bytes differ from the published version.

Each launcher manifest pins the platform, URL, size, and SHA-256 checksum.
The launcher downloads only its host platform and reuses the verified local cache.
Other Soulfire modules remain embedded.

Publishing the runtime separately avoids uploading unchanged native packages with every Soulfire release.
It does not reduce the bytes required for the first native release.
