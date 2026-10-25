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
package com.soulfiremc.server.pathfinding;

import com.soulfiremc.grpc.generated.PathfindGoal;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityGoalLifecycleTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void neverUsesAnInitialPositionAfterTheEntityDisappears(boolean away) {
    var bot = mock(BotConnection.class);
    var minecraft = mock(Minecraft.class);
    minecraft.level = mock(ClientLevel.class);
    when(bot.minecraft()).thenReturn(minecraft);
    when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
    var entity = mock(Entity.class);
    when(entity.getId()).thenReturn(7);
    when(entity.position()).thenReturn(new Vec3(4, 64, 0));
    var entities = new ArrayList<Entity>();
    entities.add(entity);
    when(minecraft.level.entitiesForRendering()).thenReturn(entities);
    var goal = PathfindGoal.newBuilder();
    if (away) {
      goal.setAwayFromEntity(PathfindGoal.AwayFromEntityGoal.newBuilder().setEntityId(7).setRadius(5));
    } else {
      goal.setEntity(PathfindGoal.EntityGoal.newBuilder().setEntityId(7).setRadius(1));
    }
    var resolved = PathfindingSupport.resolveGoal(bot, goal.build());
    assertEquals(entity.position(), resolved.position().apply(bot));
    entities.clear();
    var scorerFailure = assertThrows(StatusRuntimeException.class, () -> resolved.scorer().snapshot());
    assertEquals(Status.Code.NOT_FOUND, scorerFailure.getStatus().getCode());
    var positionFailure = assertThrows(StatusRuntimeException.class, () -> resolved.position().apply(bot));
    assertEquals(Status.Code.NOT_FOUND, positionFailure.getStatus().getCode());
  }
  @Test
  void resolvesAnAvailableCompositePositionWhenTheEntityDisappears() {
    var bot = mock(BotConnection.class);
    var minecraft = mock(Minecraft.class);
    minecraft.level = mock(ClientLevel.class);
    when(bot.minecraft()).thenReturn(minecraft);
    when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
    var entity = mock(Entity.class);
    when(entity.getId()).thenReturn(7);
    when(entity.position()).thenReturn(new Vec3(4, 64, 0));
    var entities = new ArrayList<Entity>();
    entities.add(entity);
    when(minecraft.level.entitiesForRendering()).thenReturn(entities);
    var fallback = PathfindGoal.newBuilder().setBlock(PathfindGoal.BlockGoal.newBuilder()
      .setPosition(com.soulfiremc.grpc.generated.BlockPosition.newBuilder().setX(8).setY(64))).build();
    var composite = PathfindingSupport.resolveGoal(bot, PathfindGoal.newBuilder()
      .setAny(PathfindGoal.AnyGoal.newBuilder()
        .addGoals(PathfindGoal.newBuilder().setEntity(PathfindGoal.EntityGoal.newBuilder().setEntityId(7)))
        .addGoals(fallback)).build());
    entities.clear();
    assertEquals(PathfindingSupport.resolveGoal(bot, fallback).position().apply(bot),
      composite.position().apply(bot));
  }

}
