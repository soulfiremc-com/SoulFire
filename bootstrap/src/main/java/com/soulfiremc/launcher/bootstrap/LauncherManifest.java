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

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

record LauncherManifest(int formatVersion, String minecraftVersion, String assetIndex, List<Asset> assets) {
  LauncherManifest {
    if (formatVersion != 1 || minecraftVersion == null || assetIndex == null || assets == null) {
      throw new IllegalArgumentException("Invalid Soulfire asset manifest");
    }
    assets = List.copyOf(assets);
    var paths = new HashSet<String>();
    for (var asset : assets) {
      if (!paths.add(asset.path())) {
        throw new IllegalArgumentException("Duplicate asset path: " + asset.path());
      }
    }
  }

  static LauncherManifest read(InputStream input) throws IOException {
    if (input == null) {
      throw new IOException("Missing /META-INF/soulfire-assets.json; build the launcher assets first");
    }
    try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      return new Gson().fromJson(reader, LauncherManifest.class);
    }
  }

  static String platform() {
    var osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    var os = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : osName.contains("linux") ? "linux" : null;
    var arch = switch (System.getProperty("os.arch").toLowerCase(Locale.ROOT)) {
      case "amd64", "x86_64" -> "x86_64";
      case "aarch64", "arm64" -> "arm64";
      default -> null;
    };
    if (os == null || arch == null) {
      throw new IllegalStateException("Unsupported Soulfire platform: " + osName + " / " + System.getProperty("os.arch"));
    }
    return os + "-" + arch;
  }

  record Asset(String name, String path, String kind, long size, String sha256, String url, String resource, String platform) {
    private static final Set<String> KINDS = Set.of("library", "mod", "minecraft", "asset");

    Asset {
      if (name == null || path == null || kind == null || !KINDS.contains(kind) || size < 0
        || sha256 == null || !sha256.matches("[0-9a-f]{64}") || (url == null) == (resource == null)) {
        throw new IllegalArgumentException("Invalid asset: " + name);
      }
      var relative = Path.of(path);
      if (relative.isAbsolute() || path.contains("\\") || !relative.normalize().equals(relative)
        || path.startsWith("../") || relative.getNameCount() < 2) {
        throw new IllegalArgumentException("Invalid asset path: " + path);
      }
      if (resource != null && (!resource.startsWith("META-INF/assets/") || resource.contains(".."))) {
        throw new IllegalArgumentException("Invalid asset resource: " + resource);
      }
    }

    boolean appliesTo(String currentPlatform) {
      return platform == null || platform.equals(currentPlatform);
    }
  }
}
