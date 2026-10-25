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
package com.soulfiremc.server.task;

import com.soulfiremc.grpc.generated.FollowEntityCompletionReason;
import com.soulfiremc.grpc.generated.FollowEntityTask;
import com.soulfiremc.grpc.generated.FollowEntityTaskResult;
import com.soulfiremc.grpc.generated.PathfindGoal;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlState;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FollowEntityTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void waitsForObservationAndResetsTheUnavailableBudgetWhenTheTargetReturns() {
    var bot = mock(BotConnection.class);
    var minecraft = mock(Minecraft.class);
    minecraft.player = mock(LocalPlayer.class);
    minecraft.level = mock(ClientLevel.class);
    when(bot.minecraft()).thenReturn(minecraft);
    when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
    when(bot.controlState()).thenReturn(new ControlState());
    when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
    when(minecraft.player.position()).thenReturn(Vec3.ZERO);
    var target = mock(Entity.class);
    when(target.position()).thenReturn(Vec3.ZERO);
    var context = new BotTaskContext(null, bot, null, UUID.randomUUID(),
      UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), _ -> {});
    var execution = new FollowEntityTaskProvider().start(context,
      FollowEntityTask.newBuilder()
        .setTarget(PathfindGoal.EntityGoal.newBuilder().setEntityId(42).setRadius(2))
        .setTargetUnavailableTimeoutSeconds(1)
        .build());

    for (var tick = 0; tick < 19; tick++) execution.control().tick();
    assertFalse(execution.result().isDone());
    when(minecraft.level.getEntity(42)).thenReturn(target);
    execution.control().tick();
    assertFalse(execution.result().isDone());
    when(minecraft.level.getEntity(42)).thenReturn(null);
    for (var tick = 0; tick < 19; tick++) execution.control().tick();
    assertFalse(execution.result().isDone());
    execution.control().tick();
    var result = (FollowEntityTaskResult) execution.result().join();
    assertEquals(FollowEntityCompletionReason.FOLLOW_ENTITY_COMPLETION_REASON_TARGET_UNAVAILABLE,
      result.getReason());
    assertEquals(0, result.getFinalPosition().getX());
  }

  @Test
  void rejectsReferencesFromAnEarlierConnectionBeforeWaitingForObservation() {
    var bot = mock(BotConnection.class);
    when(bot.connectionEpoch()).thenReturn(UUID.randomUUID());
    var context = new BotTaskContext(null, bot, null, UUID.randomUUID(),
      UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), _ -> {});
    var failure = assertThrows(StatusRuntimeException.class, () ->
      new FollowEntityTaskProvider().start(context, FollowEntityTask.newBuilder()
        .setTarget(PathfindGoal.EntityGoal.newBuilder().setEntityId(42)
          .setConnectionEpoch(UUID.randomUUID().toString()))
        .build()));
    assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode());
  }
}
