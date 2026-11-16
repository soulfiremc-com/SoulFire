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
package com.soulfiremc.server.api.event.bot;

import com.soulfiremc.server.api.event.SoulFireBotEvent;
import com.soulfiremc.server.bot.BotConnection;
import org.checkerframework.checker.nullness.qual.Nullable;

/// This event is called when the bot takes damage (health decreases).
///
/// @param connection    The bot connection instance.
/// @param previousHealth The health before the damage was taken.
/// @param newHealth     The health after the damage was taken.
/// @param damageAmount  The amount of damage taken (previousHealth - newHealth).
/// @param damageType    The damage type id, such as `minecraft:freeze`, or null when the server did not report one.
public record BotDamageEvent(
  BotConnection connection,
  float previousHealth,
  float newHealth,
  float damageAmount,
  @Nullable String damageType
) implements SoulFireBotEvent {
}
