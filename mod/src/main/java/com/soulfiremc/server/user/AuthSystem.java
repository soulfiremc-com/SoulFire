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
package com.soulfiremc.server.user;

import com.soulfiremc.grpc.generated.InstancePermission;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.UserSessionsInvalidatedEvent;
import com.soulfiremc.server.database.UserRole;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.database.generated.tables.records.UsersRecord;
import com.soulfiremc.server.grpc.LogServiceImpl;
import com.soulfiremc.server.settings.lib.ServerSettingsSource;
import com.soulfiremc.server.settings.server.ServerSettings;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import org.apache.commons.validator.routines.EmailValidator;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.event.Level;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

@Getter
@Slf4j
public final class AuthSystem {
  public static final UUID ROOT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
  public static final String ROOT_DEFAULT_EMAIL = "root@soulfiremc.com";
  public static final String ROOT_DEFAULT_EMAIL_PROPERTY = "sf.root.default.email";
  public static final String ROOT_DEFAULT_EMAIL_ENV = "SF_ROOT_DEFAULT_EMAIL";
  private static final String SESSION_VERSION_CLAIM = "sessionVersion";
  private final LogServiceImpl.StateHolder logService;
  private final ServerSettingsSource settingsSource;
  private final SecretKey jwtSecretKey;
  private final JwtParser parser;
  private final DSLContext dsl;
  private final SoulFireServer server;

  public AuthSystem(SoulFireServer soulFireServer, DSLContext dsl) {
    this.server = soulFireServer;
    this.logService = soulFireServer.logStateHolder();
    this.jwtSecretKey = soulFireServer.jwtSecretKey();
    this.parser = Jwts.parser().verifyWith(soulFireServer.jwtSecretKey()).build();
    this.settingsSource = soulFireServer.settingsSource();
    this.dsl = dsl;

    createRootUser();
  }

  public static String resolveRootDefaultEmail() {
    return resolveRootDefaultEmail(
      System.getProperty(ROOT_DEFAULT_EMAIL_PROPERTY),
      System.getenv(ROOT_DEFAULT_EMAIL_ENV)
    );
  }

  public static String resolveRootDefaultEmail(String propertyValue, String envValue) {
    var propertyOverride = sanitizeRootDefaultEmail(propertyValue);
    if (propertyOverride != null) {
      return propertyOverride;
    }

    var envOverride = sanitizeRootDefaultEmail(envValue);
    if (envOverride != null) {
      return envOverride;
    }

    return ROOT_DEFAULT_EMAIL;
  }

  public static boolean isDefaultRootEmail(String email) {
    return ROOT_DEFAULT_EMAIL.equals(email);
  }

