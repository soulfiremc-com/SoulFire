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
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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

    var source = Path.of(System.getProperty("sf.managedMod"));
    Files.copy(source, modsPath.resolve("managed-" + source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
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

      IO.println("[bootstrap] Installing startup hooks");
      injectEarlyMixins();
      IO.println("[bootstrap] Installing Soulfire mod");
      setupManagedMods(basePath);
      System.setProperty(SystemProperties.GAME_MAPPING_NAMESPACE, "official");
      System.setProperty(SystemProperties.GAME_JAR_PATH_CLIENT, System.getProperty("sf.minecraftJar"));
      System.setProperty(SystemProperties.RUNTIME_MAPPING_NAMESPACE, "official");

      var assetIndex = System.getProperty("sf.assetIndex");
      IO.println("[bootstrap] Starting Fabric Loader");
      KnotClient.main(new String[]{
        "--graphicsBackend", "VULKAN",
        "--username", "Soulfire",
        "--assetsDir", basePath.resolve("assets").toAbsolutePath().toString(),
        "--assetIndex", assetIndex
      });
    } catch (Throwable t) {
      // Catches fabric errors during launch
      System.err.println("Fatal error during Soulfire launch:");
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
    var classProvider = new CustomClassProvider(List.of(SoulFirePostLibLauncher.class.getClassLoader()));
    var transformerManager = new TransformerManager(classProvider);
    transformerManager.addTransformerPreprocessor(new MixinsTranslator());
    transformerManager.addTransformer("com.soulfiremc.launcher.mixin.*");

    try {
      transformerManager.hookInstrumentation(Agents.getInstrumentation());
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
