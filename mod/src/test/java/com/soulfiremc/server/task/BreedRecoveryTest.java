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
import com.soulfiremc.grpc.generated.BotTaskProgress;
import com.soulfiremc.grpc.generated.BreedTask;
import com.soulfiremc.grpc.generated.BreedTaskResult;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.ControlState;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.settings.lib.BotSettingsImpl;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

final class BreedRecoveryTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void keepsConfirmedPairsAndLoveCooldownsAcrossFreshEntityViews() throws Exception {
    var fixture = new Fixture();
    try (var food = fixture.food()) {
      var factory = fixture.factory(2);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.progress.getCurrent() == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var recovered = fixture.start(factory);
      fixture.until(recovered, () -> recovered.result().isDone());
      var result = (BreedTaskResult) recovered.result().join();
      assertEquals(2, result.getPairsStarted());
      assertEquals(4, result.getAnimalsFed());
      assertEquals(0, result.getFailedPairs());
      assertEquals(4, fixture.serverFed);
    }
  }

  @Test
  void resumesTheSecondAnimalByUuidAfterTheFirstWasConfirmed() throws Exception {
    var fixture = new Fixture();
    try (var food = fixture.food()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.serverFed == 1);
      fixture.tick(first); // Acknowledge the delivered heart before disconnecting.
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var recovered = fixture.start(factory);
      fixture.until(recovered, () -> recovered.result().isDone());
      var result = (BreedTaskResult) recovered.result().join();
      assertEquals(1, result.getPairsStarted());
      assertEquals(2, result.getAnimalsFed());
      assertEquals(2, fixture.serverFed);
    }
  }

  @Test
  void capturesAHeartDeliveredBeforeTheFinalControlTick() throws Exception {
    var fixture = new Fixture();
    try (var food = fixture.food()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.serverFed == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var recovered = fixture.start(factory);
      fixture.until(recovered, () -> recovered.result().isDone());
      var result = (BreedTaskResult) recovered.result().join();
      assertEquals(1, result.getPairsStarted());
      assertEquals(2, result.getAnimalsFed());
      assertEquals(2, fixture.serverFed);
    }
  }

  @Test
  void waitsForTheSelectedAnimalsToArriveOnTheNewConnection() throws Exception {
    var fixture = new Fixture();
    try (var food = fixture.food()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.serverFed == 1);
      fixture.tick(first);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.visible = false;
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 5; tick++) fixture.tick(recovered);
      assertFalse(recovered.result().isDone());
      assertEquals(1, fixture.serverFed);
      fixture.visible = true;
      fixture.until(recovered, () -> recovered.result().isDone());
      assertEquals(2, ((BreedTaskResult) recovered.result().join()).getAnimalsFed());
      assertEquals(2, fixture.serverFed);
    }
  }

  @Test
  void doesNotGuessWhetherAnUnacknowledgedFeedingWasAccepted() throws Exception {
    var fixture = new Fixture();
    fixture.deliverHearts = false;
    try (var food = fixture.food()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.serverFed == 1);
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      var recovered = fixture.start(factory);
      for (var tick = 0; tick < 200 && !recovered.result().isDone(); tick++) fixture.tick(recovered);
      assertTrue(recovered.result().isDone());
      var failure = assertThrows(CompletionException.class, () -> recovered.result().join());
      assertEquals(Status.Code.ABORTED, ((StatusRuntimeException) failure.getCause()).getStatus().getCode());
      assertEquals(1, fixture.serverFed);
    }
  }

  @Test
  void restoresTheSelectionCapturedOnInitialAdmission() throws Exception {
    var fixture = new Fixture();
    try (var food = fixture.food()) {
      var factory = fixture.factory(1);
      var first = fixture.start(factory);
      fixture.until(first, () -> fixture.serverFed == 1);
      fixture.tick(first);
      first.control().onSuspended();
      first.control().onStopped(ControlStopReason.CANCELLED, null);
      fixture.selection = 2;
      var recovered = fixture.start(factory);
      fixture.until(recovered, () -> recovered.result().isDone());
      recovered.control().onStopped(ControlStopReason.COMPLETED, null);
      verify(fixture.inventory).setSelectedSlot(0);
    }
  }

  private static final class Fixture {
    final List<UUID> ids = new ArrayList<>();
    final Map<UUID, Long> loveUntil = new HashMap<>();
    final AtomicLong time = new AtomicLong(1_000);
    BotTaskProgress progress = BotTaskProgress.getDefaultInstance();
    Inventory inventory;
    int serverFed;
    int frame;
    int selection;
    boolean visible = true;
    boolean deliverHearts = true;

    Fixture() {
      for (var index = 0; index < 6; index++) ids.add(UUID.randomUUID());
    }

    BotTaskProvider.ExecutionFactory factory(int pairs) {
      return new BreedTaskProvider().prepare(BreedTask.newBuilder()
        .setCenter(BlockPosition.newBuilder().setY(64)).setRadius(8)
        .setMaximumPairs(pairs).setBreedingTimeoutTicks(2)
        .setCompleteWhenNoPair(true).setRestoreSelectedSlot(true).build());
    }

    MockedStatic<TaskInventorySupport> food() {
      var food = mockStatic(TaskInventorySupport.class);
      food.when(() -> TaskInventorySupport.findInventorySlot(any(), any())).thenReturn(OptionalInt.of(36));
      food.when(() -> TaskInventorySupport.ensureHolding(any(), any())).thenReturn(true);
      return food;
    }

    BotTaskExecution start(BotTaskProvider.ExecutionFactory factory) throws Exception {
      frame++;
      var entities = new HashMap<UUID, Animal>();
      var hearts = new HashMap<UUID, AtomicLong>();
      for (var index = 0; index < ids.size(); index++) {
        var uuid = ids.get(index);
        var heart = new AtomicLong(Long.MIN_VALUE);
        hearts.put(uuid, heart);
        var animal = mock(Animal.class, withSettings().extraInterfaces(AnimalLoveState.class));
        when(animal.getUUID()).thenReturn(uuid);
        when(animal.getId()).thenReturn(frame * 100 + index);
        when(animal.getType()).thenAnswer(_ -> EntityTypes.COW);
        when(animal.isAlive()).thenReturn(true);
        when(animal.position()).thenReturn(new Vec3(index * .5, 64, 0));
        when(animal.isFood(any())).thenReturn(true);
        when(((AnimalLoveState) animal).soulfire$lastLoveEventTick()).thenAnswer(_ -> heart.get());
        entities.put(uuid, animal);
      }
      var minecraft = mock(Minecraft.class);
      var level = mock(ClientLevel.class);
      var player = mock(LocalPlayer.class);
      var gameMode = mock(MultiPlayerGameMode.class);
      minecraft.level = level;
      minecraft.player = player;
      minecraft.gameMode = gameMode;
      inventory = mock(Inventory.class);
      when(inventory.getSelectedSlot()).thenReturn(selection);
      when(player.getInventory()).thenReturn(inventory);
      when(player.getEyePosition()).thenReturn(new Vec3(0, 65.5, 0));
      when(player.position()).thenReturn(new Vec3(0, 64, 0));
      var held = mock(ItemStack.class);
      when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(held);
      when(level.dimension()).thenReturn(Level.OVERWORLD);
      when(level.getGameTime()).thenAnswer(_ -> time.get());
      when(level.entitiesForRendering()).thenAnswer(_ -> visible
        ? ids.stream().map(entities::get).map(Entity.class::cast).toList() : List.of());
      when(level.getEntity(any(UUID.class))).thenAnswer(call -> visible ? entities.get(call.getArgument(0)) : null);
      when(level.getEntity(anyInt())).thenAnswer(call -> visible
        ? entities.values().stream().filter(animal -> animal.getId() == (int) call.getArgument(0)).findFirst().orElse(null)
        : null);
      for (var index = 0; index < ids.size(); index++) {
        when(entities.get(ids.get(index)).distanceToSqr(player)).thenReturn(index * index * .25);
      }
      when(gameMode.interact(any(), any(), any(), any())).thenAnswer(call -> {
        var animal = (Animal) call.getArgument(1);
        var uuid = animal.getUUID();
        if (time.get() >= loveUntil.getOrDefault(uuid, 0L)) {
          serverFed++;
          loveUntil.put(uuid, time.get() + 600);
          if (deliverHearts) hearts.get(uuid).set(time.get());
        }
        return InteractionResult.SUCCESS;
      });
      var bot = mock(BotConnection.class);
      when(bot.minecraft()).thenReturn(minecraft);
      when(bot.settingsSource()).thenReturn(mock(BotSettingsImpl.class));
      when(bot.controlState()).thenReturn(new ControlState());
      var execution = factory.start(new BotTaskContext(null, bot, null, UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), Optional.empty(), value -> progress = value));
      execution.control().onStarted();
      return execution;
    }

    void tick(BotTaskExecution execution) {
      time.incrementAndGet();
      execution.control().tick();
    }

    void until(BotTaskExecution execution, BooleanSupplier condition) {
      for (var tick = 0; tick < 300 && !condition.getAsBoolean() && !execution.result().isDone(); tick++) tick(execution);
      if (execution.result().isCompletedExceptionally()) execution.result().join();
      assertTrue(condition.getAsBoolean());
    }
  }
}
