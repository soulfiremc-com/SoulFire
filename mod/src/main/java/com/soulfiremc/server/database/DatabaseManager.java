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

import com.soulfiremc.server.util.SFPathConstants;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
public final class DatabaseManager {
  private DatabaseManager() {
  }

  public static DatabaseContext select() {
    return switch (System.getProperty("database", "sqlite")) {
      case "sqlite" -> forSqlite();
      case "mysql" -> forMysql();
      default -> throw new IllegalArgumentException("Invalid database type");
    };
  }

  private static DatabaseContext forSqlite() {
    var dbFile = SFPathConstants.baseDirectory().resolve("soulfire.sqlite");
    var jdbcUrl = "jdbc:sqlite:%s".formatted(dbFile);

    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(jdbcUrl);
    hikariConfig.setMinimumIdle(1);
    hikariConfig.setMaximumPoolSize(1);
    hikariConfig.setIdleTimeout(0);
    hikariConfig.setConnectionTimeout(30_000);

    var dataSource = new HikariDataSource(hikariConfig);

    runMigrations(dataSource);

    var dsl = createDslContext(dataSource, SQLDialect.SQLITE);
    return new DatabaseContext(dsl, dataSource);
  }

  private static DatabaseContext forMysql() {
    var jdbcUrl = "jdbc:mysql://%s:%s/%s".formatted(
      System.getProperty("mysql.host", "localhost"),
      Integer.getInteger("mysql.port", 3306),
      System.getProperty("mysql.database", "soulfire")
    );

    var hikariConfig = new HikariConfig();
    hikariConfig.setJdbcUrl(jdbcUrl);
    hikariConfig.setUsername(System.getProperty("mysql.user"));
    hikariConfig.setPassword(System.getProperty("mysql.password"));
    hikariConfig.setMinimumIdle(Integer.getInteger("mysql.minimumIdle", 1));
    hikariConfig.setMaximumPoolSize(Integer.getInteger("mysql.maximumPoolSize", 10));
    hikariConfig.setIdleTimeout(0);
    hikariConfig.setConnectionTimeout(30_000);

    var dataSource = new HikariDataSource(hikariConfig);

    runMigrations(dataSource);

    var dsl = createDslContext(dataSource, SQLDialect.MARIADB);
    return new DatabaseContext(dsl, dataSource);
  }

  private static void runMigrations(DataSource dataSource) {
    var flyway = Flyway.configure()
      .dataSource(dataSource)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .baselineVersion("1")
      .load();
    flyway.migrate();
  }

  /// Detects an existing Hibernate-created schema and migrates it to the
  /// Flyway V1 format. Handles four independent issues:
  /// 1. camelCase column names → snake_case (including attackLifecycle → session_lifecycle)
  /// 2. Binary UUID BLOBs → hyphenated text format
  /// 3. Incompatible scripts table (old-style scripts → new node-based scripting)
  /// 4. Old audit log type values (e.g. START_ATTACK → START_SESSION)
  /// Each step is idempotent and can run independently.
  private static void migrateFromHibernate(DataSource dataSource) {
    try (var conn = dataSource.getConnection()) {
      // Check if users table exists at all
      try (var rs = conn.getMetaData().getTables(null, null, "users", null)) {
        if (!rs.next()) {
          return; // Fresh database, nothing to migrate
        }
      }

      // Check for camelCase columns (not yet renamed)
      var needsColumnRename = false;
      try (var rs = conn.getMetaData().getColumns(null, null, "users", "createdAt")) {
        needsColumnRename = rs.next();
      }

      // Check for binary UUID values
      var needsUuidConversion = false;
      try (var stmt = conn.createStatement();
           var rs = stmt.executeQuery("SELECT typeof(id) FROM users LIMIT 1")) {
        if (rs.next()) {
          needsUuidConversion = "blob".equals(rs.getString(1));
        }
      }

      // Check for old "attackLifecycle" column (predecessor of "session_lifecycle")
      var needsLegacyRename = false;
      try (var rs = conn.getMetaData().getColumns(null, null, "instances", "attackLifecycle")) {
        needsLegacyRename = rs.next();
      }

      // Check if scripts table has old incompatible schema (old-style scripts
      // with scriptName/elevatedPermissions vs new node-based scripting)
      var needsScriptsRecreate = false;
      try (var rs = conn.getMetaData().getColumns(null, null, "scripts", "scriptName")) {
        needsScriptsRecreate = rs.next();
      }

      if (!needsColumnRename && !needsUuidConversion && !needsLegacyRename && !needsScriptsRecreate) {
        return; // Already fully migrated
      }

      log.info("Detected old Hibernate schema (renameColumns={}, convertUuids={}, legacyRename={}, recreateScripts={}), migrating...",
        needsColumnRename, needsUuidConversion, needsLegacyRename, needsScriptsRecreate);
      try (var stmt = conn.createStatement()) {
        if (needsUuidConversion) {
          // Convert binary UUID columns to text format (Hibernate stores UUIDs as BLOBs)
          convertBinaryUuids(stmt, "users", "id");
          convertBinaryUuids(stmt, "instances", "id");
          convertBinaryUuids(stmt, "instances", "owner_id");
          convertBinaryUuids(stmt, "instance_audit_logs", "id");
          convertBinaryUuids(stmt, "instance_audit_logs", "instance_id");
          convertBinaryUuids(stmt, "instance_audit_logs", "user_id");
          if (!needsScriptsRecreate) {
            convertBinaryUuids(stmt, "scripts", "id");
            convertBinaryUuids(stmt, "scripts", "instance_id");
          }
        }

        if (needsColumnRename) {
          // users
          renameColumn(stmt, "users", "createdAt", "created_at");
          renameColumn(stmt, "users", "updatedAt", "updated_at");
          renameColumn(stmt, "users", "lastLoginAt", "last_login_at");
          renameColumn(stmt, "users", "minIssuedAt", "min_issued_at");

          // instances
          renameColumn(stmt, "instances", "friendlyName", "friendly_name");
          renameColumn(stmt, "instances", "sessionLifecycle", "session_lifecycle");
          renameColumn(stmt, "instances", "createdAt", "created_at");
          renameColumn(stmt, "instances", "updatedAt", "updated_at");

          // instance_audit_logs
          renameColumn(stmt, "instance_audit_logs", "createdAt", "created_at");
          renameColumn(stmt, "instance_audit_logs", "updatedAt", "updated_at");

          if (!needsScriptsRecreate) {
            // Only rename script columns if we're not recreating the table
            renameColumn(stmt, "scripts", "nodesJson", "nodes_json");
            renameColumn(stmt, "scripts", "edgesJson", "edges_json");
            renameColumn(stmt, "scripts", "createdAt", "created_at");
            renameColumn(stmt, "scripts", "updatedAt", "updated_at");
          }
        }

        // Rename old "attackLifecycle" column to "session_lifecycle"
        if (needsLegacyRename) {
          renameColumn(stmt, "instances", "attackLifecycle", "session_lifecycle");

          // Update old audit log type values
          executeQuietly(stmt, "UPDATE instance_audit_logs SET type = 'START_SESSION' WHERE type = 'START_ATTACK'");
          executeQuietly(stmt, "UPDATE instance_audit_logs SET type = 'PAUSE_SESSION' WHERE type = 'PAUSE_ATTACK'");
          executeQuietly(stmt, "UPDATE instance_audit_logs SET type = 'RESUME_SESSION' WHERE type = 'RESUME_ATTACK'");
          executeQuietly(stmt, "UPDATE instance_audit_logs SET type = 'STOP_SESSION' WHERE type = 'STOP_ATTACK'");
        }

        // Drop and recreate the scripts table if it has the old incompatible schema
        if (needsScriptsRecreate) {
          executeQuietly(stmt, "DROP TABLE scripts");
          stmt.execute("""
            CREATE TABLE scripts (
              id VARCHAR(36) NOT NULL PRIMARY KEY,
              name VARCHAR(100) NOT NULL,
              description VARCHAR(1000),
              instance_id VARCHAR(36) NOT NULL,
              nodes_json TEXT NOT NULL DEFAULT '[]',
              edges_json TEXT NOT NULL DEFAULT '[]',
              paused BOOLEAN NOT NULL DEFAULT FALSE,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              version BIGINT NOT NULL DEFAULT 0,
              FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE
            )""");
        }
      }

      log.info("Hibernate schema migration complete");
    } catch (SQLException e) {
      log.warn("Could not migrate from Hibernate schema", e);
    }
  }

