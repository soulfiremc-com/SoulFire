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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeRuntimeBundleTest {
  @TempDir Path temporary;

  private byte[] payload() {
    var bytes = new byte[32768];
    new java.util.Random(42).nextBytes(bytes);
    return bytes;
  }

  private URLClassLoader resources(String file, byte[] bytes, byte[] expected) throws Exception {
    var directory = temporary.resolve("resources/soulfire-vulkan/test");
    Files.createDirectories(directory);
    var properties = new Properties();
    properties.setProperty("loader", file);
    properties.setProperty("driver", file);
    properties.setProperty("sha256." + file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)));
    try (var output = Files.newOutputStream(directory.resolve("runtime.properties"))) {
      properties.store(output, null);
    }
    if (!file.contains("..")) Files.write(directory.resolve(file), bytes);
    return new URLClassLoader(new URL[] {temporary.resolve("resources").toUri().toURL()}, null);
  }

  @Test
  void reusesVerifiedFilesAndRepairsCorruptCache() throws Exception {
    var bytes = payload();
    try (var resources = resources("driver.bin", bytes, bytes)) {
      var cache = temporary.resolve("cache");
      var first = NativeRuntimeBundle.extract(resources, "test", cache);
      assertArrayEquals(bytes, Files.readAllBytes(first.driver()));
      assertEquals(first, NativeRuntimeBundle.extract(resources, "test", cache));
      Files.write(first.driver(), Arrays.copyOf(bytes, bytes.length / 2));
      var repaired = NativeRuntimeBundle.extract(resources, "test", cache);
      assertArrayEquals(bytes, Files.readAllBytes(repaired.driver()));
    }
  }

  @Test
  void rejectsCorruptEmbeddedLibrary() throws Exception {
    var bytes = payload();
    try (var resources = resources("driver.bin", Arrays.copyOf(bytes, 8), bytes)) {
      assertThrows(IOException.class, () -> NativeRuntimeBundle.extract(resources, "test", temporary.resolve("cache")));
    }
  }

  @Test
  void rejectsManifestPathTraversal() throws Exception {
    var bytes = payload();
    try (var resources = resources("../../outside", bytes, bytes)) {
      assertThrows(IOException.class, () -> NativeRuntimeBundle.extract(resources, "test", temporary.resolve("cache")));
    }
  }
}
