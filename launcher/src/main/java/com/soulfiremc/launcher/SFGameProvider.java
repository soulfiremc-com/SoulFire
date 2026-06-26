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
import net.fabricmc.loader.impl.game.LibClassifier;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.util.Arguments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class SFGameProvider extends MinecraftGameProvider {
  @SuppressWarnings("unchecked")
  @SneakyThrows
  @Override
  public boolean locateGame(FabricLauncher launcher, String[] args) {
    var logJarsField = MinecraftGameProvider.class.getDeclaredField("logJars");
    logJarsField.setAccessible(true);
    var logJars = (Set<Path>) logJarsField.get(this);
    LibClassifier<SFLibrary> classifier = new LibClassifier<>(SFLibrary.class, launcher.getEnvironmentType(), this);
    classifier.process(launcher.getClassPath());

    for (var lib : SFLibrary.LOGGING) {
      var origin = classifier.getOrigin(lib);
      if (origin == null) {
        continue;
      }

      logJars.add(origin);
    }

    var result = super.locateGame(launcher, args);

    var argumentsField = MinecraftGameProvider.class.getDeclaredField("arguments");
    argumentsField.setAccessible(true);
    var arguments = (Arguments) argumentsField.get(this);
    arguments.put("gameDir", getLaunchDirectory().toAbsolutePath().toString());

    return result;
  }

  @SneakyThrows
  @Override
  public Path getLaunchDirectory() {
    var launchDir = Path.of(System.getProperty("sf.baseDir")).resolve("minecraft");
    Files.createDirectories(launchDir);
    return launchDir;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
}
