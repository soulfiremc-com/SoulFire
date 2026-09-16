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
package com.soulfiremc.server.command.builtin;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.renderer.RenderConstants;
import com.soulfiremc.server.renderer.RendererDebugDump;
import com.soulfiremc.server.util.SFPathConstants;

import java.io.IOException;
import java.nio.file.Files;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class ExportRenderDebugDumpCommand {
  private ExportRenderDebugDumpCommand() {}

  private static int exportRenderDebugDump(
    CommandContext<CommandSourceStack> context,
    int width,
    int height,
    int requestedMaxDistance
  ) throws CommandSyntaxException {
    var currentTime = System.currentTimeMillis();
    return forEveryBot(
      context,
      bot -> {
        var minecraft = bot.minecraft();
        var level = minecraft.level;
        var player = minecraft.player;
        if (level == null || player == null) {
          context.getSource().source().sendWarn("No world is loaded.");
          return Command.SINGLE_SUCCESS;
        }

        var renderDistanceChunks = minecraft.options.getEffectiveRenderDistance();
        var maxDistance = requestedMaxDistance > 0 ? requestedMaxDistance : renderDistanceChunks * 16;
        var rendersDirectory = SFPathConstants.getRendersDirectory(bot.instanceManager().getInstanceObjectStoragePath());
        var dumpDirectory = rendersDirectory.resolve("render_debug_%d_%s".formatted(currentTime, safeFilePart(bot.accountName())));

        context.getSource().source().sendInfo(
          "Writing renderer debug dump {}x{} (render distance: {} blocks) to {}...",
          width,
          height,
          maxDistance,
          dumpDirectory
        );

        try {
          Files.createDirectories(dumpDirectory);
          var result = RendererDebugDump.dump(
            level,
            player,
            width,
            height,
            RenderConstants.DEFAULT_FOV,
            maxDistance,
            dumpDirectory
          );
          context.getSource().source().sendInfo("PNG: {}", result.frame());
          context.getSource().source().sendInfo("Scene data: {}", result.scene());
        } catch (IOException e) {
          context.getSource().source().sendError("Renderer debug dump export failed.", e);
        }

        return Command.SINGLE_SUCCESS;
      });
  }

  private static String safeFilePart(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("export-render-debug-dump")
        .executes(
          help(
            "Export a POV image, camera settings, Vulkan device, and capture timing.",
            c -> exportRenderDebugDump(c, RenderConstants.DEFAULT_WIDTH, RenderConstants.DEFAULT_HEIGHT, -1)))
        .then(
          argument("width", IntegerArgumentType.integer(1, 3840))
            .then(
              argument("height", IntegerArgumentType.integer(1, 2160))
                .executes(
                  help(
                    "Export a debug dump at a custom resolution.",
                    c -> exportRenderDebugDump(
                      c,
                      IntegerArgumentType.getInteger(c, "width"),
                      IntegerArgumentType.getInteger(c, "height"),
                      -1)))
                .then(
                  argument("max_distance", IntegerArgumentType.integer(1, 2048))
                    .executes(
                      help(
                        "Export a debug dump at a custom resolution and maximum render distance.",
                        c -> exportRenderDebugDump(
                          c,
                          IntegerArgumentType.getInteger(c, "width"),
                          IntegerArgumentType.getInteger(c, "height"),
                          IntegerArgumentType.getInteger(c, "max_distance"))))))));
  }
}
