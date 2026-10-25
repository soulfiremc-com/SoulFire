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

import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BreedTask;
import com.soulfiremc.grpc.generated.BuildBlock;
import com.soulfiremc.grpc.generated.BuildTask;
import com.soulfiremc.grpc.generated.FarmTask;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlState;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.test.utils.TestBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSelectionOwnershipTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  static Stream<Arguments> providersAndLifecycleChange() {
    return Arrays.stream(Provider.values()).flatMap(provider ->
      Stream.of(false, true).map(changed -> Arguments.of(provider, changed)));
  }

  @ParameterizedTest
  @MethodSource("providersAndLifecycleChange")
  void inactiveCleanupPreservesTheCurrentOwnersSelectionAndInput(Provider provider, boolean suspended) {
    var fixture = new Fixture(provider);
    if (suspended) {
      fixture.execution.control().onStarted();
      fixture.execution.control().onSuspended();
    }
    fixture.state.up(true);
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.inventory, never()).setSelectedSlot(anyInt());
    assertTrue(fixture.state.up());
    assertTrue(fixture.execution.result().isCancelled());
  }

  @ParameterizedTest
  @MethodSource("providersAndLifecycleChange")
  void activeAndResumedCleanupRestoresTheOriginalSelection(Provider provider, boolean resumed) {
    var fixture = new Fixture(provider);
    fixture.execution.control().onStarted();
    if (resumed) {
      fixture.execution.control().onSuspended();
      fixture.execution.control().onResumed();
    }
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.inventory).setSelectedSlot(0);
    assertTrue(fixture.execution.result().isCancelled());
  }

  @ParameterizedTest
  @EnumSource(Provider.class)
  void queuedAdmissionCapturesThePrecedingOwnersFinalSelection(Provider provider) {
    var fixture = new Fixture(provider);
    when(fixture.inventory.getSelectedSlot()).thenReturn(3);
    fixture.execution.control().onStarted();
    fixture.execution.control().onStopped(ControlStopReason.CANCELLED, null);
    verify(fixture.inventory).setSelectedSlot(3);
  }

  private enum Provider {
    FARM, BUILD, BREED;

    BotTaskExecution start(BotTaskContext context) {
      return switch (this) {
        case FARM -> new FarmTaskProvider().start(context,
          FarmTask.newBuilder().setRestoreSelectedSlot(true).build());
        case BREED -> new BreedTaskProvider().start(context,
          BreedTask.newBuilder().setRestoreSelectedSlot(true).build());
        case BUILD -> new BuildTaskProvider().start(context,
          BuildTask.newBuilder().setOrigin(BlockPosition.getDefaultInstance())
            .addBlocks(BuildBlock.newBuilder().setBlockId("minecraft:stone"))
            .setRestoreSelectedSlot(true).build());
      };
    }
  }

  private static final class Fixture {
    final Inventory inventory = mock(Inventory.class);
    final ControlState state = new ControlState();
    final BotTaskExecution execution;

    Fixture(Provider provider) {
      var minecraft = mock(Minecraft.class);
      minecraft.player = mock(LocalPlayer.class);
      minecraft.level = mock(ClientLevel.class);
      when(minecraft.player.getInventory()).thenReturn(inventory);
      when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      when(bot.controlState()).thenReturn(state);
      var context = new BotTaskContext(null, bot, null, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), _ -> {});
      execution = provider.start(context);
    }
  }
}
