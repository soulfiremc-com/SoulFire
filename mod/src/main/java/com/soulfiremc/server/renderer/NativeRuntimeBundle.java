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
package com.soulfiremc.server.renderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

/// Extracts a verified, content-addressed native runtime without modifying system directories.
public record NativeRuntimeBundle(Path loader, Path driver) {
  public static String platformId(String os, String architecture) {
    var name = os.toLowerCase(Locale.ROOT);
    var platform = name.startsWith("windows") ? "windows" : name.startsWith("mac") ? "macos" : name.equals("linux") ? "linux" : null;
    var arch = switch (architecture.toLowerCase(Locale.ROOT)) {
      case "amd64", "x86_64" -> "x86_64";
      case "aarch64", "arm64" -> "arm64";
      default -> null;
    };
    if (platform == null || arch == null) {
      throw new IllegalStateException("Unsupported native Vulkan platform: " + os + " / " + architecture);
    }
    return platform + "-" + arch;
  }

  public static synchronized NativeRuntimeBundle extract(ClassLoader resources, String platform, Path cache) throws IOException {
    var root = "soulfire-vulkan/" + platform + "/";
    byte[] manifest;
    try (var input = resource(resources, root + "runtime.properties")) {
      manifest = input.readAllBytes();
    }
    var properties = new Properties();
    properties.load(new java.io.ByteArrayInputStream(manifest));
    var directory = cache.resolve(platform).resolve(hash(manifest)).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    try (var channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
         var lock = channel.lock()) {
      for (var key : properties.stringPropertyNames()) {
        if (!key.startsWith("sha256.")) continue;
        var name = key.substring("sha256.".length());
        var target = resolve(directory, name);
        var expected = properties.getProperty(key);
        if (Files.isRegularFile(target) && hash(target).equals(expected)) continue;
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".extract-", ".tmp");
        try {
          try (var input = resource(resources, root + name)) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
          }
          if (!hash(temporary).equals(expected)) {
            throw new IOException("Native Vulkan resource checksum mismatch: " + name);
          }
          try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          } catch (AtomicMoveNotSupportedException _) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
          }
        } finally {
          Files.deleteIfExists(temporary);
        }
      }
    }
    return new NativeRuntimeBundle(library(properties, directory, "loader"), library(properties, directory, "driver"));
  }

  private static Path library(Properties properties, Path directory, String key) throws IOException {
    var name = properties.getProperty(key);
    if (name == null || !properties.containsKey("sha256." + name)) {
      throw new IOException("Missing verified native Vulkan library: " + key);
    }
    return resolve(directory, name);
  }

  private static Path resolve(Path directory, String name) throws IOException {
    var path = directory.resolve(name).normalize();
    if (name.contains("\\") || !path.startsWith(directory) || path.equals(directory)) {
      throw new IOException("Invalid native Vulkan resource path: " + name);
    }
    return path;
  }

  private static InputStream resource(ClassLoader resources, String name) throws IOException {
    var input = resources.getResourceAsStream(name);
    if (input == null) throw new IOException("Missing bundled Vulkan resource: " + name + ". Build the native runtime before packaging SoulFire.");
    return input;
  }

  private static String hash(Path file) throws IOException {
    try (var input = Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("SHA-256");
      var buffer = new byte[16384];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }
}
