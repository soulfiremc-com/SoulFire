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

import com.soulfiremc.grpc.generated.AutoEatTask;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AutoEatTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void failsInsteadOfWaitingWhenThePlayerDies() {
    var bot = mock(BotConnection.class);
    var minecraft = mock(Minecraft.class);
    minecraft.player = mock(LocalPlayer.class);
    when(bot.minecraft()).thenReturn(minecraft);
    when(minecraft.player.isDeadOrDying()).thenReturn(true);
    var context = new BotTaskContext(null, bot, null, UUID.randomUUID(),
      UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), _ -> {});
    var execution = new AutoEatTaskProvider().start(context,
      AutoEatTask.newBuilder().setMaximumMeals(1).build());

    for (var tick = 0; tick < 5 && !execution.result().isDone(); tick++) {
      execution.control().tick();
    }

    assertTrue(execution.result().isDone());
    var failure = assertThrows(CompletionException.class, () -> execution.result().join());
    var status = assertInstanceOf(StatusRuntimeException.class, failure.getCause());
    assertEquals(Status.Code.FAILED_PRECONDITION, status.getStatus().getCode());
  }

  @Test
  void permitsExplicitlyRequestedFoodWithNegativeEffects() {
    assertTrue(AutoEatTaskProvider.isPermittedFood(
      true,
      false,
      "minecraft:chicken",
      Set.of("minecraft:chicken")
    ));
    assertFalse(AutoEatTaskProvider.isPermittedFood(
      true,
      false,
      "minecraft:chicken",
      Set.of("minecraft:bread")
    ));
  }

  @Test
  void keepsUnsafeFoodOutOfAutomaticSelection() {
    assertFalse(AutoEatTaskProvider.isPermittedFood(
      true,
      false,
      "minecraft:chicken",
      Set.of()
    ));
    assertTrue(AutoEatTaskProvider.isPermittedFood(
      true,
      true,
      "minecraft:bread",
      Set.of()
    ));
  }
}
