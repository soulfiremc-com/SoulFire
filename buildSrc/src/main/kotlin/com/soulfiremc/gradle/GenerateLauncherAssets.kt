package com.soulfiremc.gradle

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/// Resolves metadata and download locations at build time. The runtime never resolves repositories.
@DisableCachingByDefault(because = "Fetches Minecraft metadata and verifies CDN artifacts")
abstract class GenerateLauncherAssets : DefaultTask() {
  @get:Input abstract val minecraftVersion: Property<String>
  @get:Input abstract val coordinates: MapProperty<String, String>
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val libraries: ConfigurableFileCollection
  @get:LocalState abstract val metadataDirectory: DirectoryProperty
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  private val gson get() = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

  @TaskAction
  fun generate() {
    val output = outputDirectory.get().asFile
    output.deleteRecursively()
    output.mkdirs()
    val cache = metadataDirectory.get().asFile.apply { mkdirs() }
    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build().use { client ->
      val manifest = json(client, cache, "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
      val versionEntry = manifest.getAsJsonArray("versions").firstOrNull { it.asJsonObject["id"].asString == minecraftVersion.get() }
        ?.asJsonObject ?: error("Minecraft ${minecraftVersion.get()} is absent from the launcher manifest")
      val version = json(client, cache, versionEntry["url"].asString, versionEntry["sha1"].asString)
      val minecraftLibraries = version.getAsJsonArray("libraries").mapNotNull {
        it.asJsonObject.getAsJsonObject("downloads")?.getAsJsonObject("artifact")
      }.associateBy { it["path"].asString }
      val assets = mutableListOf<Map<String, Any>>()
      Executors.newFixedThreadPool(8).use { executor ->
        val jobs = runtimeLibraries(libraries.files, coordinates.get()).sortedBy { it.name }.map { file ->
          Callable {
            val coordinate = coordinates.get()[file.name]
            val parts = coordinate?.split(':')
            val bundledProject = parts != null && parts[0] == "com.soulfiremc" && parts[1] != "vulkan-runtime"
            val hash = digest(file, "SHA-256")
            val path = if (parts != null) {
              "libraries/${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/" +
                (if (bundledProject || parts[2].endsWith("-SNAPSHOT")) "$hash/" else "") +
                "${parts[1]}-${parts[2]}${parts.getOrNull(3)?.let { "-$it" } ?: ""}.jar"
            } else "libraries/com/soulfiremc/${file.nameWithoutExtension}/$hash/${file.name}"
            val artifact = linkedMapOf<String, Any>(
              "name" to (coordinate ?: file.name), "path" to path, "size" to file.length(), "sha256" to hash,
              "kind" to if (parts?.get(0) == "com.soulfiremc" && parts[1] == "mod") "mod" else "library"
            )
            platform(file.name)?.let { artifact["platform"] = it }
            val mc = minecraftLibraries[path.removePrefix("libraries/")]
            val url = when {
              mc != null && digest(file, "SHA-1") == mc["sha1"].asString -> mc["url"].asString
              parts != null && !parts[2].endsWith("-SNAPSHOT") && !bundledProject -> {
                val candidate = "https://maven-central.storage-download.googleapis.com/maven2/" + path.removePrefix("libraries/")
                if (available(client, cache, candidate, file, hash)) candidate else null
              }
              else -> null
            }
            if (url != null) artifact["url"] = url else {
              val resource = "META-INF/assets/$hash.jar"
              val destination = output.resolve(resource)
              destination.parentFile.mkdirs()
              file.copyTo(destination, overwrite = true)
              artifact["resource"] = resource
            }
            artifact
          }
        }
        assets.addAll(executor.invokeAll(jobs).map { it.get() }.distinctBy { it["path"] })
        val resolvedPaths = assets.map { it["path"] }.toSet()
        val nativeJobs = minecraftNatives(version).filter { "libraries/${it.path}" !in resolvedPaths }.map { native ->
          Callable {
            val download = native.download ?: run {
              val url = "https://maven-central.storage-download.googleapis.com/maven2/${native.path}"
              val sha1 = fetch(client, cache, "$url.sha1").readText().trim().split(Regex("\\s+")).first().lowercase()
              require(sha1.matches(Regex("[0-9a-fA-F]{40}"))) { "Invalid checksum for $url" }
              val file = fetch(client, cache, url, sha1)
              JsonObject().apply {
                addProperty("url", url)
                addProperty("sha1", sha1)
                addProperty("size", file.length())
              }
            }
            remote(client, cache, native.name, "libraries/${native.path}", "library", download) +
              ("platform" to native.platform)
          }
        }
        assets.addAll(executor.invokeAll(nativeJobs).map { it.get() })
      }

      val clientDownload = version.getAsJsonObject("downloads").getAsJsonObject("client")
      assets += remote(client, cache, "Minecraft client", "mc-downloads/minecraft-${minecraftVersion.get()}-client.jar", "minecraft", clientDownload)
      val indexDownload = version.getAsJsonObject("assetIndex")
      val index = json(client, cache, indexDownload["url"].asString, indexDownload["sha1"].asString)
      val startupAssets = JsonObject()
      index.getAsJsonObject("objects").entrySet().filter {
        it.key.startsWith("minecraft/font/") || it.key.startsWith("minecraft/textures/font/") ||
          it.key.startsWith("icons/")
      }.forEach { startupAssets.add(it.key, it.value) }
      for ((hash, value) in startupAssets.entrySet().map { it.value.asJsonObject }.associateBy { it["hash"].asString }.toSortedMap()) {
        val path = "${hash.take(2)}/$hash"
        val download = value.deepCopy().apply {
          addProperty("url", "https://resources.download.minecraft.net/$path")
          addProperty("sha1", hash)
        }
        assets += remote(client, cache, "Minecraft startup asset $hash", "assets/objects/$path", "asset", download)
      }
      val sounds = JsonObject()
      index.getAsJsonObject("objects").entrySet().filter {
        it.key == "minecraft/sounds.json" || it.key.startsWith("minecraft/sounds/")
      }.forEach { sounds.add(it.key, it.value) }
      val soundDefinitions = sounds.getAsJsonObject("minecraft/sounds.json")
      val soundHash = soundDefinitions["hash"].asString
      val soundPath = "${soundHash.take(2)}/$soundHash"
      assets += remote(client, cache, "Minecraft sound definitions", "assets/objects/$soundPath", "asset",
        soundDefinitions.deepCopy().apply {
          addProperty("url", "https://resources.download.minecraft.net/$soundPath")
          addProperty("sha1", soundHash)
        })
      val soundIndexId = "soulfire-sounds-${minecraftVersion.get()}"
      val soundIndexFile = output.resolve("META-INF/assets/$soundIndexId.json")
      soundIndexFile.writeText(gson.toJson(JsonObject().apply { add("objects", sounds) }) + "\n")
      assets += linkedMapOf(
        "name" to "Minecraft sound index", "path" to "assets/indexes/$soundIndexId.json", "kind" to "asset",
        "resource" to "META-INF/assets/$soundIndexId.json", "size" to soundIndexFile.length(), "sha256" to digest(soundIndexFile, "SHA-256")
      )
      val indexId = "soulfire-startup-${minecraftVersion.get()}"
      val indexFile = output.resolve("META-INF/assets/$indexId.json")
      indexFile.writeText(gson.toJson(JsonObject().apply { add("objects", startupAssets) }) + "\n")
      assets += linkedMapOf(
        "name" to "Minecraft startup asset index", "path" to "assets/indexes/$indexId.json", "kind" to "asset",
        "resource" to "META-INF/assets/$indexId.json", "size" to indexFile.length(), "sha256" to digest(indexFile, "SHA-256")
      )
      output.resolve("META-INF/soulfire-assets.json").writeText(gson.toJson(linkedMapOf(
        "formatVersion" to 1, "minecraftVersion" to minecraftVersion.get(), "assetIndex" to indexId,
        "assets" to assets.sortedBy { it["path"].toString() }
      )) + "\n")
      logger.lifecycle("Pinned ${assets.size} launcher assets (${assets.count { it.containsKey("url") }} CDN downloads)")
    }
  }

  private fun remote(client: HttpClient, cache: File, name: String, path: String, kind: String, download: JsonObject): Map<String, Any> {
    val url = download["url"].asString
    val file = fetch(client, cache, url, download["sha1"].asString)
    check(file.length() == download["size"].asLong) { "Unexpected size for $url" }
    return linkedMapOf("name" to name, "path" to path, "kind" to kind, "url" to url, "size" to file.length(), "sha256" to digest(file, "SHA-256"))
  }

  private fun json(client: HttpClient, cache: File, url: String, sha1: String? = null): JsonObject =
    JsonParser.parseString(fetch(client, cache, url, sha1).readText()).asJsonObject

  private fun fetch(client: HttpClient, cache: File, url: String, sha1: String? = null): File {
    val key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()))
    val file = cache.resolve(key)
    // Hash-addressed metadata is immutable; refresh the version index when regenerating assets.
    if (file.isFile && sha1 != null && digest(file, "SHA-1") == sha1) return file
    val temporary = File.createTempFile("download-", ".tmp", cache)
    try {
      val response = client.send(request(url), HttpResponse.BodyHandlers.ofFile(temporary.toPath()))
      check(response.statusCode() == 200) { "HTTP ${response.statusCode()} fetching $url" }
      check(sha1 == null || digest(temporary, "SHA-1") == sha1) { "Checksum mismatch for $url" }
      temporary.copyTo(file, overwrite = true)
      return file
    } finally {
      temporary.delete()
    }
  }

  private fun available(client: HttpClient, cache: File, url: String, file: File, sha256: String): Boolean {
    val proof = cache.resolve("central-$sha256")
    if (proof.isFile && proof.readText() == url) return true
    val response = client.send(request("$url.sha1"), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 404) return false
    check(response.statusCode() == 200) { "HTTP ${response.statusCode()} verifying $url" }
    check(response.body().trim().split(Regex("\\s+")).first().equals(digest(file, "SHA-1"), ignoreCase = true)) {
      "Resolved artifact differs from Google Maven Central mirror: $url"
    }
    proof.writeText(url)
    return true
  }

  private fun request(url: String) = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).build()

  companion object {
    private val nativePlatforms = mapOf(
      "linux" to "linux-x86_64", "linux-arm64" to "linux-arm64",
      "windows" to "windows-x86_64", "windows-arm64" to "windows-arm64",
      "macos" to "macos-x86_64", "macos-arm64" to "macos-arm64"
    )

    data class MinecraftNative(val name: String, val platform: String, val download: JsonObject?) {
      val path: String
        get() = name.split(':').let { (group, artifact, version, classifier) ->
          "${group.replace('.', '/')}/$artifact/$version/$artifact-$version-$classifier.jar"
        }
    }

    fun minecraftNatives(metadata: JsonObject): List<MinecraftNative> {
      val natives = metadata.getAsJsonArray("libraries").mapNotNull { element ->
        val library = element.asJsonObject
        val name = library["name"].asString
        val parts = name.split(':')
        if (parts.size != 4 || parts[0] != "org.lwjgl" || !parts[3].startsWith("natives-")) return@mapNotNull null
        val platform = nativePlatforms[parts[3].removePrefix("natives-")] ?: return@mapNotNull null
        val download = requireNotNull(library.getAsJsonObject("downloads")?.getAsJsonObject("artifact")) {
          "Missing Minecraft native download: $name"
        }
        MinecraftNative(name, platform, download)
      }.associateBy { it.name }.toMutableMap()

      // Mojang omits Linux ARM64. LWJGL publishes matching classifiers on Central.
      natives.values.filter { it.platform == "linux-x86_64" }.toList().forEach { native ->
        val arm = native.copy(
          name = native.name.substringBeforeLast(':') + ":natives-linux-arm64", platform = "linux-arm64", download = null
        )
        natives.putIfAbsent(arm.name, arm)
      }
      require(natives.values.filter { it.name.startsWith("org.lwjgl:lwjgl:") }.map { it.platform }.toSet() == nativePlatforms.values.toSet()) {
        "Minecraft metadata must provide LWJGL system natives for all six Soulfire platforms"
      }
      return natives.values.toList()
    }

    fun runtimeLibraries(files: Set<File>, coordinates: Map<String, String>): List<File> {
      // Fabric discovers these from the managed mod. Keep transitive dependencies in the
      // runtime, but do not also ship byte-identical copies of its declared nested JARs.
      val bundledHashes = files.filter { coordinates[it.name]?.startsWith("com.soulfiremc:mod:") == true }
        .flatMap { mod ->
          ZipFile(mod).use { jar ->
            val metadata = jar.getInputStream(jar.getEntry("fabric.mod.json")).reader().use {
              JsonParser.parseReader(it).asJsonObject
            }
            metadata.getAsJsonArray("jars")?.map { nested ->
              val path = nested.asJsonObject["file"].asString
              val entry = requireNotNull(jar.getEntry(path)) { "Missing nested library $path in $mod" }
              jar.getInputStream(entry).use { digest(it, "SHA-256") }
            }.orEmpty()
          }
        }.toSet()
      return files.filter { file ->
        val coordinate = coordinates[file.name].orEmpty()
        file.extension == "jar" && !coordinate.startsWith("net.minecraft:") &&
          !coordinate.startsWith("net.fabricmc:dev-launch-injector:") &&
          !coordinate.startsWith("net.fabricmc:fabric-log4j-util:") &&
          (bundledHashes.isEmpty() || digest(file, "SHA-256") !in bundledHashes)
      }
    }

    fun digest(file: File, algorithm: String): String = file.inputStream().use { digest(it, algorithm) }

    private fun digest(input: InputStream, algorithm: String): String {
      val digest = MessageDigest.getInstance(algorithm)
      val buffer = ByteArray(65536)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
      return HexFormat.of().formatHex(digest.digest())
    }

    fun platform(name: String): String? {
      val token = when {
        name.startsWith("soulfire-vulkan-") -> name.removePrefix("soulfire-vulkan-").removeSuffix(".jar")
        "-natives-" in name -> return requireNotNull(nativePlatforms[name.substringAfter("-natives-").removeSuffix(".jar")]) {
          "Unsupported native platform: $name"
        }
        Regex("-(linux|windows|macos|macosx|osx)-(x86_64|arm64|aarch_64|riscv64)(-gpl)?\\.jar$").containsMatchIn(name) ->
          Regex("-(linux|windows|macos|macosx|osx)-(x86_64|arm64|aarch_64|riscv64)(-gpl)?\\.jar$").find(name)!!.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
        else -> return null
      }
      val os = when {
        token.startsWith("linux") -> "linux"
        token.startsWith("windows") -> "windows"
        token.startsWith("macos") || token.startsWith("osx") -> "macos"
        else -> error("Unsupported native platform: $name")
      }
      val arch = when {
        "aarch_64" in token || "aarch64" in token || "arm64" in token -> "arm64"
        "riscv64" in token -> "riscv64"
        else -> "x86_64"
      }
      return "$os-$arch"
    }
  }
}
