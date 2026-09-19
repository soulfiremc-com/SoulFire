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
import com.soulfiremc.grpc.generated.BreedCompletionReason;
import com.soulfiremc.grpc.generated.BreedTask;
import com.soulfiremc.grpc.generated.BreedTaskResult;
import com.soulfiremc.grpc.generated.PathfindGoal;
import com.soulfiremc.grpc.generated.WorldPosition;
import com.soulfiremc.server.api.BotTaskExecution;
import com.soulfiremc.server.api.BotTaskProvider;
import com.soulfiremc.server.bot.ControlPriority;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.grpc.InventoryServiceImpl;
import com.soulfiremc.server.grpc.WorldServiceImpl;
import com.soulfiremc.server.pathfinding.PathfindingSupport;
import com.soulfiremc.server.pathfinding.execution.PathExecutor;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import io.grpc.Status;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Mule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.StreamSupport;

/// Selects compatible adult animals, feeds both, and confirms each server
/// love-mode event before counting a pair.
public final class BreedTaskProvider implements BotTaskProvider<BreedTask> {
  private static final int DEFAULT_RADIUS = 24;
  private static final int MAX_RADIUS = 64;
  private static final int DEFAULT_RESCAN_INTERVAL_TICKS = 100;
  private static final int MAX_RESCAN_INTERVAL_TICKS = 72_000;
  private static final int DEFAULT_BREEDING_TIMEOUT_TICKS = 100;
  private static final int MAX_BREEDING_TIMEOUT_TICKS = 1_200;
  private static final int LOVE_MODE_TICKS = 600;
  private static final Set<ControlResource> RESOURCES = Set.of(
    ControlResource.MOVEMENT,
    ControlResource.ROTATION,
    ControlResource.MAIN_HAND,
    ControlResource.INVENTORY
  );

  @Override
  public BreedTask inputPrototype() {
    return BreedTask.getDefaultInstance();
  }

  @Override
  public String summary(BreedTask input) {
    return input.getMaximumPairs() == 0
      ? "Breed compatible animals until cancelled"
      : "Start breeding for up to " + input.getMaximumPairs() + " pairs";
  }

  @Override
  public Set<ControlResource> resources(BreedTask input) {
    return RESOURCES;
  }

  @Override
  public BotTaskExecution start(BotTaskContext context, BreedTask input) {
    var radius = input.getRadius() == 0
      ? DEFAULT_RADIUS
      : requireRange(input.getRadius(), 1, MAX_RADIUS, "radius");
    var rescanInterval = input.getRescanIntervalTicks() == 0
      ? DEFAULT_RESCAN_INTERVAL_TICKS
      : requireRange(
        input.getRescanIntervalTicks(),
        1,
        MAX_RESCAN_INTERVAL_TICKS,
        "rescan_interval_ticks"
      );
    var breedingTimeout = input.getBreedingTimeoutTicks() == 0
      ? DEFAULT_BREEDING_TIMEOUT_TICKS
      : requireRange(
        input.getBreedingTimeoutTicks(),
        1,
        MAX_BREEDING_TIMEOUT_TICKS,
        "breeding_timeout_ticks"
      );
    BlockPos center = null;
    if (input.hasCenter()) {
      validateDimension(context, input.getCenter());
      center = toBlockPos(input.getCenter());
    }
    var player = Objects.requireNonNull(
      context.bot().minecraft().player,
      "Bot player is not available"
    );
    var result = new CompletableFuture<BreedTaskResult>();
    return new BotTaskExecution(
      new BreedControl(
        context,
        input,
        center,
        radius,
        rescanInterval,
        breedingTimeout,
        player.getInventory().getSelectedSlot(),
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result
      ),
      result
    );
  }

