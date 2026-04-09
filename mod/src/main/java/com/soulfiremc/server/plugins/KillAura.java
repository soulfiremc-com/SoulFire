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
package com.soulfiremc.server.plugins;

import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.bot.BotPostEntityTickEvent;
import com.soulfiremc.server.api.event.bot.BotPreEntityTickEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@InternalPluginClass
public final class KillAura extends InternalPlugin {
  private static final MetadataKey<Integer> COOLDOWN = MetadataKey.of("kill_aura", "cooldown", Integer.class);

  public KillAura() {
    super(new PluginInfo(
      "kill-aura",
      "1.0.0",
      "Automatically attacks entities",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com/docs/how-to/kill-aura"
    ));
  }

  @EventHandler
  public static void onPreEntityTick(BotPreEntityTickEvent event) {
    var bot = event.connection();
    if (!bot.settingsSource().get(KillAuraSettings.ENABLE)) {
      return;
    }

    var control = bot.botControl();
    var localPlayer = bot.minecraft().player;
    if (control.hasActiveTask()) {
      return;
    }

    var whitelistedUsers = bot.settingsSource().get(KillAuraSettings.WHITELISTED_USERS);

    var lookRange = bot.settingsSource().get(KillAuraSettings.LOOK_RANGE);
    var hitRange = bot.settingsSource().get(KillAuraSettings.HIT_RANGE);
    var swingRange = bot.settingsSource().get(KillAuraSettings.SWING_RANGE);

    var max = Math.max(lookRange, Math.max(hitRange, swingRange));

    Entity target =
      getClosestEntity(
        bot,
        max,
        whitelistedUsers,
        true,
        true,
        bot.settingsSource().get(KillAuraSettings.CHECK_WALLS));

    if (target == null) {
      return;
    }

    Vec3 bestVisiblePoint = getEntityVisiblePoint(bot, target);
    if (bestVisiblePoint == null) {
      bestVisiblePoint = target.getEyePosition();
    }

    var distance = bestVisiblePoint.distanceTo(localPlayer.getEyePosition());

    if (distance > lookRange) {
      return;
    }

    if (!control.tryStart(ControlTask.marker("Kill aura", ControlPriority.LOW, new KillAuraMarker(target, distance)))) {
      return;
    }
    localPlayer.lookAt(EntityAnchorArgument.Anchor.EYES, bestVisiblePoint);
  }

