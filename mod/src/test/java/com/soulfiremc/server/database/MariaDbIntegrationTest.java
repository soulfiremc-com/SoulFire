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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "SF_TEST_MARIADB_HOST", matches = ".+")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class MariaDbIntegrationTest {
  @Test
  void connectsMigratesAndPersistsWithTheLegacyTlsDefault() {
    var settings = Map.of(
      "database", "mysql",
      "mysql.host", System.getenv("SF_TEST_MARIADB_HOST"),
      "mysql.database", "soulfire_validation",
      "mysql.user", "validation",
      "mysql.password", "validation-only"
    );
    var original = new LinkedHashMap<String, String>();
    settings.forEach((key, value) -> original.put(key, System.setProperty(key, value)));
    original.put("mysql.sslMode", System.clearProperty("mysql.sslMode"));

    try (var database = DatabaseManager.select()) {
      var id = UUID.randomUUID().toString();
      var username = "validation-" + id.substring(0, 8);
      database.dsl().execute("INSERT INTO users (id, username, email, role, min_issued_at) VALUES (?, ?, ?, 'ADMIN', CURRENT_TIMESTAMP)",
        id, username, username + "@example.test");
      assertEquals(username, database.dsl().fetchValue("SELECT username FROM users WHERE id = ?", id));
      database.dsl().execute("DELETE FROM users WHERE id = ?", id);
      assertEquals(0, database.dsl().fetchCount(database.dsl().selectFrom("users").where("id = ?", id)));
      assertEquals("", database.dsl().fetch("SHOW SESSION STATUS LIKE 'Ssl_cipher'").getFirst().get(1, String.class));
    } finally {
      original.forEach((key, value) -> {
        if (value == null) {
          System.clearProperty(key);
        } else {
          System.setProperty(key, value);
        }
      });
    }
  }
}
