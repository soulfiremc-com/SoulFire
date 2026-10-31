/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.launcher.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class SoulFireBootstrap {
  private static final Set<String> DOWNLOAD_HOSTS = Set.of(
    "maven-central.storage-download.googleapis.com", "libraries.minecraft.net",
    "piston-data.mojang.com", "resources.download.minecraft.net", "launcher.mojang.com");

  private SoulFireBootstrap() {
  }

  public static void run(Path basePath, String bootstrapClassName, String[] args) {
    try {
      var baseDirectory = basePath.toAbsolutePath().normalize();
      Files.createDirectories(baseDirectory);
      StartupProgress.log("Reading runtime manifest");
      var manifest = LauncherManifest.read(SoulFireBootstrap.class.getResourceAsStream("/META-INF/soulfire-assets.json"));
      var versionOverride = System.getProperty("sf.mcVersionOverride", manifest.minecraftVersion());
      if (!versionOverride.equals(manifest.minecraftVersion())) {
        throw new IllegalArgumentException("This launcher contains fixed Minecraft " + manifest.minecraftVersion() + " assets");
      }
      var platform = LauncherManifest.platform();
      configureNativeDirectories(baseDirectory, platform);
      var assets = manifest.assets().stream().filter(asset -> asset.appliesTo(platform)).toList();
      StartupProgress.log("Checking " + assets.size() + " runtime files ("
        + StartupProgress.size(assets.stream().mapToLong(LauncherManifest.Asset::size).sum()) + ") for "
        + platform + "; Minecraft " + manifest.minecraftVersion());
      var runtime = new ArrayList<Path>();
      var progress = new StartupProgress(assets.size());
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
           var lockChannel = FileChannel.open(baseDirectory.resolve(".asset-cache.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
           var lock = acquireCacheLock(lockChannel)) {
        if (!lock.isValid()) {
          throw new IOException("Unable to lock the SoulFire asset cache");
        }
        var cache = new AssetCache(baseDirectory, asset -> open(client, asset), progress);
        try (var executor = Executors.newFixedThreadPool(6);
             var reports = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("SoulFire-startup-progress").daemon().factory())) {
          reports.scheduleAtFixedRate(progress::report, 2, 2, TimeUnit.SECONDS);
          var downloads = assets.stream()
            .map(asset -> executor.submit(() -> new PreparedAsset(asset, cache.materialize(asset))))
            .toList();
          try {
            for (var download : downloads) {
              var prepared = download.get();
              var file = prepared.file();
              switch (prepared.asset().kind()) {
                case "library" -> runtime.add(file);
                case "mod" -> System.setProperty("sf.managedMod", file.toString());
                case "minecraft" -> System.setProperty("sf.minecraftJar", file.toString());
                default -> { }
              }
            }
          } catch (ExecutionException | InterruptedException e) {
            downloads.forEach(download -> download.cancel(true));
            if (e instanceof InterruptedException) {
              Thread.currentThread().interrupt();
            }
            throw new IOException("Failed to prepare pinned runtime assets", e instanceof ExecutionException ? e.getCause() : e);
          }
        }
        progress.finish();
      }
      StartupProgress.log("Starting runtime with " + runtime.size() + " libraries");
      System.setProperty("sf.assetIndex", manifest.assetIndex());
      System.setProperty("sf.soundIndex", baseDirectory.resolve("assets/indexes/soulfire-sounds-" + manifest.minecraftVersion() + ".json").toString());
      System.setProperty("java.class.path", runtime.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
      var urls = new ArrayList<URL>();
      for (var file : runtime) {
        urls.add(file.toUri().toURL());
      }
      // Its platform parent keeps bootstrap classes and relocated dependencies outside the runtime.
      // Keep this loader open for the application's lifetime.
      var runtimeLoader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          runtimeLoader.close();
        } catch (IOException e) {
          e.printStackTrace(System.err);
        }
      }, "SoulFire-runtime-loader-close"));
      Thread.currentThread().setContextClassLoader(runtimeLoader);
      Class.forName("com.soulfiremc.launcher.SoulFirePostLibLauncher", true, runtimeLoader)
        .getMethod("runPostLib", Path.class, String.class, String[].class)
        .invoke(null, baseDirectory, bootstrapClassName, args);
    } catch (InvocationTargetException e) {
      throw new IllegalStateException("SoulFire runtime failed", e.getCause());
    } catch (IOException | ReflectiveOperationException e) {
      throw new IllegalStateException("Failed to prepare SoulFire runtime", e);
    }
  }

  private record PreparedAsset(LauncherManifest.Asset asset, Path file) {
  }

  private static void configureNativeDirectories(Path baseDirectory, String platform) throws IOException {
    // Native libraries read these properties during class initialization.
    var properties = Map.of(
      "org.lwjgl.system.SharedLibraryExtractPath", "lwjgl",
      "jna.tmpdir", "jna",
      "io.netty.native.workdir", "netty");
    for (var entry : properties.entrySet()) {
      if (System.getProperty(entry.getKey()) == null) {
        var directory = baseDirectory.resolve("natives").resolve(entry.getValue()).resolve(platform);
        Files.createDirectories(directory);
        System.setProperty(entry.getKey(), directory.toString());
      }
    }
  }

  private static FileLock acquireCacheLock(FileChannel channel) throws IOException {
    var lock = channel.tryLock();
    if (lock != null) {
      return lock;
    }
    StartupProgress.log("Waiting for another SoulFire process to release the runtime cache");
    return channel.lock();
  }

  private static InputStream open(HttpClient client, LauncherManifest.Asset asset) throws IOException {
    if (asset.resource() != null) {
      var input = SoulFireBootstrap.class.getResourceAsStream("/" + asset.resource());
      if (input == null) {
        throw new IOException("Missing packaged asset: " + asset.resource());
      }
      return input;
    }
    var uri = URI.create(asset.url());
    if (!"https".equals(uri.getScheme()) || !DOWNLOAD_HOSTS.contains(uri.getHost())) {
      throw new IOException("Unsupported asset download URL: " + asset.url());
    }
    try {
      var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() != 200) {
        response.body().close();
        throw new IOException("HTTP " + response.statusCode() + " downloading " + asset.name());
      }
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Asset download interrupted: " + asset.name(), e);
    }
  }
}
