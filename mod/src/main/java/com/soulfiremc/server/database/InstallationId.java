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
package com.soulfiremc.server.database;

import com.soulfiremc.server.database.generated.Tables;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.time.Duration;
import java.util.UUID;

public final class InstallationId {
  private InstallationId() {
  }

  /// Keeps the installation identity with its database, including across restarts and restores.
  public static UUID getOrCreate(DSLContext dsl) {
    for (var attempt = 0; ; attempt++) {
      try {
        return readOrCreate(dsl);
      } catch (DataAccessException error) {
        if (attempt >= 2 || dsl.dialect().family() != SQLDialect.SQLITE || !isBusy(error)) {
          throw error;
        }
        try {
          Thread.sleep(Duration.ofMillis(100L << attempt));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new DataAccessException("Interrupted while loading the installation ID", interrupted);
        }
      }
    }
  }

  private static boolean isBusy(Throwable error) {
    for (var cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLiteException sqlite && (sqlite.getResultCode().code & 0xff) == SQLiteErrorCode.SQLITE_BUSY.code) {
        return true;
      }
    }
    return false;
  }

  private static UUID readOrCreate(DSLContext dsl) {
    var metadata = Tables.SERVER_METADATA;
    var existing = dsl.select(metadata.INSTALLATION_ID).from(metadata)
      .where(metadata.ID.eq(1L)).fetchOne(metadata.INSTALLATION_ID);
    if (existing != null) {
      return UUID.fromString(existing);
    }
    // Let the primary key choose a single identity when multiple processes initialize together.
    dsl.insertInto(metadata)
      .set(metadata.ID, 1L)
      .set(metadata.INSTALLATION_ID, UUID.randomUUID().toString())
      .onDuplicateKeyIgnore()
      .execute();
    return UUID.fromString(dsl.select(metadata.INSTALLATION_ID)
      .from(metadata)
      .where(metadata.ID.eq(1L))
      .fetchSingle(metadata.INSTALLATION_ID));
  }
}
