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

import lombok.SneakyThrows;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SoulFireAbstractLauncher {
  private SoulFireAbstractLauncher() {
  }

  private static Path[] createLibClassLoader(Path libDir) {
    try (var dependencyListInput = SoulFireAbstractLauncher.class.getResourceAsStream("/META-INF/dependency-list.txt")) {
      if (dependencyListInput == null) {
        return new Path[0];
      }

      Files.createDirectories(libDir);

      var urls = new ArrayList<Path>();
      var bytes = dependencyListInput.readAllBytes();
      for (var fileName : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
        var libFile = libDir.resolve(fileName);
        try (var libInput = Objects.requireNonNull(
          SoulFireAbstractLauncher.class.getResourceAsStream(
            "/META-INF/lib/" + fileName
          ),
          "File not found: " + fileName
        )) {
          Files.write(libFile, libInput.readAllBytes());
          urls.add(libFile);
        }
      }

      return urls.toArray(new Path[0]);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @SneakyThrows
  public static void run(Path basePath, String bootstrapClassName, String[] args) {
    loadLibs(basePath);
    Class.forName(SoulFirePostLibLauncher.class.getName())
      .getMethod("runPostLib", Path.class, String.class, String[].class)
      .invoke(null, basePath, bootstrapClassName, args);
  }

  @SneakyThrows
  private static void loadLibs(Path basePath) {
    var librariesPath = basePath.resolve("libraries");
    var extractedLibs = createLibClassLoader(librariesPath);
    if (extractedLibs.length == 0) {
      IO.println("No libraries found in /META-INF/dependency-list.txt, skipping library loading.");
      // In development mode, expand Gradle's classpath JAR manifest if present
      expandGradleClasspathJar();
      return;
    }

    try (var reflectLib = new URLClassLoader(
      Arrays.stream(extractedLibs)
        .filter(path -> path.getFileName().toString().startsWith("Reflect-")
          || path.getFileName().toString().startsWith("unchecked-"))
        .map(Path::toUri)
        .map(uri -> {
          try {
            return uri.toURL();
          } catch (MalformedURLException e) {
            throw new RuntimeException(e);
          }
        })
        .toArray(URL[]::new)
    )) {
      var addToSystemClassPath = reflectLib.loadClass("net.lenni0451.reflect.ClassLoaders")
        .getDeclaredMethod("addToSystemClassPath", URL.class);

      for (var lib : extractedLibs) {
        System.setProperty("java.class.path",
          Stream.concat(Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)),
              Stream.of(lib.toAbsolutePath().toString()))
            .collect(Collectors.joining(File.pathSeparator)));

        addToSystemClassPath.invoke(null, lib.toUri().toURL());
      }
    }
  }

  /// On Windows, Gradle uses a classpath JAR manifest to avoid command-line length limits.
  /// This JAR contains a manifest with Class-Path attribute that lists the actual classpath entries.
  /// Fabric Loader reads java.class.path but only sees the wrapper JAR, breaking class isolation.
  /// This method expands the manifest and updates java.class.path with the actual entries.
  private static void expandGradleClasspathJar() {
    var classPath = System.getProperty("java.class.path");
    if (classPath == null) {
      return;
    }

    var entries = classPath.split(File.pathSeparator);
    // Check if classpath is a single JAR file (Gradle's wrapper)
    if (entries.length == 1 && entries[0].endsWith(".jar")) {
      var jarPath = Path.of(entries[0]);
      if (Files.exists(jarPath)) {
        try (var jarFile = new JarFile(jarPath.toFile())) {
          var manifest = jarFile.getManifest();
          if (manifest != null) {
            var manifestClassPath = manifest.getMainAttributes().getValue("Class-Path");
            if (manifestClassPath != null && !manifestClassPath.isEmpty()) {
              // Convert space-separated URLs to file paths
              var expandedPaths = new ArrayList<String>();
              for (var urlStr : manifestClassPath.split(" ")) {
                if (urlStr.startsWith("file:")) {
                  // URL decode and convert to path
                  var path = Path.of(URI.create(urlStr)).toAbsolutePath().toString();
                  expandedPaths.add(path);
                }
              }
              if (!expandedPaths.isEmpty()) {
                var expandedClassPath = String.join(File.pathSeparator, expandedPaths);
                System.setProperty("java.class.path", expandedClassPath);
                IO.println("Expanded Gradle classpath JAR manifest (" + expandedPaths.size() + " entries)");
              }
            }
          }
        } catch (Exception e) {
          IO.println("Warning: Failed to expand Gradle classpath JAR: " + e.getMessage());
        }
      }
    }
  }
}
