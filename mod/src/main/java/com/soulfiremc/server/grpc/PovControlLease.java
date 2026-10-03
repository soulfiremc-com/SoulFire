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
package com.soulfiremc.server.grpc;

import com.soulfiremc.server.bot.BotControlLeaseManager;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.UUID;

/// A POV session's control lease, held only while its viewer controls the bot, so SDK clients
/// can drive a bot that is only watched.
@RequiredArgsConstructor
final class PovControlLease {
  private static final Duration TTL = Duration.ofSeconds(10);
  private final BotControlLeaseManager leases;
  private final UUID botId;
  private final UUID owner;
  private @Nullable String token;
  private boolean closed;

  /// Takes or renews the lease. Throws LeaseUnavailableException while another client holds it,
  /// and NOT_FOUND once the session closed.
  synchronized void hold() {
    if (closed) throw Status.NOT_FOUND.withDescription("POV session ended").asRuntimeException();
    if (token == null) token = leases.acquire(botId, owner, TTL).token();
    else leases.renew(botId, owner, token, TTL);
  }

  synchronized void release() {
    if (token == null) return;
    try { leases.release(botId, owner, token); }
    catch (BotControlLeaseManager.InvalidLeaseException _) { /* Expired leases already release ownership. */ }
    token = null;
  }

  /// Releases the lease for good: the session ended.
  synchronized void close() {
    closed = true;
    release();
  }
}
