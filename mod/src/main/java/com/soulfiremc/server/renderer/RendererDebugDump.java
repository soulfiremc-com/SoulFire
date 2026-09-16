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

import com.google.gson.GsonBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class RendererDebugDump {
  private RendererDebugDump() {}
  public static Result dump(ClientLevel level, LocalPlayer player, int width, int height, double fov, int maxDistance, Path directory) throws IOException {
    var options = VulkanRenderer.Options.defaults(player, width, height, fov, maxDistance).withForceDebugTrace(true);
    var result = VulkanRenderer.renderWithResult(level, player, options);
    Files.createDirectories(directory);
    var frame = directory.resolve("frame.png");
    var scene = directory.resolve("scene.json");
    ImageIO.write(result.image(), "png", frame.toFile());
    Files.writeString(scene, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("options", options, "trace", result.debugTrace())));
    return new Result(directory, frame, scene);
  }
  public record Result(Path directory, Path frame, Path scene) {}
}