  /// Converts binary UUID values (stored by Hibernate as BLOBs) to the standard
  /// hyphenated text format expected by JOOQ. Only updates rows where the column
  /// contains BLOB data (typeof returns 'blob'), leaving text UUIDs untouched.
  private static void convertBinaryUuids(
    Statement stmt, String table, String column) {
    try {
      // SQLite: convert 16-byte binary UUID to hyphenated text format
      // lower(hex(x)) gives 32 hex chars, then we insert hyphens at positions 8, 12, 16, 20
      stmt.execute("""
        UPDATE %s SET %s =
          substr(lower(hex(%s)), 1, 8) || '-' ||
          substr(lower(hex(%s)), 9, 4) || '-' ||
          substr(lower(hex(%s)), 13, 4) || '-' ||
          substr(lower(hex(%s)), 17, 4) || '-' ||
          substr(lower(hex(%s)), 21, 12)
        WHERE typeof(%s) = 'blob'
        """.formatted(table, column, column, column, column, column, column, column));
    } catch (SQLException e) {
      log.debug("Could not convert binary UUIDs in {}.{}: {}", table, column, e.getMessage());
    }
  }

  private static void renameColumn(
    Statement stmt, String table, String oldName, String newName) {
    try {
      stmt.execute("ALTER TABLE %s RENAME COLUMN %s TO %s".formatted(table, oldName, newName));
    } catch (SQLException e) {
      log.debug("Could not rename column {}.{} (may not exist): {}", table, oldName, e.getMessage());
    }
  }

  private static void addColumn(
    Statement stmt, String table, String column, String definition) {
    try {
      stmt.execute("ALTER TABLE %s ADD COLUMN %s %s".formatted(table, column, definition));
    } catch (SQLException e) {
      log.debug("Could not add column {}.{} (may already exist): {}", table, column, e.getMessage());
    }
  }

  private static void executeQuietly(Statement stmt, String sql) {
    try {
      stmt.execute(sql);
    } catch (SQLException e) {
      log.debug("Could not execute migration SQL [{}]: {}", sql, e.getMessage());
    }
  }

  private static DSLContext createDslContext(DataSource dataSource, SQLDialect dialect) {
    var config = new DefaultConfiguration()
      .set(dataSource)
      .set(dialect)
      .set(new DefaultConfiguration().settings()
        .withExecuteWithOptimisticLocking(true));
    return DSL.using(config);
  }

  public record DatabaseContext(DSLContext dsl, HikariDataSource dataSource) implements AutoCloseable {
    @Override
    public void close() {
      dataSource.close();
    }
  }
}
