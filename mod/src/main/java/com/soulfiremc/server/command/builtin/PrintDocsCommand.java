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
import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.command.brigadier.BrigadierHelper;
import com.soulfiremc.server.settings.instance.BotSettings;
import com.viaversion.viaaprilfools.api.AprilFoolsProtocolVersion;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.vialegacy.api.LegacyProtocolVersion;

import java.util.Objects;

import static com.soulfiremc.server.command.brigadier.BrigadierHelper.literal;
import static com.soulfiremc.server.command.brigadier.BrigadierHelper.privateCommand;

public final class PrintDocsCommand {
  private PrintDocsCommand() {
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
      literal("print-docs")
        .requires(CommandSourceStack.IS_ADMIN)
        .then(literal("commands")
          .executes(
            privateCommand(
              c -> {
                var builder = new StringBuilder("\n");
                for (var command : BrigadierHelper.getAllUsage(dispatcher, dispatcher.getRoot(), c.getSource())) {
                  builder.append("| `%s{:bash}` | %s |%n".formatted(
                    command.command(),
                    Objects.requireNonNullElse(command.helpMeta().help(), "")
                      .replace("|", "\\|")
                      .replace("\n", " ")
                  ));
                }
                c.getSource().source().sendInfo(builder.toString());

                return Command.SINGLE_SUCCESS;
              })))
        .then(literal("plugins")
          .executes(
            privateCommand(
              c -> {
                var builder = new StringBuilder("\n");
                for (var plugin : SoulFireAPI.getServerExtensions()) {
                  if (!(plugin instanceof InternalPlugin)) {
                    continue;
                  }

                  var pluginInfo = plugin.pluginInfo();
                  builder.append("| `%s` | %s | %s | %s |%n".formatted(
                    pluginInfo.id(),
                    pluginInfo.description()
                      .replace("|", "\\|")
                      .replace("\n", " "),
                    pluginInfo.author(),
                    pluginInfo.license()
                  ));
                }
                c.getSource().source().sendInfo(builder.toString());

                return Command.SINGLE_SUCCESS;
              })))
        .then(literal("versions")
          .executes(
            privateCommand(
              c -> {
                var builder = new StringBuilder("\n");
                BotSettings.getAvailableProtocolVersions()
                  .forEach(
                    version -> {
                      var versionId = "%s\\|%d".formatted(version.getVersionType().name(), version.getOriginalVersion());
                      String type;
                      if (BedrockProtocolVersion.PROTOCOLS.contains(version)) {
                        type = "BEDROCK";
                      } else if (LegacyProtocolVersion.PROTOCOLS.contains(version)) {
                        type = "LEGACY";
                      } else if (AprilFoolsProtocolVersion.PROTOCOLS.contains(version)) {
                        type = "SNAPSHOT";
                      } else {
                        type = "JAVA";
                      }

                      builder.append("| `%s`%s | `%s` | `%s` |%n".formatted(
                        version.getName(),
                        ProtocolTranslationImpl.NATIVE_VERSION == version ? " (native)" : "",
                        versionId,
                        type
                      ));
                    });
                c.getSource().source().sendInfo(builder.toString());

                return Command.SINGLE_SUCCESS;
              }))));
  }
}
