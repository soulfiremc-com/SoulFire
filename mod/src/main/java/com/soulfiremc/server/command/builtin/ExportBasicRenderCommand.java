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
import com.soulfiremc.server.renderer.VulkanRenderer;
import com.soulfiremc.server.util.SFPathConstants;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.*;

public final class ExportBasicRenderCommand {
  private ExportBasicRenderCommand() {}

  private static int exportBasicRender(
    CommandContext<CommandSourceStack> context,
    int width,
    int height) throws CommandSyntaxException {
    var currentTime = System.currentTimeMillis();
    return forEveryBot(
      context,
      bot -> {
        var level = bot.minecraft().level;
        var player = bot.minecraft().player;
        if (level == null || player == null) {
          context.getSource().source().sendWarn("No world is loaded.");
          return Command.SINGLE_SUCCESS;
        }

        // Get render distance from settings (in chunks) and convert to blocks
        var renderDistanceChunks = bot.minecraft().options.getEffectiveRenderDistance();
        var maxDistance = renderDistanceChunks * 16;

        context.getSource().source().sendInfo("Rendering {}x{} image (render distance: {} chunks / {} blocks)...",
          width, height, renderDistanceChunks, maxDistance);

        var renderStart = System.currentTimeMillis();

        var image = VulkanRenderer.render(
          level,
          player,
          width,
          height,
          RenderConstants.DEFAULT_FOV,
          maxDistance
        );

        var renderTime = System.currentTimeMillis() - renderStart;
        context.getSource().source().sendInfo("Render completed in {}ms", renderTime);

        var fileName = "render_%d_%s.png".formatted(currentTime, bot.accountName());
        try {
          var rendersDirectory = SFPathConstants.getRendersDirectory(bot.instanceManager().getInstanceObjectStoragePath());
          Files.createDirectories(rendersDirectory);
          var file = rendersDirectory.resolve(fileName);
          ImageIO.write(image, "png", file.toFile());
          context.getSource().source().sendInfo("Exported render to {}", file);
        } catch (IOException e) {
          context.getSource().source().sendError("Render export failed.", e);
        }

        return Command.SINGLE_SUCCESS;
      });
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("export-basic-render")
        .executes(
          help(
            "Export a basic camera image of the world that the bot sees.",
            c -> exportBasicRender(c, RenderConstants.DEFAULT_WIDTH, RenderConstants.DEFAULT_HEIGHT)))
        .then(
          argument("width", IntegerArgumentType.integer(1, 3840))
            .then(
              argument("height", IntegerArgumentType.integer(1, 2160))
                .executes(
                  help(
                    "Export an image at a custom resolution.",
                    c -> exportBasicRender(
                      c,
                      IntegerArgumentType.getInteger(c, "width"),
                      IntegerArgumentType.getInteger(c, "height")))))));
  }
}
