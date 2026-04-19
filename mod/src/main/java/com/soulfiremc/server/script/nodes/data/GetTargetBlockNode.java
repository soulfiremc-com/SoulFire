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

import com.soulfiremc.server.script.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import reactor.core.publisher.Mono;

import java.util.Map;

/// Data node that gets the block the bot is looking at.
/// Outputs: found, blockId, position, distance
public final class GetTargetBlockNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("data.get_target_block")
    .displayName("Get Target Block")
    .category(CategoryRegistry.DATA)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.botIn(),
      PortDefinition.inputWithDefault("maxDistance", "Max Distance", PortType.NUMBER, "5", "Maximum raycast distance")
    )
    .addOutputs(
      PortDefinition.execOut(),
      PortDefinition.output("found", "Found", PortType.BOOLEAN, "True if a block was found"),
      PortDefinition.output("blockId", "Block ID", PortType.STRING, "Block identifier"),
      PortDefinition.output("position", "Position", PortType.VECTOR3, "Block position"),
      PortDefinition.output("distance", "Distance", PortType.NUMBER, "Distance to block")
    )
    .description("Gets the block the bot is currently looking at (raycast)")
    .icon("crosshair")
    .color("#9C27B0")
    .addKeywords("target", "look", "raycast", "crosshair", "aim")
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var bot = requireBot(inputs);
    var level = bot.minecraft().level;
    var player = bot.minecraft().player;
    var maxDistance = getDoubleInput(inputs, "maxDistance", 5.0);

    if (level == null || player == null) {
      return completedMono(results(
        "found", false,
        "blockId", "minecraft:air",
        "position", Vec3.ZERO,
        "distance", 0.0
      ));
    }

    // Perform raycast from player's eyes in look direction
    var eyePos = player.getEyePosition();
    var lookVec = player.getLookAngle();
    var endPos = eyePos.add(lookVec.scale(maxDistance));

    var hitResult = level.clip(new ClipContext(
      eyePos, endPos,
      ClipContext.Block.OUTLINE,
      ClipContext.Fluid.NONE,
      player
    ));

    if (hitResult.getType() == HitResult.Type.BLOCK) {
      var blockHit = hitResult;
      var blockPos = blockHit.getBlockPos();
      var blockState = level.getBlockState(blockPos);
      var blockId = BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString();
      var distance = eyePos.distanceTo(hitResult.getLocation());

      return completedMono(results(
        "found", true,
        "blockId", blockId,
        "position", new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ()),
        "distance", distance
      ));
    }

    return completedMono(results(
      "found", false,
      "blockId", "minecraft:air",
      "position", Vec3.ZERO,
      "distance", 0.0
    ));
  }
}
