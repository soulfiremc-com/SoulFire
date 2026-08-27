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
package com.soulfiremc.bootstrap;

import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.Plugin;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.util.PortHelper;
import com.soulfiremc.server.util.SFPathConstants;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Scanner;
import java.util.Set;

/// This class prepares the earliest work possible, such as loading mixins and setting up logging.
@Slf4j
public abstract class SoulFireAbstractBootstrap {
  private static final Set<String> RESERVED_FLAGS = Set.of("sf.flags.v2");
  public static final Instant START_TIME = Instant.now();

  protected SoulFireAbstractBootstrap() {}

  public static int getRPCPort(int defaultPort) {
    return Integer.getInteger("sf.grpc.port", defaultPort);
  }

  public static int getRandomRPCPort() {
    var port = getRPCPort(0);

    return port == 0 ? PortHelper.getRandomAvailablePort() : port;
  }

  public static String getRPCHost(String defaultHost) {
    return System.getProperty("sf.grpc.host", defaultHost);
  }

  private void initPlugins() {
    try (var scanResult =
           new ClassGraph()
             .verbose()
             .enableAllInfo()
             .acceptPackages("com.soulfiremc.server.plugins")
             .scan()) {
      scanResult.getClassesWithAnnotation(InternalPluginClass.class)
        .stream()
        .sorted((a, b) -> {
          var aOrder = (int) a.getAnnotationInfo(InternalPluginClass.class).getParameterValues().getValue("order");
          var bOrder = (int) b.getAnnotationInfo(InternalPluginClass.class).getParameterValues().getValue("order");
          return Integer.compare(aOrder, bOrder);
        })
        .forEach(this::processRouteClass);
    } catch (Throwable t) {
      log.error("Failed to initialize plugins", t);
      throw new RuntimeException("Failed to initialize plugins", t);
    }
  }

  private void processRouteClass(ClassInfo classInfo) {
    try {
      var clazz = Class.forName(classInfo.getName());
      var instance = clazz.getConstructor().newInstance();
      if (!(instance instanceof Plugin plugin)) {
        throw new IllegalStateException("Annotated plugin class does not extend Plugin: " + classInfo.getName());
      }
      SoulFireAPI.loadPlugin(plugin);
    } catch (Throwable t) {
      log.error("Failed to load plugin class {}", classInfo.getName(), t);
    }
  }

  @SneakyThrows
  protected void internalBootstrap(String[] args) {
    try {
      injectFileProperties();

      initPlugins();

      injectMixinsAndRun(args);
    } catch (Throwable t) {
      // Ensure logs are written out on fatal errors
      log.error("Failed to start server", t);
      LogManager.shutdown();
      System.exit(1);
    }
  }

  private void injectFileProperties() {
    var optionsFile = SFPathConstants.baseDirectory().resolve("soulfire.properties");
    if (!Files.exists(optionsFile)) {
      return;
    }

    log.info("Loading options from {}", optionsFile);
    try (var scanner = new Scanner(optionsFile)) {
      var lineNumber = 0;
      while (scanner.hasNextLine()) {
        lineNumber++;
        var line = scanner.nextLine();
        if (line.startsWith("#") || line.isBlank()) {
          continue;
        }

        var parts = line.split("=", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
          log.warn("Invalid line in options file at line {}: {}", lineNumber, line);
          continue;
        }

        var parsedKey = parts[0].strip();
        var parsedValue = parts[1].strip();
        if (!parsedKey.startsWith("sf.") || RESERVED_FLAGS.stream().anyMatch(parsedKey::equalsIgnoreCase)) {
          log.warn("Invalid key in options file at line {}: {}", lineNumber, parsedKey);
          continue;
        }

        System.setProperty(parsedKey, parsedValue);
      }
    } catch (IOException e) {
      log.error("Failed to read options file", e);
    }
  }

  public void injectMixinsAndRun(String[] args) {
    this.postMixinMain(args);
  }

  protected abstract void postMixinMain(String[] args);
}
