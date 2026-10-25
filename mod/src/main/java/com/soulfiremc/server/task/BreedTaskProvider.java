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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.Donkey;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.entity.animal.equine.Mule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
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
  private static final int OBSERVATION_TIMEOUT_TICKS = 100;
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
    return start(context, input, new RecoveryState());
  }

  @Override
  public ExecutionFactory prepare(BreedTask input) {
    var recovery = new RecoveryState();
    return context -> start(context, input, recovery);
  }

  private BotTaskExecution start(BotTaskContext context, BreedTask input, RecoveryState recovery) {
    var dimension = Objects.requireNonNull(context.bot().minecraft().level).dimension().identifier().toString();
    if (recovery.dimension != null && !recovery.dimension.equals(dimension)) {
      throw Status.FAILED_PRECONDITION.withDescription("The unfinished breeding task is in another dimension").asRuntimeException();
    }
    recovery.dimension = dimension;
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
    var result = new CompletableFuture<BreedTaskResult>();
    return new BotTaskExecution(
      new BreedControl(
        context,
        input,
        center,
        radius,
        rescanInterval,
        breedingTimeout,
        PathfindingSupport.buildConstraint(
          context.bot(),
          input.getOptions()
        ),
        result,
        recovery
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
    private final PathConstraint constraint;
    private final CompletableFuture<BreedTaskResult> result;
    private boolean active;
    private final RecoveryState recovery;
    private boolean recoveryPending;
    private boolean recoveredPendingLove;
    private int recoveryTicks;
    private @Nullable Animal pendingAnimal;
    private @Nullable PathExecutor path;

    private BreedControl(
      BotTaskContext context,
      BreedTask input,
      @Nullable BlockPos fixedCenter,
      int radius,
      int rescanIntervalTicks,
      int breedingTimeoutTicks,
      PathConstraint constraint,
      CompletableFuture<BreedTaskResult> result,
      RecoveryState recovery
    ) {
      this.context = context;
      this.input = input;
      this.fixedCenter = fixedCenter;
      this.radius = radius;
      this.rescanIntervalTicks = rescanIntervalTicks;
      this.breedingTimeoutTicks = breedingTimeoutTicks;
      this.constraint = constraint;
      this.result = result;
      this.recovery = recovery;
      this.recoveryPending = recovery.initialized;
      this.recoveredPendingLove = recoveryPending && recovery.stage == Stage.WAIT_FOR_LOVE;
      recovery.initialized = true;
    }

    @Override
    public void tick() {
      if (result.isDone()) {
        return;
      }
      try {
        if (
          input.getMaximumPairs() > 0
            && recovery.pairsStarted >= input.getMaximumPairs()
        ) {
          complete(
            BreedCompletionReason
              .BREED_COMPLETION_REASON_PAIR_LIMIT_REACHED
          );
          return;
        }
        if (recoveryPending) {
          if (!recoverObservation()) return;
          recoveryPending = false;
        }
        switch (recovery.stage) {
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

    private boolean recoverObservation() {
      var level = requireLevel();
      var visible = recovery.observed.stream().allMatch(uuid -> level.getEntity(uuid) instanceof Animal);
      if (!visible && ++recoveryTicks < OBSERVATION_TIMEOUT_TICKS) {
        report("Waiting to observe breeding animals after reconnecting");
        return false;
      }
      var pair = recovery.pair;
      if (pair != null && (!(level.getEntity(pair.first()) instanceof Animal)
        || !(level.getEntity(pair.second()) instanceof Animal))) {
        throw Status.NOT_FOUND.withDescription("The unfinished breeding pair is no longer observable").asRuntimeException();
      }
      return true;
    }

    private void scan() {
      var search = findPair();
      if (search.pair() != null) {
        recovery.pair = search.pair();
        recovery.targetIndex = 0;
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
      recovery.stageTicks++;
      if (recovery.stageTicks >= rescanIntervalTicks) {
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
        recovery.failedPairs++;
        recovery.pair = null;
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
        recovery.pair = null;
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
      pendingAnimal = animal;
      var loveState = (AnimalLoveState) animal;
      recovery.loveEventBeforeInteraction = loveState.soulfire$lastLoveEventTick();
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
      if (confirmPendingLove(animal)) {
        pendingAnimal = null;
        recoveredPendingLove = false;
        stopPath(ControlStopReason.CANCELLED, null);
        transition(recovery.stage, recovery.pair == null
          ? "Breeding pair started" : "Walking to second breeding animal");
        return;
      }
      recovery.stageTicks++;
      if (recovery.stageTicks >= breedingTimeoutTicks) {
        if (recoveredPendingLove) {
          throw Status.ABORTED.withDescription("The pending feeding could not be confirmed after reconnecting").asRuntimeException();
        }
        recovery.refused.add(animal.getUUID(), requireLevel().getGameTime());
        failPair("Server did not confirm breeding food");
      }
    }

    private boolean confirmPendingLove(Animal animal) {
      var event = ((AnimalLoveState) animal).soulfire$lastLoveEventTick();
      return event != Long.MIN_VALUE && event != recovery.loveEventBeforeInteraction
        && recovery.confirmLove(animal.getUUID(), event);
    }

    private PairSearch findPair() {
      var player = requirePlayer();
      var level = requireLevel();
      var gameTime = level.getGameTime();
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
        .sorted(searchOrder(recovery.refused, gameTime, player))
        .toList();
      recovery.observed = candidates.stream().map(Entity::getUUID).collect(Collectors.toUnmodifiableSet());
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
              new Pair(first.getUUID(), second.getUUID()),
              true
            );
          }
        }
      }
      return new PairSearch(null, compatiblePairExists);
    }

    /// On the client `AgeableMob.getAge()` is only -1 (baby) or 1 (adult):
    /// the real age, and with it the breeding cooldown, stays on the server.
    private boolean eligible(Animal animal) {
      return animal.isAlive()
        && !animal.isRemoved()
        && !animal.isBaby()
        && !(animal instanceof Mule)
        && !probablyInLove(animal);
    }

    private boolean probablyInLove(Animal animal) {
      if (recovery.loved.contains(animal.getUUID(), requireLevel().getGameTime())) return true;
      var lastEvent = ((AnimalLoveState) animal)
        .soulfire$lastLoveEventTick();
      if (lastEvent == Long.MIN_VALUE) {
        return false;
      }
      var age = requireLevel().getGameTime() - lastEvent;
      if (age >= 0 && age < LOVE_MODE_TICKS) {
        recovery.loved.add(animal.getUUID(), lastEvent);
        return true;
      }
      return false;
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
      var uuid = recovery.targetIndex == 0
        ? selectedPair.first()
        : selectedPair.second();
      return animal(uuid);
    }

    private Animal animal(UUID uuid) {
      var entity = requireLevel().getEntity(uuid);
      if (entity instanceof Animal animal) {
        return animal;
      }
      throw Status.NOT_FOUND
        .withDescription("Selected breeding animal is no longer observable")
        .asRuntimeException();
    }

    private Pair requirePair() {
      return Objects.requireNonNull(recovery.pair, "Breeding pair is not available");
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
      pendingAnimal = null;
      recoveredPendingLove = false;
      recovery.failedPairs++;
      recovery.pair = null;
      stopPath(ControlStopReason.CANCELLED, null);
      transition(Stage.WAIT_TO_RESCAN, message);
    }

    private void transition(Stage next, String message) {
      recovery.stage = next;
      recovery.stageTicks = 0;
      report(message);
    }

    private void report(String message) {
      var builder = BotTaskProgress.newBuilder()
        .setMessage(message)
        .setCurrent(recovery.pairsStarted);
      if (input.getMaximumPairs() > 0) {
        builder
          .setTotal(input.getMaximumPairs())
          .setFraction(Math.min(
            1.0,
            (double) recovery.pairsStarted / input.getMaximumPairs()
          ));
      }
      context.reportProgress(builder.build());
    }

    private void complete(BreedCompletionReason reason) {
      var player = context.bot().minecraft().player;
      var level = context.bot().minecraft().level;
      var builder = BreedTaskResult.newBuilder()
        .setReason(reason)
        .setPairsStarted(recovery.pairsStarted)
        .setAnimalsFed(recovery.animalsFed)
        .setFailedPairs(recovery.failedPairs);
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
    public void onStarted() {
      if (recovery.originalSelectedSlot.isEmpty()) {
        recovery.originalSelectedSlot = OptionalInt.of(requirePlayer().getInventory().getSelectedSlot());
      }
      active = true;
    }

    @Override
    public void onSuspended() {
      active = false;
      if (path != null) {
        path.onSuspended();
      }
    }

    @Override
    public void onResumed() {
      active = true;
      if (path != null) {
        path.onResumed();
      }
    }

    @Override
    public void onStopped(
      ControlStopReason reason,
      @Nullable Throwable cause
    ) {
      try {
        var pending = pendingAnimal;
        if (pending != null && recovery.stage == Stage.WAIT_FOR_LOVE) {
          confirmPendingLove(pending);
        }
        pendingAnimal = null;
        stopPath(reason, cause);
        if (active) {
          active = false;
          if (input.getRestoreSelectedSlot()) {
            var player = context.bot().minecraft().player;
            if (player != null) {
              recovery.originalSelectedSlot.ifPresent(player.getInventory()::setSelectedSlot);
            }
          }
        }
      } finally {
        if (reason != ControlStopReason.COMPLETED && !result.isDone()) {
          result.cancel(true);
        }
      }
    }

    @Override
    public String description() {
      return "Breed animals";
    }
  }

  /// The order the pair search tries animals in: the ones the server hasn't
  /// recovery.refused first, then the nearest to the player.
  static Comparator<Animal> searchOrder(
    RefusedAnimals refused,
    long gameTime,
    Entity player
  ) {
    return Comparator
      .comparing((Animal animal) -> refused.contains(animal.getUUID(), gameTime))
      .thenComparingDouble(animal -> animal.distanceToSqr(player));
  }

  /// Animals the server didn't put in love when fed, most likely because they
  /// bred recently. Each is tried last for a breeding cooldown after it was
  /// refused.
  static final class RefusedAnimals {
    private final Map<UUID, Long> refusedUntil = new HashMap<>();

    void add(UUID animal, long gameTime) {
      refusedUntil.values().removeIf(until -> until <= gameTime);
      refusedUntil.put(animal, gameTime + Animal.PARENT_AGE_AFTER_BREEDING);
    }

    boolean contains(UUID animal, long gameTime) {
      var until = refusedUntil.get(animal);
      return until != null && gameTime < until;
    }
  }

  /// Only identifiers, counters and game-time observations survive a connection.
  private static final class RecoveryState {
    private @Nullable String dimension;
    private boolean initialized;
    private OptionalInt originalSelectedSlot = OptionalInt.empty();
    private final RefusedAnimals refused = new RefusedAnimals();
    private final LovedAnimals loved = new LovedAnimals();
    private Set<UUID> observed = Set.of();
    private @Nullable Pair pair;
    private Stage stage = Stage.SCAN;
    private int targetIndex;
    private int stageTicks;
    private long loveEventBeforeInteraction;
    private int pairsStarted;
    private int animalsFed;
    private int failedPairs;

    boolean confirmLove(UUID animal, long eventTick) {
      var selected = pair;
      if (stage != Stage.WAIT_FOR_LOVE || selected == null
        || !animal.equals(targetIndex == 0 ? selected.first() : selected.second())) return false;
      loved.add(animal, eventTick);
      animalsFed++;
      stageTicks = 0;
      if (targetIndex == 0) {
        targetIndex = 1;
        stage = Stage.NAVIGATE;
      } else {
        pairsStarted++;
        pair = null;
        stage = Stage.SCAN;
      }
      return true;
    }
  }

  static final class LovedAnimals {
    private final Map<UUID, Long> until = new HashMap<>();

    void add(UUID animal, long eventTick) {
      until.values().removeIf(expiry -> expiry <= eventTick);
      until.put(animal, eventTick + LOVE_MODE_TICKS);
    }

    boolean contains(UUID animal, long gameTime) {
      var expiry = until.get(animal);
      return expiry != null && gameTime < expiry;
    }
  }

  private record Pair(UUID first, UUID second) {
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
