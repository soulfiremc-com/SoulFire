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
import com.soulfiremc.server.util.MouseClickHelper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    if (!control.tryStart(ControlTask.marker("Kill aura", ControlPriority.LOW, new KillAuraMarker(target)))) {
      return;
    }
    bot.rotationControl().lookAt(bestVisiblePoint);
  }

  @EventHandler
  public static void onPostEntityTick(BotPostEntityTickEvent event) {
    var bot = event.connection();
    var localPlayer = bot.minecraft().player;
    var marker = bot.botControl().claimMarker(KillAuraMarker.class);
    if (!bot.settingsSource().get(KillAuraSettings.ENABLE) || localPlayer == null) {
      return;
    }

    var cooldownTicks = bot.metadata().getOrDefault(COOLDOWN, 0);
    if (cooldownTicks > 0) {
      bot.metadata().set(COOLDOWN, cooldownTicks - 1);
      return;
    }

    var useAttackDelay = bot.currentProtocolVersion().olderThanOrEqualTo(ProtocolVersion.v1_8)
      || bot.settingsSource().get(KillAuraSettings.IGNORE_COOLDOWN);
    if (marker == null || (!useAttackDelay && localPlayer.getAttackStrengthScale(0) < 1F)) {
      return;
    }

    var target = marker.attackEntity();
    if (!target.isAlive()) {
      return;
    }

    var hitRange = bot.settingsSource().get(KillAuraSettings.HIT_RANGE);
    var swingRange = bot.settingsSource().get(KillAuraSettings.SWING_RANGE);
    var hitResult = localPlayer.raycastHitResult(1.0F, localPlayer);
    if (hitResult instanceof EntityHitResult entityHitResult
      && entityHitResult.getEntity() == target
      && hitResult.getLocation().distanceTo(localPlayer.getEyePosition()) <= hitRange) {
      MouseClickHelper.performLeftClick(bot.minecraft());
    } else {
      var visiblePoint = getEntityVisiblePoint(bot, target);
      if (visiblePoint == null) {
        visiblePoint = target.getEyePosition();
      }
      if (visiblePoint.distanceTo(localPlayer.getEyePosition()) > swingRange
        || !bot.rotationControl().isFacing(visiblePoint)) {
        return;
      }
      localPlayer.swing(InteractionHand.MAIN_HAND, localPlayer.getItemInHand(InteractionHand.MAIN_HAND).getAttackAnimation(), false);
      localPlayer.connection.send(ServerboundPunchPacket.INSTANCE);
    }

    if (useAttackDelay) {
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

    Entity closest = null;
    var closestDistanceSquared = Double.MAX_VALUE;
    var rangeSquared = range * range;

    for (var entity : connection.minecraft().level.entitiesForRendering()) {
      if (entity.getId() == player.getId()) {
        continue;
      }

      var distanceSquared = player.distanceToSqr(entity);
      if (distanceSquared > rangeSquared) {
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
        && entity.getType() == EntityTypes.PLAYER) {
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

      if (distanceSquared < closestDistanceSquared) {
        closest = entity;
        closestDistanceSquared = distanceSquared;
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

    return level.clip(new ClipContext(eye, vec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
      connection.minecraft().player)).getType() == HitResult.Type.MISS;
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
        .description("Maximum attack distance, limited by vanilla reach and crosshair targeting")
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
        .description("Only select targets with a visible point; attacks always respect walls")
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

  private record KillAuraMarker(Entity attackEntity) {
  }
}
