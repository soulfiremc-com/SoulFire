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
import lombok.SneakyThrows;
import net.fabricmc.loader.impl.util.SystemProperties;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SFMinecraftDownloader {
  private static final String MINECRAFT_VERSION = System.getProperty("sf.mcVersionOverride", "26.2");
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

  private static JsonObject versionInfo() {
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
  }

  @SneakyThrows
  public static String prepareFontAssets(Path basePath) {
    var assetDirectory = basePath.resolve("assets");
    var indexId = "soulfire-fonts-" + MINECRAFT_VERSION;
    var indexPath = assetDirectory.resolve("indexes").resolve(indexId + ".json");
    JsonObject index;
    if (Files.exists(indexPath)) {
      index = JsonParser.parseString(Files.readString(indexPath)).getAsJsonObject();
    } else {
      var assetIndex = getUrl(versionInfo().getAsJsonObject("assetIndex").get("url").getAsString());
      var fonts = new JsonObject();
      for (var entry : assetIndex.getAsJsonObject("objects").entrySet()) {
        if (entry.getKey().startsWith("minecraft/font/") || entry.getKey().startsWith("minecraft/textures/font/")) {
          fonts.add(entry.getKey(), entry.getValue());
        }
      }
      index = new JsonObject();
      index.add("objects", fonts);
    }

    for (var entry : index.getAsJsonObject("objects").entrySet()) {
      var hash = entry.getValue().getAsJsonObject().get("hash").getAsString();
      if (!hash.matches("[0-9a-f]{40}")) {
        throw new IOException("Invalid font asset hash for " + entry.getKey());
      }
      var objectPath = hash.substring(0, 2) + "/" + hash;
      var destination = assetDirectory.resolve("objects").resolve(objectPath);
      if (!Files.exists(destination) || !sha1(destination).equals(hash)) {
        IO.println("Downloading Minecraft font asset: " + entry.getKey());
        Files.createDirectories(destination.getParent());
        var temporary = Files.createTempFile(destination.getParent(), "font-", ".tmp");
        try {
          try (var input = URI.create("https://resources.download.minecraft.net/" + objectPath).toURL().openStream()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
          }
          if (!sha1(temporary).equals(hash)) {
            throw new IOException("Font asset checksum mismatch: " + entry.getKey());
          }
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
    }
    if (!Files.exists(indexPath)) {
      Files.createDirectories(indexPath.getParent());
      var temporary = Files.createTempFile(indexPath.getParent(), "font-index-", ".tmp");
      try {
        Files.writeString(temporary, index.toString());
        Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    return indexId;
  }

  @SneakyThrows
  private static String sha1(Path path) {
    var digest = MessageDigest.getInstance("SHA-1");
    try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
      input.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  @SneakyThrows
  public static void loadAndInjectMinecraftJar(Path basePath) {
    var minecraftJarPath = getMinecraftClientJarPath(basePath);
    if (Files.exists(minecraftJarPath)) {
      IO.println("Minecraft already downloaded, continuing");
    } else {
      IO.println("Downloading Minecraft...");
      var versionInfo = versionInfo();

      if (!Files.exists(minecraftJarPath)) {
        var clientUrl = versionInfo
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
