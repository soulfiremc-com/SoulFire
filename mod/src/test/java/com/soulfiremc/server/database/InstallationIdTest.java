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
import com.soulfiremc.server.util.SFPathConstants;
import org.flywaydb.core.Flyway;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

final class InstallationIdTest {
  @Test
  void persistsAcrossConnectionsAndDatabaseCopies(@TempDir Path directory) throws Exception {
    var path = directory.resolve("original.sqlite");
    var source = database(path);
    var first = InstallationId.getOrCreate(DSL.using(source, SQLDialect.SQLITE));
    assertNotEquals(new UUID(0, 0), first);
    assertEquals(first, InstallationId.getOrCreate(DSL.using(source, SQLDialect.SQLITE)));

    var copy = directory.resolve("restored.sqlite");
    Files.copy(path, copy);
    assertEquals(first, InstallationId.getOrCreate(DSL.using(database(copy), SQLDialect.SQLITE)));
    assertEquals(1, DSL.using(source, SQLDialect.SQLITE).fetchCount(Tables.SERVER_METADATA));
  }

  @Test
  void independentDatabasesHaveDifferentIdentities(@TempDir Path directory) {
    var first = InstallationId.getOrCreate(DSL.using(database(directory.resolve("first.sqlite")), SQLDialect.SQLITE));
    var second = InstallationId.getOrCreate(DSL.using(database(directory.resolve("second.sqlite")), SQLDialect.SQLITE));
    assertNotEquals(first, second);
  }

  @Test
  void existingIdentityDoesNotNeedTheSqliteWriteLock(@TempDir Path directory) throws Exception {
    var source = database(directory.resolve("existing.sqlite"));
    source.setBusyTimeout(1);
    var dsl = DSL.using(source, SQLDialect.SQLITE);
    var expected = InstallationId.getOrCreate(dsl);
    try (var connection = DriverManager.getConnection(source.getUrl());
         var statement = connection.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
      try {
        assertEquals(expected, InstallationId.getOrCreate(dsl));
      } finally {
        statement.execute("ROLLBACK");
      }
    }
  }

  @Test
  void initializationRetriesAfterSqliteReleasesTheWriteLock(@TempDir Path directory) throws Exception {
    var source = database(directory.resolve("retry.sqlite"));
    source.setBusyTimeout(1);
    var dsl = DSL.using(source, SQLDialect.SQLITE);
    var busy = new CountDownLatch(1);
    dsl.configuration().set(ExecuteListener.onException(_ -> busy.countDown()));
    try (var connection = DriverManager.getConnection(source.getUrl());
         var statement = connection.createStatement();
         var executor = Executors.newSingleThreadExecutor()) {
      statement.execute("BEGIN IMMEDIATE");
      var result = executor.submit(() -> InstallationId.getOrCreate(dsl));
      try {
        assertTrue(busy.await(10, TimeUnit.SECONDS));
      } finally {
        statement.execute("ROLLBACK");
      }
      assertEquals(result.get(10, TimeUnit.SECONDS), InstallationId.getOrCreate(dsl));
    }
  }

  @Test
  void permanentLockErrorsStopAfterThreeAttempts(@TempDir Path directory) throws Exception {
    var source = database(directory.resolve("locked.sqlite"));
    source.setBusyTimeout(1);
    var dsl = DSL.using(source, SQLDialect.SQLITE);
    var failures = new AtomicInteger();
    dsl.configuration().set(ExecuteListener.onException(_ -> failures.incrementAndGet()));
    try (var connection = DriverManager.getConnection(source.getUrl());
         var statement = connection.createStatement()) {
      statement.execute("BEGIN IMMEDIATE");
      try {
        assertThrows(DataAccessException.class, () -> InstallationId.getOrCreate(dsl));
        assertEquals(3, failures.get());
        assertEquals(0, dsl.fetchCount(Tables.SERVER_METADATA));
      } finally {
        statement.execute("ROLLBACK");
      }
    }
  }

  @Test
  void unrelatedDatabaseErrorsDoNotRetry(@TempDir Path directory) {
    var source = database(directory.resolve("missing.sqlite"));
    var dsl = DSL.using(source, SQLDialect.SQLITE);
    dsl.dropTable(Tables.SERVER_METADATA).execute();
    var failures = new AtomicInteger();
    dsl.configuration().set(ExecuteListener.onException(_ -> failures.incrementAndGet()));
    assertThrows(DataAccessException.class, () -> InstallationId.getOrCreate(dsl));
    assertEquals(1, failures.get());
  }

  @Test
  void runtimeConnectionsWaitForSqliteLocks(@TempDir Path directory) {
    try (var paths = mockStatic(SFPathConstants.class)) {
      paths.when(SFPathConstants::baseDirectory).thenReturn(directory);
      try (var database = DatabaseManager.select()) {
        assertEquals(30_000, database.dsl().fetchValue("PRAGMA busy_timeout", Integer.class));
      }
    }
  }

  @Test
  void concurrentInitializationReturnsThePersistedWinner(@TempDir Path directory) throws Exception {
    var source = database(directory.resolve("concurrent.sqlite"));
    var ready = new CountDownLatch(8);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(8)) {
      var results = new ArrayList<Future<UUID>>();
      for (var i = 0; i < 8; i++) {
        results.add(executor.submit(() -> {
          ready.countDown();
          assertTrue(start.await(10, TimeUnit.SECONDS));
          return InstallationId.getOrCreate(DSL.using(source, SQLDialect.SQLITE));
        }));
      }
      try {
        assertTrue(ready.await(10, TimeUnit.SECONDS));
      } finally {
        start.countDown();
      }
      var expected = results.getFirst().get(10, TimeUnit.SECONDS);
      for (var result : results) {
        assertEquals(expected, result.get(10, TimeUnit.SECONDS));
      }
      assertEquals(1, DSL.using(source, SQLDialect.SQLITE).fetchCount(Tables.SERVER_METADATA));
    }
  }

  @Test
  void upgradesWithoutChangingServerSettingsOrUsers(@TempDir Path directory) {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("upgrade.sqlite"));
    Flyway.configure().dataSource(source).locations("classpath:db/migration").target("9").load().migrate();
    var dsl = DSL.using(source, SQLDialect.SQLITE);
    dsl.insertInto(Tables.SERVER_CONFIG).set(Tables.SERVER_CONFIG.ID, 1L)
      .set(Tables.SERVER_CONFIG.SETTINGS, "{\"server\":{}}").set(Tables.SERVER_CONFIG.VERSION, 7L).execute();
    dsl.execute("INSERT INTO users (id, username, email, role, min_issued_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
      new UUID(0, 0).toString(), "root", "root@example.com", "ADMIN");

    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    var id = InstallationId.getOrCreate(dsl);
    assertEquals(id, InstallationId.getOrCreate(dsl));
    var settings = dsl.selectFrom(Tables.SERVER_CONFIG).fetchSingle();
    assertEquals("{\"server\":{}}", settings.getSettings());
    assertEquals(7L, settings.getVersion());
    assertEquals(new UUID(0, 0).toString(), dsl.select(Tables.USERS.ID).from(Tables.USERS).fetchSingle(Tables.USERS.ID));
  }

  private static SQLiteDataSource database(Path path) {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + path);
    source.setBusyTimeout(10_000);
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    return source;
  }
}