  @EventHandler
  public static void onPostEntityTick(BotPostEntityTickEvent event) {
    var bot = event.connection();
    var localPlayer = bot.minecraft().player;
    var control = bot.botControl();
    if (localPlayer.getAttackStrengthScale(0) != 1F) {
      return;
    }

    int cooldownTicks = bot.metadata().getOrDefault(COOLDOWN, 0);
    if (cooldownTicks > 0) {
      bot.metadata().set(COOLDOWN, cooldownTicks - 1);
      return;
    }

    var marker = control.claimMarker(KillAuraMarker.class);
    if (marker == null) {
      return;
    }

    var hitRange = bot.settingsSource().get(KillAuraSettings.HIT_RANGE);
    var swingRange = bot.settingsSource().get(KillAuraSettings.SWING_RANGE);
    var swing = marker.distance() <= swingRange;
    if (marker.distance() <= hitRange) {
      bot.minecraft().gameMode.attack(localPlayer, marker.attackEntity());
      localPlayer.swing(InteractionHand.MAIN_HAND);
    } else if (swing) {
      localPlayer.swing(InteractionHand.MAIN_HAND);
    }

    // Custom attack delay for specific scenarios
    if (bot.currentProtocolVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)
      || bot.settingsSource().get(KillAuraSettings.IGNORE_COOLDOWN)) {
      bot.metadata().set(COOLDOWN, bot.settingsSource().getRandom(KillAuraSettings.ATTACK_DELAY_TICKS).getAsInt());
    }
  }

  public static @Nullable Vec3 getEntityVisiblePoint(BotConnection connection, Entity entity) {
    var points = new ArrayList<Vec3>();
    double halfWidth = entity.getBbWidth() / 2;
    double halfHeight = entity.getBbHeight() / 2;
    for (var x = -1; x <= 1; x++) {
      for (var y = 0; y <= 2; y++) {
        for (var z = -1; z <= 1; z++) {
          // skip the middle point because you're supposed to look at hitbox faces
          if (x == 0 && y == 1 && z == 0) {
            continue;
          }
          points.add(
            new Vec3(
              entity.getX() + halfWidth * x,
              entity.getY() + halfHeight * y,
              entity.getZ() + halfWidth * z));
        }
      }
    }

    var eye = connection.minecraft().player.getEyePosition();

    // sort by distance to the bot
    points.sort(Comparator.comparingDouble(eye::distanceTo));

    // remove the farthest points because they're not "visible"
    for (var i = 0; i < 4; i++) {
      points.removeLast();
    }

    for (var point : points) {
      if (canSee(connection, point)) {
        return point;
      }
    }

    return null;
  }

  public static @Nullable Entity getClosestEntity(
    BotConnection connection,
    double range,
    List<String> whitelistedUsers,
    boolean ignoreBots,
    boolean onlyInteractable,
    boolean mustBeSeen) {
    var player = connection.minecraft().player;

    if (player == null) {
      return null;
    }

    var x = player.getX();
    var y = player.getY();
    var z = player.getZ();

    Entity closest = null;
    var closestDistance = Double.MAX_VALUE;

    for (var entity : connection.minecraft().level.entitiesForRendering()) {
      if (entity.getId() == player.getId()) {
        continue;
      }

      var distance = entity.getPosition(0).distanceTo(new Vec3(x, y, z));
      if (distance > range) {
        continue;
      }

      if (onlyInteractable && !entity.isAttackable()) {
        continue;
      }

      if (onlyInteractable && entity instanceof LivingEntity le && !le.canBeSeenAsEnemy()) {
        continue;
      }

      if (onlyInteractable && entity instanceof AbstractClientPlayer acp && (acp.isCreative() || acp.isSpectator())) {
        continue;
      }

      if (!whitelistedUsers.isEmpty()
        && entity.getType() == EntityType.PLAYER) {
        var playerListEntry = connection.minecraft().player.connection.getPlayerInfo(entity.getUUID());
        if (playerListEntry != null && whitelistedUsers.stream()
          .anyMatch(whitelistedUser -> playerListEntry.getProfile().name().equalsIgnoreCase(whitelistedUser))) {
          continue;
        }
      }

      if (ignoreBots
        && connection.instanceManager().getConnectedBots().stream()
        .anyMatch(
          b -> {
            var player2 = b.minecraft().player;
            if (player2 == null) {
              return false;
            }

            return player2.getUUID().equals(entity.getUUID());
          })) {
        continue;
      }

      if (mustBeSeen && !canSee(connection, entity)) {
        continue;
      }

      if (distance < closestDistance) {
        closest = entity;
        closestDistance = distance;
      }
    }

    return closest;
  }

  public static boolean canSee(BotConnection connection, Entity entity) {
    return getEntityVisiblePoint(connection, entity) != null;
  }

  public static boolean canSee(BotConnection connection, Vec3 vec) { // intensive method, don't use it too often
    var level = connection.minecraft().level;

    var eye = connection.minecraft().player.getEyePosition();
    var distance = eye.distanceTo(vec);
    if (distance >= 256) {
      return false;
    }

    var blockVec = BlockPos.containing(vec);
    if (!level.isLoaded(blockVec)) {
      return false;
    }

    return isNotIntersected(level.getBlockCollisions(connection.minecraft().player, new AABB(eye, vec)), eye, vec);
  }

  private static boolean isNotIntersected(Iterable<VoxelShape> shapes, Vec3 start, Vec3 end) {
    for (var shape : shapes) {
      var aabb = shape.bounds();
      if (
        AABB.clip(
          aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ,
          start, end
        ).isPresent()
      ) {
        return false;
      }
    }

    return true;
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(KillAuraSettings.class, "kill-aura", "Kill Aura", this, "skull", KillAuraSettings.ENABLE);
  }

  @NoArgsConstructor(access = AccessLevel.NONE)
  private static class KillAuraSettings implements SettingsObject {
    private static final String NAMESPACE = "kill-aura";
    public static final BooleanProperty<SettingsSource.Bot> ENABLE =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enable")
        .uiName("Enable")
        .description("Enable KillAura")
        .defaultValue(false)
        .build();
    public static final StringListProperty<SettingsSource.Bot> WHITELISTED_USERS =
      ImmutableStringListProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("whitelisted-users")
        .uiName("Whitelisted Users")
        .description("These users will be ignored by the kill aura")
        .addDefaultValue("Dinnerbone")
        .build();
    public static final DoubleProperty<SettingsSource.Bot> HIT_RANGE =
      ImmutableDoubleProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("hit-range")
        .uiName("Hit Range")
        .description("Range for the kill aura where the bot will start hitting the entity")
        .defaultValue(3.0d)
        .minValue(0.5d)
        .maxValue(6.0d)
        .stepValue(0.1d)
        .build();
    public static final DoubleProperty<SettingsSource.Bot> SWING_RANGE =
      ImmutableDoubleProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("swing-range")
        .uiName("Swing Range")
        .description("Range for the kill aura where the bot will start swinging arm, set to 0 to disable")
        .defaultValue(3.5d)
        .minValue(0.0d)
        .maxValue(10.0d)
        .stepValue(0.1d)
        .build();
    public static final DoubleProperty<SettingsSource.Bot> LOOK_RANGE =
      ImmutableDoubleProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("look-range")
        .uiName("Look Range")
        .description("Range for the kill aura where the bot will start looking at the entity, set to 0 to disable")
        .defaultValue(4.8d)
        .minValue(0.0d)
        .maxValue(25.0d)
        .stepValue(0.1d)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> CHECK_WALLS =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("check-walls")
        .uiName("Check Walls")
        .description("Check if the entity is behind a wall")
        .defaultValue(true)
        .build();
    public static final BooleanProperty<SettingsSource.Bot> IGNORE_COOLDOWN =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("ignore-cooldown")
        .uiName("Ignore Cooldown")
        .description("Ignore the 1.9+ attack cooldown to act like a 1.8 kill aura")
        .defaultValue(false)
        .build();
    public static final MinMaxProperty<SettingsSource.Bot> ATTACK_DELAY_TICKS =
      ImmutableMinMaxProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("attack-delay-ticks")
        .minValue(1)
        .maxValue(20)
        .minEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Attack Delay Ticks Min")
          .description("Minimum tick delay between attacks on pre-1.9 versions")
          .defaultValue(8)
          .build())
        .maxEntry(ImmutableMinMaxPropertyEntry.builder()
          .uiName("Attack Delay Ticks Max")
          .description("Maximum tick delay between attacks on pre-1.9 versions")
          .defaultValue(12)
          .build())
        .build();
  }

  private record KillAuraMarker(Entity attackEntity, double distance) {
  }
}
