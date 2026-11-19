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

import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class JwtServerInterceptorTest {
  @Test
  void treatsATimedOutConnectionWaitAsTransient() {
    // As in a live run: the single SQLite connection stayed busy, and the
    // token lookup timed out waiting for it.
    var failure = new DataAccessException(
      "Error getting connection from data source HikariDataSource (HikariPool-1)",
      new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 30001ms"));

    assertTrue(JwtServerInterceptor.isTransientDatabaseFailure(failure));
  }

  @Test
  void treatsOtherDatabaseFailuresAsPermanent() {
    var failure = new DataAccessException("no such table: users", new SQLException("no such table: users"));

    assertFalse(JwtServerInterceptor.isTransientDatabaseFailure(failure));
  }
}
