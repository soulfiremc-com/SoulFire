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

import com.soulfiremc.builddata.BuildData;
import lombok.SneakyThrows;
import net.fabricmc.loader.impl.launch.knot.KnotClient;
import net.fabricmc.loader.impl.util.SystemProperties;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.mixinstranslator.MixinsTranslator;
import net.lenni0451.reflect.Agents;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SoulFirePostLibLauncher {
  private SoulFirePostLibLauncher() {
  }

  @SneakyThrows
  private static void setupManagedMods(Path baseDir) {
    var modsPath = baseDir.resolve("minecraft").resolve("mods");
    if (!Files.exists(modsPath)) {
      Files.createDirectories(modsPath);
    }

    try (var stream = Files.list(modsPath)) {
      stream.filter(path -> path.getFileName().toString().startsWith("managed-"))
        .forEach(path -> {
          try {
            Files.deleteIfExists(path);
          } catch (Exception e) {
            System.err.println("Failed to delete managed mod: " + path + " - " + e.getMessage());
          }
        });
    }

    var fileName = "mod-%s.jar".formatted(BuildData.VERSION);
    try (var filePath = SoulFireAbstractLauncher.class.getResourceAsStream("/META-INF/jars/" + fileName)) {
      var fileBytes = Objects.requireNonNull(filePath, "Managed mod file not found: %s".formatted(fileName)).readAllBytes();
      var targetPath = modsPath.resolve("managed-" + fileName);
      Files.write(targetPath, fileBytes);
      System.out.printf("Copied managed mod: %s%n", targetPath);
    } catch (Exception e) {
      throw new RuntimeException("Failed to copy managed mod: " + fileName, e);
    }
  }

  @SuppressWarnings("unused")
  public static void runPostLib(Path basePath, String bootstrapClassName, String[] args) {
    try {
      injectEarlyExceptionHandler();
      System.setProperty("sf.baseDir", basePath.toAbsolutePath().toString());
      System.setProperty("java.awt.headless", "true");
      System.setProperty("joml.nounsafe", "true");
      System.setProperty(SystemProperties.SKIP_MC_PROVIDER, "true");
      System.setProperty("sf.bootstrap.class", bootstrapClassName);
      System.setProperty("sf.initial.arguments", joinBase64(args));

      injectEarlyMixins();
      setupManagedMods(basePath);
      SFMinecraftDownloader.loadAndInjectMinecraftJar(basePath);

      KnotClient.main(new String[]{"--username", "SoulFire"});
    } catch (Throwable t) {
      // Catches fabric errors during launch
      System.err.println("Fatal error during SoulFire launch:");
      t.printStackTrace(System.err);
      LogManager.shutdown();
    }
  }

  /// This injects a super early exception handler before mixins are loaded.
  /// We need mixins for log4j and other stuff, so we use stderr directly here.
  /// A later exception handler will use log4j.
  private static void injectEarlyExceptionHandler() {
    Thread.setDefaultUncaughtExceptionHandler(
      (thread, throwable) -> {
        System.err.println("Uncaught exception in thread " + thread.getName() + ":");
        throwable.printStackTrace(System.err);
        LogManager.shutdown();
      });
  }

  private static void injectEarlyMixins() {
    var classProvider = new CustomClassProvider(List.of(SoulFireAbstractLauncher.class.getClassLoader()));
    var transformerManager = new TransformerManager(classProvider);
    transformerManager.addTransformerPreprocessor(new MixinsTranslator());
    transformerManager.addTransformer("com.soulfiremc.launcher.mixin.*");

    try {
      transformerManager.hookInstrumentation(Agents.getInstrumentation());
      IO.println("Used Runtime Agent to inject mixins");
    } catch (IOException t) {
      throw new IllegalStateException("Failed to inject mixins", t);
    }
  }

  private static String joinBase64(String[] args) {
    return Arrays.stream(args)
      .map(arg -> Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)))
      .collect(Collectors.joining(","));
  }
}
