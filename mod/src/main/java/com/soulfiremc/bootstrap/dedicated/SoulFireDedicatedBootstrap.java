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
package com.soulfiremc.bootstrap.dedicated;

import com.soulfiremc.bootstrap.SoulFireAbstractBootstrap;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.command.CommandSourceStack;
import com.soulfiremc.server.command.ConsoleCommandSource;
import com.soulfiremc.server.user.AuthSystem;
import com.soulfiremc.server.util.PortHelper;
import com.soulfiremc.server.util.SFPathConstants;
import com.soulfiremc.server.util.log4j.GenericTerminalConsole;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@Slf4j
public final class SoulFireDedicatedBootstrap extends SoulFireAbstractBootstrap {
  private SoulFireDedicatedBootstrap() {
    super();
  }

  @SuppressWarnings("unused")
  public static void bootstrap(String[] args) {
    new SoulFireDedicatedBootstrap().internalBootstrap(args);
  }

  @Override
  protected void postMixinMain(String[] args) {
    var host = SoulFireAbstractBootstrap.getRPCHost("0.0.0.0");
    var port = SoulFireAbstractBootstrap.getRPCPort(PortHelper.SF_DEFAULT_PORT);

    var soulFire =
      new SoulFireServer(host, port, SoulFireAbstractBootstrap.START_TIME);

    if (Boolean.getBoolean("sf.dumpOpenApi")) {
      try {
        Files.write(SFPathConstants.BASE_DIR.resolve("sf-openapi.json"), soulFire.rpcServer().openApiService().openApiDocument());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      soulFire.shutdownManager().shutdownSoftware(true);
      return;
    }

    if (AuthSystem.ROOT_DEFAULT_EMAIL.equals(soulFire.authSystem().rootUserData().getEmail())) {
      log.info("The root users email is '{}', please change it using the command 'set-email <email>', you can login with the client using that email", AuthSystem.ROOT_DEFAULT_EMAIL);
    }

    var commandManager = soulFire.serverCommandManager();
    var commandSource = new ConsoleCommandSource(soulFire.authSystem());
    new GenericTerminalConsole(
      soulFire.shutdownManager(),
      command -> {
        try {
          return commandManager.execute(command, CommandSourceStack.ofUnrestricted(soulFire, commandSource));
        } catch (Throwable t) {
          log.atError().setCause(t).log("Error while executing command '{}'", command);
          return 0;
        }
      },
      (command, cursor) -> {
        try {
          return commandManager.complete(command, cursor, CommandSourceStack.ofUnrestricted(soulFire, commandSource));
        } catch (Throwable t) {
          log.atError().setCause(t).log("Error while tab completing command '{}'", command);
          return List.of();
        }
      },
      SFPathConstants.BASE_DIR
    ).start();

    soulFire.shutdownManager().awaitShutdown();
  }
}
