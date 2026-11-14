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
package com.soulfiremc.server.script.nodes.data;

import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.script.*;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Data node that finds the nearest entity of a specific type.
/// Input: entityType (string, e.g., "minecraft:zombie", or "any" for any entity)
/// Input: maxDistance (double, maximum search radius)
/// Outputs: found (boolean), position (Vec3), entityId (int), distance (double)
public final class FindEntityNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("data.find_entity")
    .displayName("Find Entity")
    .category(CategoryRegistry.DATA)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("entityType", "Entity Type", PortType.STRING, "\"any\"", "Entity ID or 'any' for any entity"),
      PortDefinition.inputWithDefault("maxDistance", "Max Distance", PortType.NUMBER, "32", "Maximum search radius"),
      PortDefinition.inputWithDefault("excludeDead", "Exclude Dead", PortType.BOOLEAN, "true", "Skip dead or non-enemy entities"),
      PortDefinition.inputWithDefault("playersOnly", "Players Only", PortType.BOOLEAN, "false", "Only return player entities"),
      PortDefinition.inputWithDefault("excludeBots", "Exclude Bots", PortType.BOOLEAN, "false", "Skip other Soulfire bots in the same instance"),
      PortDefinition.inputWithDefault("mustBeSeen", "Must Be Seen", PortType.BOOLEAN, "false", "Only return entities with line-of-sight (raytrace)")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("found", "Found", PortType.BOOLEAN, "Whether an entity was found"),
      PortDefinition.output("position", "Position", PortType.VECTOR3, "Entity position"),
      PortDefinition.output("entityId", "Entity ID", PortType.NUMBER, "The entity's ID"),
      PortDefinition.output("distance", "Distance", PortType.NUMBER, "Distance to the entity")
    )
    .description("Finds the nearest entity of a specific type within range")
    .icon("target")
    .color("#9C27B0")
    .addKeywords("find", "entity", "search", "nearest", "mob", "player", "locate")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var level = bot.minecraft().level;
    var player = bot.minecraft().player;
    var entityTypeInput = getStringInput(inputs, "entityType", "any");
    var maxDistance = getDoubleInput(inputs, "maxDistance", 32.0);
    var excludeDead = getBooleanInput(inputs, "excludeDead", true);
    var playersOnly = getBooleanInput(inputs, "playersOnly", false);
    var excludeBots = getBooleanInput(inputs, "excludeBots", false);
    var mustBeSeen = getBooleanInput(inputs, "mustBeSeen", false);

    if (level == null || player == null) {
      return completedMono(notFoundResult());
    }

    var playerPos = player.position();
    var maxDistSq = maxDistance * maxDistance;

    // Find the nearest entity matching the criteria
    Entity nearestEntity = null;
    double nearestDistSq = Double.MAX_VALUE;

    for (var entity : level.entitiesForRendering()) {
      if (entity == player) {
        continue; // Skip self
      }

      var distSq = entity.distanceToSqr(playerPos);
      if (distSq > maxDistSq || distSq >= nearestDistSq) {
        continue;
      }

      // Entity type filter
      if (!"any".equalsIgnoreCase(entityTypeInput)) {
        var entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        if (!entityTypeId.equals(entityTypeInput) && !entityTypeId.equals("minecraft:" + entityTypeInput)) {
          continue;
        }
      }

      // Players only filter
      if (playersOnly && !(entity instanceof AbstractClientPlayer)) {
        continue;
      }

      // Exclude dead/non-enemy entities
      if (excludeDead) {
        if (!entity.isAlive()) {
          continue;
        }
        if (entity instanceof LivingEntity le && !le.canBeSeenAsEnemy()) {
          continue;
        }
        if (entity instanceof AbstractClientPlayer acp && (acp.isCreative() || acp.isSpectator())) {
          continue;
        }
      }

      // Exclude other Soulfire bots in the same instance
      if (excludeBots) {
        var isBot = bot.instanceManager().getConnectedBots().stream()
          .anyMatch(b -> {
            var p = b.minecraft().player;
            return p != null && p.getUUID().equals(entity.getUUID());
          });
        if (isBot) {
          continue;
        }
      }

      // Line-of-sight check
      if (mustBeSeen && !canSee(bot, entity.position())) {
        continue;
      }

      nearestEntity = entity;
      nearestDistSq = distSq;
    }

    if (nearestEntity == null) {
      return completedMono(notFoundResult());
    }

    return completedMono(results(
      "found", true,
      "position", nearestEntity.position(),
      "entityId", nearestEntity.getId(),
      "distance", Math.sqrt(nearestDistSq)
    ));
  }

  private static boolean canSee(BotConnection connection, Vec3 target) {
    var level = connection.minecraft().level;
    var player = connection.minecraft().player;
    if (level == null || player == null) {
      return false;
    }

    var eye = player.getEyePosition();
    if (eye.distanceTo(target) >= 256) {
      return false;
    }

    var blockVec = BlockPos.containing(target);
    if (!level.isLoaded(blockVec)) {
      return false;
    }

    for (var shape : level.getBlockCollisions(player, new AABB(eye, target))) {
      var aabb = shape.bounds();
      if (AABB.clip(
        aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ,
        eye, target
      ).isPresent()) {
        return false;
      }
    }
    return true;
  }

  private Map<String, NodeValue> notFoundResult() {
    return results(
      "found", false,
      "position", Vec3.ZERO,
      "entityId", -1,
      "distance", -1.0
    );
  }
}