  private void createRootUser() {
    var rootDefaultEmail = configuredRootDefaultEmail();

    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var currentRootUser = ctx.selectFrom(Tables.USERS).where(Tables.USERS.ID.eq(ROOT_USER_ID.toString())).fetchOne();
      if (currentRootUser == null) {
        // Use USERNAME in the WHERE clause (not ID) to handle old Hibernate databases
        // where UUIDs may be stored in a binary format that doesn't match text comparison
        var randomSuffix = UUID.randomUUID().toString().substring(0, 6);
        ctx.update(Tables.USERS)
          .set(Tables.USERS.USERNAME, "old-root-%s".formatted(randomSuffix))
          .set(Tables.USERS.EMAIL, "old-root-%s@soulfiremc.com".formatted(randomSuffix))
          .set(Tables.USERS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.USERS.USERNAME.eq("root"))
          .execute();
      }
    });

    dsl.transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var currentRootUser = ctx.selectFrom(Tables.USERS).where(Tables.USERS.ID.eq(ROOT_USER_ID.toString())).fetchOne();
      if (currentRootUser == null) {
        var now = LocalDateTime.now(ZoneOffset.UTC);
        ctx.insertInto(Tables.USERS)
          .set(Tables.USERS.ID, ROOT_USER_ID.toString())
          .set(Tables.USERS.USERNAME, "root")
          .set(Tables.USERS.ROLE, UserRole.ADMIN.name())
          .set(Tables.USERS.EMAIL, rootDefaultEmail)
          .set(Tables.USERS.MIN_ISSUED_AT, now)
          .set(Tables.USERS.CREATED_AT, now)
          .set(Tables.USERS.UPDATED_AT, now)
          .set(Tables.USERS.VERSION, 0L)
          .execute();
        if (isDefaultRootEmail(rootDefaultEmail)) {
          log.warn("The root user email is defaulted to '{}'. Please change it using the command 'set-email <email>'", rootDefaultEmail);
        }
      } else {
        ctx.update(Tables.USERS)
          .set(Tables.USERS.ROLE, UserRole.ADMIN.name())
          .set(Tables.USERS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.USERS.ID.eq(ROOT_USER_ID.toString()))
          .execute();
        if (isDefaultRootEmail(currentRootUser.getEmail())) {
          log.warn("The root user email is currently set to '{}'. Please change it using the command 'set-email <email>'", currentRootUser.getEmail());
        }
      }
    });
  }

  private String configuredRootDefaultEmail() {
    var propertyValue = System.getProperty(ROOT_DEFAULT_EMAIL_PROPERTY);
    var propertyOverride = sanitizeRootDefaultEmail(propertyValue);
    if (propertyOverride != null) {
      return propertyOverride;
    }
    warnAboutInvalidRootDefaultEmail(ROOT_DEFAULT_EMAIL_PROPERTY, propertyValue);

    var envValue = System.getenv(ROOT_DEFAULT_EMAIL_ENV);
    var envOverride = sanitizeRootDefaultEmail(envValue);
    if (envOverride != null) {
      return envOverride;
    }
    warnAboutInvalidRootDefaultEmail(ROOT_DEFAULT_EMAIL_ENV, envValue);

    return ROOT_DEFAULT_EMAIL;
  }

  private static String sanitizeRootDefaultEmail(String configuredValue) {
    if (configuredValue == null) {
      return null;
    }

    var normalized = configuredValue.strip();
    if (normalized.isEmpty() || !EmailValidator.getInstance().isValid(normalized)) {
      return null;
    }

    return normalized;
  }

  private void warnAboutInvalidRootDefaultEmail(String sourceName, String configuredValue) {
    if (configuredValue == null || configuredValue.isBlank()) {
      return;
    }

    log.warn("Ignoring invalid root default email override from {}: '{}'", sourceName, configuredValue);
  }

  public Optional<SoulFireUser> authenticateByHeader(String authorization, String audience) {
    if (authorization.startsWith("Bearer ")) {
      return authenticateByToken(authorization.substring("Bearer ".length()), audience);
    } else if (authorization.startsWith("Basic ")) {
      var credentials = authorization.substring("Basic ".length());
      var decoded = new String(Base64.getDecoder().decode(credentials), StandardCharsets.UTF_8);
      var parts = decoded.split(":", 2);
      if (parts.length != 2) {
        return Optional.empty();
      }

      return authenticateByToken(parts[1], audience);
    } else {
      return Optional.empty();
    }
  }

  public Optional<SoulFireUser> authenticateByToken(String token, String audience) {
    UUID userId;
    Instant issuedAt;
    long sessionVersion;
    try {
      var claims = parser.parseSignedClaims(token).getPayload();
      if (claims.getAudience() == null || !claims.getAudience().contains(audience)
        || claims.getSubject() == null || claims.getIssuedAt() == null) {
        return Optional.empty();
      }
      userId = UUID.fromString(claims.getSubject());
      issuedAt = claims.getIssuedAt().toInstant();
      // Tokens issued before session versioning belong to the initial generation.
      var claimedVersion = claims.get(SESSION_VERSION_CLAIM, Long.class);
      sessionVersion = claimedVersion == null ? 0L : claimedVersion;
      if (sessionVersion < 0) return Optional.empty();
    } catch (JwtException | IllegalArgumentException _) {
      return Optional.empty();
    }

    return authenticateBySubject(userId, issuedAt, sessionVersion);
  }

  public Optional<SoulFireUser> authenticateBySubject(UUID uuid, Instant issuedAt, long sessionVersion) {
    return dsl.transactionResult(cfg -> {
      var ctx = DSL.using(cfg);
      var userRecord = ctx.selectFrom(Tables.USERS).where(Tables.USERS.ID.eq(uuid.toString())).fetchOne();
      if (userRecord == null || userRecord.getSessionVersion() != sessionVersion) {
        return Optional.empty();
      }

      // Preserve timestamp revocations made before session versioning was introduced.
      // JWT timestamps only have second precision; new revocations also advance the version.
      var minIssuedAt = userRecord.getMinIssuedAt().toInstant(ZoneOffset.UTC);
      if (issuedAt.isBefore(minIssuedAt.truncatedTo(ChronoUnit.SECONDS))) {
        return Optional.empty();
      }

      // Only update login if last login was more than 30 seconds ago
      var lastLogin = userRecord.getLastLoginAt();
      var lastLoginInstant = lastLogin != null ? lastLogin.toInstant(ZoneOffset.UTC) : null;
      if (lastLoginInstant == null || lastLoginInstant.isBefore(Instant.now().minusSeconds(30))) {
        ctx.update(Tables.USERS)
          .set(Tables.USERS.LAST_LOGIN_AT, LocalDateTime.now(ZoneOffset.UTC))
          .set(Tables.USERS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.USERS.ID.eq(uuid.toString()))
          .execute();
      }

      return Optional.of(new SoulFireUserImpl(logService, userRecord, dsl, settingsSource, issuedAt, sessionVersion));
    });
  }

  public void deleteUser(UUID uuid) {
    if (uuid.equals(ROOT_USER_ID)) {
      throw new IllegalArgumentException("Cannot delete root user");
    }

    dsl.deleteFrom(Tables.USERS).where(Tables.USERS.ID.eq(uuid.toString())).execute();

    SoulFireAPI.postEvent(new UserSessionsInvalidatedEvent(server, uuid, Long.MAX_VALUE));
    logService.disconnect(uuid);
  }

  public void invalidateSessions(UUID uuid) {
    var now = LocalDateTime.now(ZoneOffset.UTC);
    var sessionVersion = dsl.transactionResult(cfg -> {
      var ctx = DSL.using(cfg);
      var updated = ctx.update(Tables.USERS)
        .set(Tables.USERS.MIN_ISSUED_AT, now)
        .set(Tables.USERS.SESSION_VERSION, Tables.USERS.SESSION_VERSION.plus(1L))
        .set(Tables.USERS.UPDATED_AT, now)
        .where(Tables.USERS.ID.eq(uuid.toString()))
        .execute();
      if (updated == 0) {
        throw new IllegalArgumentException("User not found: " + uuid);
      }
      return ctx.select(Tables.USERS.SESSION_VERSION).from(Tables.USERS)
        .where(Tables.USERS.ID.eq(uuid.toString())).fetchSingle(Tables.USERS.SESSION_VERSION);
    });
    SoulFireAPI.postEvent(new UserSessionsInvalidatedEvent(server, uuid, sessionVersion));
  }

  public UsersRecord rootUserData() {
    return getUserData(ROOT_USER_ID).orElseThrow();
  }

  public Optional<UsersRecord> getUserData(UUID uuid) {
    return Optional.ofNullable(dsl.selectFrom(Tables.USERS).where(Tables.USERS.ID.eq(uuid.toString())).fetchOne());
  }

  public String generateJWT(UsersRecord user, String audience) {
    return Jwts.builder()
      .signWith(jwtSecretKey, Jwts.SIG.HS256)
      .claims()
      .subject(UUID.fromString(user.getId()).toString())
      .issuedAt(Date.from(Instant.now()))
      .add(SESSION_VERSION_CLAIM, user.getSessionVersion())
      .audience()
      .add(audience)
      .and()
      .and()
      .compact();
  }

  private record SoulFireUserImpl(
    LogServiceImpl.StateHolder logService,
    UsersRecord userData,
    DSLContext dsl,
    ServerSettingsSource settingsSource,
    Instant issuedAt,
    long sessionVersion
  ) implements SoulFireUser {
    @Override
    public UUID getUniqueId() {
      return UUID.fromString(userData.getId());
    }

    @Override
    public String getUsername() {
      return userData.getUsername();
    }

    @Override
    public String getEmail() {
      return userData.getEmail();
    }

    @Override
    public UserRole getRole() {
      return UserRole.valueOf(userData.getRole());
    }

    @Override
    public Instant getIssuedAt() {
      return issuedAt;
    }

    @Override
    public long getSessionVersion() {
      return sessionVersion;
    }

    @Override
    public TriState getPermission(PermissionContext permission) {
      // Admins have all permissions
      if (isAdmin()) {
        return TriState.TRUE;
      }

      // Permissions for normal users
      return switch (permission) {
        case PermissionContext.GlobalContext global -> switch (global.globalPermission()) {
          case GLOBAL_COMMAND_EXECUTION,
               DELETE_USER, UPDATE_USER, READ_USER,
               CREATE_USER, UPDATE_SERVER_CONFIG, READ_SERVER_CONFIG,
               GLOBAL_SUBSCRIBE_LOGS, INVALIDATE_SESSIONS,
               GENERATE_API_TOKEN -> TriState.FALSE;
          case CREATE_INSTANCE -> TriState.byBoolean(settingsSource.get(ServerSettings.ALLOW_CREATING_INSTANCES));
          case UPDATE_SELF_USERNAME -> TriState.byBoolean(settingsSource.get(ServerSettings.ALLOW_UPDATING_SELF_USERNAME));
          case UPDATE_SELF_EMAIL -> TriState.byBoolean(settingsSource.get(ServerSettings.ALLOW_UPDATING_SELF_EMAIL));
          case READ_CLIENT_DATA, GENERATE_SELF_WEBDAV_TOKEN, GENERATE_SELF_API_TOKEN,
               INVALIDATE_SELF_SESSIONS -> TriState.TRUE;
          case UNRECOGNIZED -> throw new IllegalStateException("Unexpected value: " + global.globalPermission());
        };
        case PermissionContext.InstanceContext instance -> instancePermission(instance.instancePermission(),
          () -> isOwnerOfInstance(instance.instanceId()));
        case PermissionContext.PluginContext plugin -> pluginPermission(plugin);
      };
    }

    @Override
    public Predicate<InstancePermission> instancePermissionChecker(UUID instanceId) {
      if (isAdmin()) return _ -> true;
      var owned = isOwnerOfInstance(instanceId);
      BooleanSupplier ownership = () -> owned;
      return permission -> instancePermission(permission, ownership).toBooleanOrElse(false);
    }

    private TriState instancePermission(InstancePermission permission, BooleanSupplier ownership) {
      return switch (permission) {
        case DELETE_INSTANCE -> TriState.byBoolean(ownership.getAsBoolean()
          && settingsSource.get(ServerSettings.ALLOW_DELETING_INSTANCES));
        case UPDATE_INSTANCE_META -> TriState.byBoolean(ownership.getAsBoolean()
          && settingsSource.get(ServerSettings.ALLOW_CHANGING_INSTANCE_META));
        case INSTANCE_COMMAND_EXECUTION, INSTANCE_SUBSCRIBE_LOGS,
             ACCESS_OBJECT_STORAGE,
             DOWNLOAD_URL, CHECK_PROXY, AUTHENTICATE_MC_ACCOUNT,
             CONTROL_BOTS, CONTROL_BOT_ACTIONS, EXECUTE_SCRIPTS, UPDATE_INSTANCE_CONFIG,
             READ_INSTANCE, READ_INSTANCE_AUDIT_LOGS,
             READ_BOT_INFO, UPDATE_BOT_CONFIG -> TriState.byBoolean(ownership.getAsBoolean());
        case RAW_PROTOCOL -> TriState.FALSE;
        case UNRECOGNIZED -> throw new IllegalStateException("Unexpected value: " + permission);
      };
    }

    @Override
    public void sendMessage(Level level, Component message) {
      logService.sendPersonalMessage(UUID.fromString(userData.getId()), message);
    }

    private boolean isOwnerOfInstance(UUID instanceId) {
      var count = dsl.selectCount()
        .from(Tables.INSTANCES)
        .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
        .and(Tables.INSTANCES.OWNER_ID.eq(userData.getId()))
        .fetchOne(0, int.class);
      return count != null && count > 0;
    }

    private TriState pluginPermission(PermissionContext.PluginContext permission) {
      var target = PluginPermissionGrantScope.fromContext(permission);
      var explicit = dsl.select(Tables.PLUGIN_PERMISSION_GRANTS.GRANTED)
        .from(Tables.PLUGIN_PERMISSION_GRANTS)
        .where(Tables.PLUGIN_PERMISSION_GRANTS.USER_ID.eq(userData.getId()))
        .and(Tables.PLUGIN_PERMISSION_GRANTS.PERMISSION_ID.eq(permission.permission().id()))
        .and(Tables.PLUGIN_PERMISSION_GRANTS.SCOPE.eq(target.scope()))
        .and(Tables.PLUGIN_PERMISSION_GRANTS.RESOURCE_ID.eq(target.resourceId()))
        .fetchOne(Tables.PLUGIN_PERMISSION_GRANTS.GRANTED);
      if (explicit != null) {
        return TriState.byBoolean(explicit);
      }
      return switch (permission.permission().definition().defaultGrant()) {
        case ADMIN_ONLY -> TriState.FALSE;
        case AUTHENTICATED -> TriState.TRUE;
        case INSTANCE_OWNER -> TriState.byBoolean(
          permission.instanceId().map(this::isOwnerOfInstance).orElse(false)
        );
      };
    }

    private boolean isAdmin() {
      return UserRole.valueOf(userData.getRole()) == UserRole.ADMIN;
    }
  }
}
