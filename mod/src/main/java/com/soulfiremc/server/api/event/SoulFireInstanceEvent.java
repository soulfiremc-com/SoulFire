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
package com.soulfiremc.server.api.event;

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;

/// Represents an attack event of a Soulfire instance.
public non-sealed interface SoulFireInstanceEvent extends SoulFireEvent, SoulFireGlobalEvent {
  InstanceManager instanceManager();

  @Override
  default SoulFireServer soulFireServer() {
    return instanceManager().soulFireServer();
  }
}
