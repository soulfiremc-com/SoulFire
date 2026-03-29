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
package com.soulfiremc.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulfiremc.shared.LazyObject;
import lombok.SneakyThrows;
import net.fabricmc.loader.impl.game.GameProviderHelper;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.File;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SFMinecraftDownloader {
  private static final String MINECRAFT_VERSION = System.getProperty("sf.mcVersionOverride", "26.1");
  private static final String MINECRAFT_CLIENT_JAR_NAME = "minecraft-%s-client.jar".formatted(MINECRAFT_VERSION);
  private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

  private SFMinecraftDownloader() {
  }

  private static Path getAndCreateDownloadDirectory(Path basePath) {
    var downloadDir = basePath.resolve("mc-downloads");
    if (!Files.exists(downloadDir)) {
      try {
        Files.createDirectories(downloadDir);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create Minecraft download directory: " + downloadDir, e);
      }
    }
    return downloadDir;
  }

  private static Path getMinecraftClientJarPath(Path basePath) {
    return getAndCreateDownloadDirectory(basePath).resolve(MINECRAFT_CLIENT_JAR_NAME);
  }

  private static JsonObject getUrl(String url) {
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(URI.create(url)).build(),
        HttpResponse.BodyHandlers.ofString());
      return JsonParser.parseString(response.body()).getAsJsonObject();
    } catch (Exception e) {
      throw new RuntimeException("Failed to fetch URL: " + url, e);
    }
  }

  @SneakyThrows
  private static void setRemapClasspath(Path basePath) {
    var remapPathFile = Files.createTempFile("soulfire-mc-remap-", ".txt");
    remapPathFile.toFile().deleteOnExit();

    var getDeobfJarDir = GameProviderHelper.class.getDeclaredMethod("getDeobfJarDir", Path.class, String.class, String.class);
    getDeobfJarDir.setAccessible(true);
    var deobfJarDir = (Path) getDeobfJarDir.invoke(null, basePath.resolve("minecraft"), "minecraft", MINECRAFT_VERSION);

    Files.writeString(remapPathFile, Stream.concat(Arrays.stream(System.getProperty("java.class.path")
          .split(File.pathSeparator)),
        Stream.of(
          deobfJarDir
            .resolve("client-intermediary.jar")
            .toAbsolutePath()
            .toString()
        )
      )
      .collect(Collectors.joining(File.pathSeparator)));

    System.setProperty(SystemProperties.REMAP_CLASSPATH_FILE, remapPathFile.toString());
  }

  @SuppressWarnings("unused")
  @SneakyThrows
  public static void loadAndInjectMinecraftJar(Path basePath) {
    var minecraftJarPath = getMinecraftClientJarPath(basePath);
    if (Files.exists(minecraftJarPath)) {
      IO.println("Minecraft already downloaded, continuing");
    } else {
      IO.println("Downloading Minecraft...");
      var versionInfo = new LazyObject<>(() -> {
        var versionUrl = getUrl(MANIFEST_URL)
          .getAsJsonArray("versions")
          .asList()
          .stream()
          .map(JsonElement::getAsJsonObject)
          .filter(v -> MINECRAFT_VERSION.equals(v.get("id").getAsString()))
          .map(v -> v.get("url").getAsString())
          .findFirst()
          .orElseThrow(() -> new RuntimeException("Minecraft version " + MINECRAFT_VERSION + " not found in manifest"));
        return getUrl(versionUrl);
      });

      if (!Files.exists(minecraftJarPath)) {
        var clientUrl = versionInfo.get()
          .getAsJsonObject("downloads")
          .getAsJsonObject("client")
          .get("url")
          .getAsString();

        IO.println("Downloading Minecraft client jar from: " + clientUrl);
        var tempJarPath = Files.createTempFile("sf-mc-jar-download-", "-" + MINECRAFT_CLIENT_JAR_NAME);
        try (var in = URI.create(clientUrl).toURL().openStream()) {
          Files.copy(in, tempJarPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
          Files.deleteIfExists(tempJarPath);
          throw new RuntimeException("Failed to download Minecraft client jar from " + clientUrl, e);
        }

        Files.copy(tempJarPath, minecraftJarPath);
        Files.deleteIfExists(tempJarPath);
        IO.println("Minecraft client jar downloaded and saved to: " + minecraftJarPath);
      }
    }

    System.setProperty(SystemProperties.GAME_MAPPING_NAMESPACE, "official");
    System.setProperty(SystemProperties.GAME_JAR_PATH_CLIENT, minecraftJarPath.toString());
    System.setProperty(SystemProperties.RUNTIME_MAPPING_NAMESPACE, "official");
  }
}