  private static int requireRange(
    int value,
    int minimum,
    int maximum,
    String name
  ) {
    if (value < minimum || value > maximum) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "%s must be between %d and %d"
            .formatted(name, minimum, maximum)
        )
        .asRuntimeException();
    }
    return value;
  }

  private static void validateDimension(
    BotTaskContext context,
    BlockPosition position
  ) {
    var level = Objects.requireNonNull(context.bot().minecraft().level);
    var actual = level.dimension().identifier().toString();
    if (
      !position.getDimension().isBlank()
        && !position.getDimension().equals(actual)
    ) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Breeding center is in '%s', but the bot is in '%s'"
            .formatted(position.getDimension(), actual)
        )
        .asRuntimeException();
    }
  }

  private static BlockPos toBlockPos(BlockPosition position) {
    return new BlockPos(
      position.getX(),
      position.getY(),
      position.getZ()
    );
  }

  private static final class BreedControl implements ControlTask {
    private final BotTaskContext context;
    private final BreedTask input;
    private final @Nullable BlockPos fixedCenter;
    private final int radius;
    private final int rescanIntervalTicks;
    private final int breedingTimeoutTicks;
    private final int originalSelectedSlot;
    private final PathConstraint constraint;
    private final CompletableFuture<BreedTaskResult> result;
    private @Nullable Pair pair;
    private @Nullable PathExecutor path;
    private Stage stage = Stage.SCAN;
    private int targetIndex;
    private int stageTicks;
    private long loveEventBeforeInteraction;
    private int pairsStarted;
    private int animalsFed;
    private int failedPairs;

    private BreedControl(
      BotTaskContext context,
      BreedTask input,
      @Nullable BlockPos fixedCenter,
      int radius,
      int rescanIntervalTicks,
      int breedingTimeoutTicks,
      int originalSelectedSlot,
      PathConstraint constraint,
      CompletableFuture<BreedTaskResult> result
    ) {
      this.context = context;
      this.input = input;
      this.fixedCenter = fixedCenter;
      this.radius = radius;
      this.rescanIntervalTicks = rescanIntervalTicks;
      this.breedingTimeoutTicks = breedingTimeoutTicks;
      this.originalSelectedSlot = originalSelectedSlot;
      this.constraint = constraint;
      this.result = result;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (
          input.getMaximumPairs() > 0
            && pairsStarted >= input.getMaximumPairs()
        ) {
          complete(
            BreedCompletionReason
              .BREED_COMPLETION_REASON_PAIR_LIMIT_REACHED
          );
          return;
        }
        switch (stage) {
          case SCAN -> scan();
          case WAIT_TO_RESCAN -> waitToRescan();
          case NAVIGATE -> navigate();
          case FEED -> feed();
          case WAIT_FOR_LOVE -> waitForLove();
        }
      } catch (Throwable throwable) {
        result.completeExceptionally(throwable);
      }
    }

    private void scan() {
      var search = findPair();
      if (search.pair() != null) {
        pair = search.pair();
        targetIndex = 0;
        transition(Stage.NAVIGATE, "Walking to first breeding animal");
        return;
      }
      if (search.compatiblePairExists()) {
        if (input.getCompleteWhenNoFood()) {
          complete(
            BreedCompletionReason.BREED_COMPLETION_REASON_NO_FOOD
          );
          return;
        }
        transition(
          Stage.WAIT_TO_RESCAN,
          "Waiting for shared breeding food"
        );
        return;
      }
      if (input.getCompleteWhenNoPair()) {
        complete(
          BreedCompletionReason
            .BREED_COMPLETION_REASON_NO_COMPATIBLE_PAIR
        );
        return;
      }
      transition(
        Stage.WAIT_TO_RESCAN,
        "Waiting for a compatible adult animal pair"
      );
    }

    private void waitToRescan() {
      stageTicks++;
      if (stageTicks >= rescanIntervalTicks) {
        transition(Stage.SCAN, "Scanning for a breeding pair");
      }
    }

    private void navigate() {
      var animal = currentAnimal();
      if (!eligible(animal)) {
        failPair("Selected animal is no longer eligible");
        return;
      }
      var player = requirePlayer();
      if (animal.distanceToSqr(player) <= 25) {
        stopPath(ControlStopReason.CANCELLED, null);
        transition(Stage.FEED, "Feeding breeding animal");
        return;
      }
      if (path == null) {
        path = newPath(animal);
        path.onStarted();
      }
      if (!path.isDone()) {
        path.tick();
        report(path.progress().planning()
          ? "Planning route to breeding animal"
          : "Walking to breeding animal");
        return;
      }
      var completed = path;
      path = null;
      try {
        completed.completion().join();
        completed.onStopped(ControlStopReason.COMPLETED, null);
      } catch (CompletionException exception) {
        var cause = Objects.requireNonNullElse(
          exception.getCause(),
          exception
        );
        completed.onStopped(ControlStopReason.FAILED, cause);
        failedPairs++;
        pair = null;
        transition(
          Stage.WAIT_TO_RESCAN,
          "Unable to reach breeding animal"
        );
      }
    }

    private PathExecutor newPath(Animal animal) {
      var goal = PathfindGoal.newBuilder()
        .setEntity(PathfindGoal.EntityGoal.newBuilder()
          .setEntityId(animal.getId())
          .setConnectionEpoch(
            context.bot().connectionEpoch().toString()
          )
          .setRadius(3))
        .build();
      return PathExecutor.createPathfinding(
        context.bot(),
        PathfindingSupport.resolveGoal(context.bot(), goal).scorer(),
        constraint
      );
    }

    private void feed() {
      var animal = currentAnimal();
      var currentPair = requirePair();
      var first = animal(currentPair.first());
      var second = animal(currentPair.second());
      if (!eligible(animal)) {
        failPair("Selected animal is no longer eligible");
        return;
      }
      if (
        !TaskInventorySupport.ensureHolding(
          context.bot(),
          stack -> sharedFood(
            first,
            second,
            stack
          )
        )
      ) {
        pair = null;
        if (input.getCompleteWhenNoFood()) {
          complete(
            BreedCompletionReason.BREED_COMPLETION_REASON_NO_FOOD
          );
        } else {
          transition(
            Stage.WAIT_TO_RESCAN,
            "Waiting for shared breeding food"
          );
        }
        return;
      }
      var loveState = (AnimalLoveState) animal;
      loveEventBeforeInteraction = loveState.soulfire$lastLoveEventTick();
      var swingAnimation = requirePlayer().getItemInHand(InteractionHand.MAIN_HAND).getInteractAnimation();
      var interaction = requireGameMode().interact(
        requirePlayer(),
        animal,
        new EntityHitResult(animal),
        InteractionHand.MAIN_HAND
      );
      if (!(interaction instanceof InteractionResult.Success success)) {
        throw Status.FAILED_PRECONDITION
          .withDescription("The selected animal rejected its breeding food")
          .asRuntimeException();
      }
      if (
        success.swingSource() == InteractionResult.SwingSource.PREDICTED
      ) {
        requirePlayer().swing(InteractionHand.MAIN_HAND, swingAnimation, false);
      }
      transition(
        Stage.WAIT_FOR_LOVE,
        "Waiting for server breeding confirmation"
      );
    }

    private void waitForLove() {
      var animal = currentAnimal();
      if (!animal.isAlive() || animal.isRemoved()) {
        failPair("Selected animal became unavailable");
        return;
      }
      var loveEvent = ((AnimalLoveState) animal)
        .soulfire$lastLoveEventTick();
      if (
        loveEvent != Long.MIN_VALUE
          && loveEvent != loveEventBeforeInteraction
      ) {
        animalsFed++;
        if (targetIndex == 0) {
          targetIndex = 1;
          stopPath(ControlStopReason.CANCELLED, null);
          transition(
            Stage.NAVIGATE,
            "Walking to second breeding animal"
          );
          return;
        }
        pairsStarted++;
        pair = null;
        transition(Stage.SCAN, "Breeding pair started");
        return;
      }
      stageTicks++;
      if (stageTicks >= breedingTimeoutTicks) {
        failPair("Server did not confirm breeding food");
      }
    }

    private PairSearch findPair() {
      var player = requirePlayer();
      var level = requireLevel();
      var origin = fixedCenter == null
        ? player.position()
        : Vec3.atCenterOf(fixedCenter);
      var candidates = StreamSupport.stream(
          level.entitiesForRendering().spliterator(),
          false
        )
        .filter(Animal.class::isInstance)
        .map(Animal.class::cast)
        .filter(this::eligible)
        .filter(animal -> animal.position().distanceToSqr(origin)
          <= (double) radius * radius)
        .filter(animal -> WorldServiceImpl.matchesEntity(
          context.bot(),
          animal,
          input.getAnimals(),
          player.getEyePosition()
        ))
        .sorted(Comparator.comparingDouble(
          animal -> animal.distanceToSqr(player)
        ))
        .toList();
      var compatiblePairExists = false;
      for (var firstIndex = 0; firstIndex < candidates.size(); firstIndex++) {
        var first = candidates.get(firstIndex);
        for (
          var secondIndex = firstIndex + 1;
          secondIndex < candidates.size();
          secondIndex++
        ) {
          var second = candidates.get(secondIndex);
          if (!compatible(first, second)) {
            continue;
          }
          compatiblePairExists = true;
          if (hasSharedFood(first, second)) {
            return new PairSearch(
              new Pair(first.getId(), second.getId()),
              true
            );
          }
        }
      }
      return new PairSearch(null, compatiblePairExists);
    }

    private boolean eligible(Animal animal) {
      return animal.isAlive()
        && !animal.isRemoved()
        && animal.getAge() == 0
        && !(animal instanceof Mule)
        && !probablyInLove(animal);
    }

    private boolean probablyInLove(Animal animal) {
      var lastEvent = ((AnimalLoveState) animal)
        .soulfire$lastLoveEventTick();
      if (lastEvent == Long.MIN_VALUE) {
        return false;
      }
      var age = requireLevel().getGameTime() - lastEvent;
      return age >= 0 && age < LOVE_MODE_TICKS;
    }

    private boolean hasSharedFood(Animal first, Animal second) {
      return TaskInventorySupport.findInventorySlot(
        context.bot(),
        stack -> sharedFood(first, second, stack)
      ).isPresent();
    }

    private boolean sharedFood(
      Animal first,
      Animal second,
      ItemStack stack
    ) {
      return !stack.isEmpty()
        && first.isFood(stack)
        && second.isFood(stack)
        && (!input.hasFood()
        || InventoryServiceImpl.matches(stack, input.getFood()));
    }

    private static boolean compatible(Animal first, Animal second) {
      if (first.getClass() == second.getClass()) {
        return true;
      }
      return first instanceof Horse && second instanceof Donkey
        || first instanceof Donkey && second instanceof Horse;
    }

    private Animal currentAnimal() {
      var selectedPair = requirePair();
      var entityId = targetIndex == 0
        ? selectedPair.first()
        : selectedPair.second();
      return animal(entityId);
    }

    private Animal animal(int entityId) {
      var entity = requireLevel().getEntity(entityId);
      if (entity instanceof Animal animal) {
        return animal;
      }
      throw Status.NOT_FOUND
        .withDescription("Selected breeding animal is no longer observable")
        .asRuntimeException();
    }

    private Pair requirePair() {
      return Objects.requireNonNull(pair, "Breeding pair is not available");
    }

    private net.minecraft.client.player.LocalPlayer requirePlayer() {
      return Objects.requireNonNull(
        context.bot().minecraft().player,
        "Bot player is not available"
      );
    }

    private net.minecraft.client.multiplayer.ClientLevel requireLevel() {
      return Objects.requireNonNull(
        context.bot().minecraft().level,
        "Bot level is not available"
      );
    }

    private net.minecraft.client.multiplayer.MultiPlayerGameMode
    requireGameMode() {
      return Objects.requireNonNull(
        context.bot().minecraft().gameMode,
        "Bot game mode is not available"
      );
    }

    private void failPair(String message) {
      failedPairs++;
      pair = null;
      stopPath(ControlStopReason.CANCELLED, null);
      transition(Stage.WAIT_TO_RESCAN, message);
    }

    private void transition(Stage next, String message) {
      stage = next;
      stageTicks = 0;
      report(message);
    }

    private void report(String message) {
      var builder = BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(pairsStarted);
      if (input.getMaximumPairs() > 0) {
        builder
          .setTotal(input.getMaximumPairs())
          .setFraction(Math.min(
            1.0,
            (double) pairsStarted / input.getMaximumPairs()
          ));
      }
      context.reportProgress(builder.build());
    }

    private void complete(BreedCompletionReason reason) {
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      var builder = BreedTaskResult.newBuilder()
        .setReason(reason)
        .setPairsStarted(pairsStarted)
        .setAnimalsFed(animalsFed)
        .setFailedPairs(failedPairs);
      if (player != null && level != null) {
        builder.setFinalPosition(WorldPosition.newBuilder()
          .setX(player.getX())
          .setY(player.getY())
          .setZ(player.getZ())
          .setDimension(level.dimension().identifier().toString()));
      }
      result.complete(builder.build());
    }

    private void stopPath(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      var activePath = path;
      path = null;
      if (activePath != null) {
        activePath.onStopped(reason, cause);
      }
    }

    @Override
    public boolean isDone() {
      return result.isDone();
    }

    @Override
    public ControlPriority priority() {
      return ControlPriority.HIGH;
    }

    @Override
    public Set<ControlResource> resources() {
      return RESOURCES;
    }

    @Override
    public void onSuspended() {
      if (path != null) {
        path.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      if (path != null) {
        path.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      stopPath(reason, cause);
      if (input.getRestoreSelectedSlot()) {
        var player = context.bot().minecraft().player;
        if (player != null) {
          player.getInventory().setSelectedSlot(originalSelectedSlot);
        }
      }
      if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
        result.cancel(true);
      }
    }

    @Override
    public String description() {
      return "Breed animals";
    }
  }

  private record Pair(int first, int second) {
  }

  private record PairSearch(
    @Nullable Pair pair,
    boolean compatiblePairExists
  ) {
  }

  private enum Stage {
    SCAN,
    WAIT_TO_RESCAN,
    NAVIGATE,
    FEED,
    WAIT_FOR_LOVE
  }
}
