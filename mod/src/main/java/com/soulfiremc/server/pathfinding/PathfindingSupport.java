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
import com.soulfiremc.grpc.generated.PathfindOptions;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.pathfinding.goals.AwayFromPosGoal;
import com.soulfiremc.server.pathfinding.goals.BreakBlockPosGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToPosGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToWorldPosGoal;
import com.soulfiremc.server.pathfinding.goals.CloseToWorldXZGoal;
import com.soulfiremc.server.pathfinding.goals.CompositeGoal;
import com.soulfiremc.server.pathfinding.goals.DynamicGoalScorer;
import com.soulfiremc.server.pathfinding.goals.GoalScorer;
import com.soulfiremc.server.pathfinding.goals.PlaceBlockGoal;
import com.soulfiremc.server.pathfinding.goals.PosGoal;
import com.soulfiremc.server.pathfinding.goals.XZGoal;
import com.soulfiremc.server.pathfinding.goals.YGoal;
import com.soulfiremc.server.pathfinding.graph.constraint.AdditionalPlacementConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.AvoidFluidConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.BlockBreakBlacklistConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.ConfiguredPathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockBreakingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.NoBlockPlacingConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraint;
import com.soulfiremc.server.pathfinding.graph.constraint.PathConstraintImpl;
import com.soulfiremc.server.pathfinding.graph.constraint.PathYRangeConstraint;
import com.soulfiremc.server.util.BlockItems;
import com.soulfiremc.server.util.SFItemHelpers;
import io.grpc.Status;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashSet;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/// Shared validation and resolution for pathfinding RPCs and durable tasks.
public final class PathfindingSupport {
  private static final int MAX_COMPOSITE_DEPTH = 16;
  private static final int MAX_COMPOSITE_GOALS = 64;

  private PathfindingSupport() {
  }

  public static ResolvedGoal resolveGoal(BotConnection bot, PathfindGoal goal) {
    return resolveGoal(bot, goal, 0);
  }

  private static ResolvedGoal resolveGoal(
    BotConnection bot,
    PathfindGoal goal,
    int depth
  ) {
    if (depth > MAX_COMPOSITE_DEPTH) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Pathfinding goals may be nested at most 16 levels")
        .asRuntimeException();
    }
    var currentDimension = currentDimension(bot);
    return switch (goal.getGoalCase()) {
      case BLOCK -> {
        var block = goal.getBlock();
        validateDimension(currentDimension, block.getPosition().getDimension());
        var position = block.getPosition();
        var vector = SFVec3i.from(position.getX(), position.getY(), position.getZ());
        var radius = Math.max(1, Math.round(block.getRadius()));
        var scorer = block.getRadius() <= 0
          ? (GoalScorer) new PosGoal(vector)
          : new CloseToPosGoal(vector, radius);
        yield new ResolvedGoal(
          scorer,
          _ -> Vec3.atCenterOf(vector.toBlockPos())
        );
      }
      case NEAR -> {
        var near = goal.getNear();
        validateDimension(currentDimension, near.getPosition().getDimension());
        var position = near.getPosition();
        var target = new Vec3(position.getX(), position.getY(), position.getZ());
        var vector = SFVec3i.fromDouble(target);
        yield new ResolvedGoal(
          near.getRadius() <= 0
            ? new PosGoal(vector)
            : new CloseToWorldPosGoal(target, near.getRadius()),
          _ -> target
        );
      }
      case ENTITY -> {
        var entityGoal = goal.getEntity();
        validateConnectionEpoch(
          bot,
          entityGoal.hasConnectionEpoch()
            ? entityGoal.getConnectionEpoch()
            : ""
        );
        var entityId = entityGoal.getEntityId();
        requireEntityPosition(bot, entityId);
        DynamicGoalScorer scorer = () -> {
          var position = requireEntityPosition(bot, entityId);
          return entityGoal.getRadius() <= 0
            ? new PosGoal(SFVec3i.fromDouble(position))
            : new CloseToWorldPosGoal(position, entityGoal.getRadius());
        };
        yield new ResolvedGoal(scorer, connection -> requireEntityPosition(connection, entityId));
      }
      case XZ -> {
        var xz = goal.getXz();
        validateDimension(currentDimension, xz.getDimension());
        var scorer = xz.getRadius() <= 0
          ? (GoalScorer) new XZGoal(
            (int) Math.round(xz.getX()),
            (int) Math.round(xz.getZ())
          )
          : new CloseToWorldXZGoal(
            xz.getX(),
            xz.getZ(),
            xz.getRadius()
          );
        yield new ResolvedGoal(
          scorer,
          connection -> {
            var player = connection.minecraft().player;
            return new Vec3(xz.getX(), player == null ? 0.0 : player.getY(), xz.getZ());
          }
        );
      }
      case Y -> {
        var y = goal.getY();
        validateDimension(currentDimension, y.getDimension());
        yield new ResolvedGoal(
          new YGoal(y.getY()),
          connection -> {
            var player = connection.minecraft().player;
            return new Vec3(
              player == null ? 0.0 : player.getX(),
              y.getY(),
              player == null ? 0.0 : player.getZ()
            );
          }
        );
      }
      case BREAK_BLOCK -> {
        var position = goal.getBreakBlock().getPosition();
        validateDimension(currentDimension, position.getDimension());
        var vector = SFVec3i.from(position.getX(), position.getY(), position.getZ());
        yield new ResolvedGoal(
          new BreakBlockPosGoal(vector),
          _ -> Vec3.atCenterOf(vector.toBlockPos())
        );
      }
      case PLACE_BLOCK -> {
        var position = goal.getPlaceBlock().getPosition();
        validateDimension(currentDimension, position.getDimension());
        var vector = SFVec3i.from(position.getX(), position.getY(), position.getZ());
        yield new ResolvedGoal(
          new PlaceBlockGoal(vector),
          _ -> Vec3.atCenterOf(vector.toBlockPos())
        );
      }
      case AWAY_FROM_POSITION -> {
        var away = goal.getAwayFromPosition();
        validateDimension(currentDimension, away.getPosition().getDimension());
        requirePositiveRadius(away.getRadius(), "away_from_position.radius");
        var position = away.getPosition();
        var origin = new Vec3(position.getX(), position.getY(), position.getZ());
        yield new ResolvedGoal(
          new AwayFromPosGoal(
            SFVec3i.fromDouble(origin),
            Math.max(1, Math.round(away.getRadius()))
          ),
          _ -> origin
        );
      }
      case AWAY_FROM_ENTITY -> {
        var away = goal.getAwayFromEntity();
        validateConnectionEpoch(
          bot,
          away.hasConnectionEpoch() ? away.getConnectionEpoch() : ""
        );
        requirePositiveRadius(away.getRadius(), "away_from_entity.radius");
        var entityId = away.getEntityId();
        requireEntityPosition(bot, entityId);
        var radius = Math.max(1, Math.round(away.getRadius()));
        DynamicGoalScorer scorer = () -> new AwayFromPosGoal(
          SFVec3i.fromDouble(requireEntityPosition(bot, entityId)), radius);
        yield new ResolvedGoal(scorer, connection -> requireEntityPosition(connection, entityId));
      }
      case ANY -> {
        var goals = goal.getAny().getGoalsList();
        if (goals.isEmpty()) {
          throw Status.INVALID_ARGUMENT
            .withDescription("any.goals must contain at least one goal")
            .asRuntimeException();
        }
        if (goals.size() > MAX_COMPOSITE_GOALS) {
          throw Status.INVALID_ARGUMENT
            .withDescription("any.goals may contain at most 64 goals")
            .asRuntimeException();
        }
        var resolved = goals.stream()
          .map(value -> resolveGoal(bot, value, depth + 1))
          .toList();
        yield new ResolvedGoal(
          new CompositeGoal(resolved.stream()
            .map(ResolvedGoal::scorer)
            .collect(java.util.stream.Collectors.toUnmodifiableSet())),
          connection -> closestPosition(connection, resolved)
        );
      }
      case GOAL_NOT_SET -> throw Status.INVALID_ARGUMENT
        .withDescription("goal must be set")
        .asRuntimeException();
    };
  }

  public static PathConstraint buildConstraint(
    BotConnection bot,
    PathfindOptions options
  ) {
    PathConstraint constraint = new PathConstraintImpl(bot);
    if (options.getAdditionalPlaceItemIdsCount() > 0) {
      var additionalItems = new LinkedHashSet<Item>();
      for (var itemId : options.getAdditionalPlaceItemIdsList()) {
        var identifier = Identifier.tryParse(itemId);
        if (
          identifier == null
            || !BuiltInRegistries.ITEM.containsKey(identifier)
        ) {
          throw Status.INVALID_ARGUMENT
            .withDescription("Unknown additional place item id: " + itemId)
            .asRuntimeException();
        }
        var item = BuiltInRegistries.ITEM.getValue(identifier);
        var block = BlockItems.getBlock(item);
        if (
          block.isEmpty()
            || !SFItemHelpers.isSafeFullBlock(block.orElseThrow())
        ) {
          throw Status.INVALID_ARGUMENT
            .withDescription(
              "Additional place item must be a safe, non-falling block: "
                + itemId
            )
            .asRuntimeException();
        }
        additionalItems.add(item);
      }
      constraint = new AdditionalPlacementConstraint(
        constraint,
        Set.copyOf(additionalItems)
      );
    }
    if (options.getProtectedBlocksCount() > 0) {
      if (options.getProtectedBlocksCount() > 4_096) {
        throw Status.INVALID_ARGUMENT
          .withDescription("At most 4096 protected blocks may be supplied")
          .asRuntimeException();
      }
      var currentDimension = currentDimension(bot);
      var protectedBlocks = new LinkedHashSet<SFVec3i>();
      for (var position : options.getProtectedBlocksList()) {
        validateDimension(currentDimension, position.getDimension());
        protectedBlocks.add(SFVec3i.from(
          position.getX(),
          position.getY(),
          position.getZ()
        ));
      }
      constraint = new BlockBreakBlacklistConstraint(
        constraint,
        Set.copyOf(protectedBlocks)
      );
    }
    if (!options.getAllowMining()) {
      constraint = new NoBlockBreakingConstraint(constraint);
    }
    if (!options.getAllowPlacing()) {
      constraint = new NoBlockPlacingConstraint(constraint);
    }
    if (options.getAvoidFluids()) {
      constraint = AvoidFluidConstraint.forPlayer(
        constraint,
        bot.minecraft().player
      );
    }
    if (options.hasMinimumY() || options.hasMaximumY()) {
      var minimumY = options.hasMinimumY()
        ? OptionalInt.of(options.getMinimumY())
        : OptionalInt.empty();
      var maximumY = options.hasMaximumY()
        ? OptionalInt.of(options.getMaximumY())
        : OptionalInt.empty();
      if (
        minimumY.isPresent()
          && maximumY.isPresent()
          && minimumY.getAsInt() > maximumY.getAsInt()
      ) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "minimum_y must be less than or equal to maximum_y"
          )
          .asRuntimeException();
      }
      constraint = new PathYRangeConstraint(
        constraint,
        minimumY,
        maximumY
      );
    }
    if (options.hasBreakBlockPenalty()
      || options.hasPlaceBlockPenalty()
      || options.getSearchTimeoutSeconds() > 0
      || options.hasSprint()
      || options.getSearchMode()
        != com.soulfiremc.grpc.generated.PathfindSearchMode.PATHFIND_SEARCH_MODE_UNSPECIFIED
      || options.hasMaximumQualityBound()
      || options.getMaximumExpandedStates() != 0
      || options.hasMaximumFallDistance()
      || options.hasMaximumParkourGap()
      || options.hasSmoothCamera()) {
      var breakPenalty = optionalPenalty(
        options.hasBreakBlockPenalty(),
        options.getBreakBlockPenalty(),
        "break_block_penalty"
      );
      var placePenalty = optionalPenalty(
        options.hasPlaceBlockPenalty(),
        options.getPlaceBlockPenalty(),
        "place_block_penalty"
      );
      var searchTimeout = options.getSearchTimeoutSeconds() == 0
        ? OptionalInt.empty()
        : OptionalInt.of(Math.toIntExact(Math.min(
          options.getSearchTimeoutSeconds(),
          3_600
        )));
      var searchMode = switch (options.getSearchMode()) {
        case PATHFIND_SEARCH_MODE_UNSPECIFIED -> java.util.Optional.<RouteSearchMode>empty();
        case PATHFIND_SEARCH_MODE_PRECISION -> java.util.Optional.of(RouteSearchMode.PRECISION);
        case PATHFIND_SEARCH_MODE_NORMAL -> java.util.Optional.of(RouteSearchMode.NORMAL);
        case PATHFIND_SEARCH_MODE_URGENT -> java.util.Optional.of(RouteSearchMode.URGENT);
        case PATHFIND_SEARCH_MODE_ESCAPE -> java.util.Optional.of(RouteSearchMode.ESCAPE);
        case UNRECOGNIZED -> throw Status.INVALID_ARGUMENT
          .withDescription("search_mode is not recognized")
          .asRuntimeException();
      };
      var maximumQualityBound = options.hasMaximumQualityBound()
        ? OptionalDouble.of(options.getMaximumQualityBound())
        : OptionalDouble.empty();
      var modeBound = searchMode.orElse(RouteSearchMode.NORMAL)
        .initialEpsilon();
      if (
        maximumQualityBound.isPresent()
          && (
            !Double.isFinite(maximumQualityBound.getAsDouble())
              || maximumQualityBound.getAsDouble() < 1
              || maximumQualityBound.getAsDouble() > modeBound
          )
      ) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            "maximum_quality_bound must be between 1.0 and the search mode's initial epsilon"
          )
          .asRuntimeException();
      }
      var requestedExpandedStates = Integer.toUnsignedLong(
        options.getMaximumExpandedStates()
      );
      var maximumExpandedStates = requestedExpandedStates == 0
        ? OptionalInt.empty()
        : OptionalInt.of(Math.toIntExact(Math.min(
          requestedExpandedStates,
          1_000_000
        )));
      var maximumFallDistance = boundedOptionalDistance(
        options.hasMaximumFallDistance(),
        options.getMaximumFallDistance(),
        "maximum_fall_distance",
        3
      );
      var maximumParkourGap = boundedOptionalDistance(
        options.hasMaximumParkourGap(),
        options.getMaximumParkourGap(),
        "maximum_parkour_gap",
        3
      );
      constraint = new ConfiguredPathConstraint(
        constraint,
        breakPenalty,
        placePenalty,
        searchTimeout,
        options.hasSprint()
          ? java.util.Optional.of(options.getSprint())
          : java.util.Optional.empty(),
        searchMode,
        maximumQualityBound,
        maximumExpandedStates,
        maximumFallDistance,
        maximumParkourGap,
        options.hasSmoothCamera()
          ? java.util.Optional.of(options.getSmoothCamera())
          : java.util.Optional.empty()
      );
    }
    return constraint;
  }

  private static Vec3 closestPosition(
    BotConnection bot,
    java.util.List<ResolvedGoal> goals
  ) {
    var player = bot.minecraft().player;
    Vec3 closest = null;
    RuntimeException failure = null;
    for (var goal : goals) {
      try {
        var position = goal.position().apply(bot);
        if (closest == null || (player != null
          && position.distanceToSqr(player.position()) < closest.distanceToSqr(player.position()))) {
          closest = position;
        }
      } catch (RuntimeException exception) {
        if (failure == null) failure = exception;
      }
    }
    if (closest != null) return closest;
    if (failure != null) throw failure;
    throw new IllegalStateException("No goal positions provided");
  }

  private static OptionalDouble optionalPenalty(
    boolean present,
    double value,
    String field
  ) {
    if (!present) {
      return OptionalDouble.empty();
    }
    if (!Double.isFinite(value) || value < 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be finite and non-negative")
        .asRuntimeException();
    }
    return OptionalDouble.of(value);
  }

  private static OptionalInt boundedOptionalDistance(
    boolean present,
    int value,
    String field,
    int maximum
  ) {
    if (!present) {
      return OptionalInt.empty();
    }
    if (value < 0 || value > maximum) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be between 0 and " + maximum)
        .asRuntimeException();
    }
    return OptionalInt.of(value);
  }

  private static void requirePositiveRadius(float radius, String field) {
    if (!Float.isFinite(radius) || radius <= 0) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be finite and greater than zero")
        .asRuntimeException();
    }
  }

  private static void validateConnectionEpoch(
    BotConnection bot,
    String requested
  ) {
    if (!requested.isBlank()
      && !bot.connectionEpoch().toString().equals(requested)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Entity reference belongs to a previous connection")
        .asRuntimeException();
    }
  }

  private static Vec3 requireEntityPosition(BotConnection bot, int entityId) {
    var level = bot.minecraft().level;
    if (level == null) {
      throw Status.FAILED_PRECONDITION.withDescription("Level is not loaded").asRuntimeException();
    }
    var entity = findEntityById(level, entityId);
    if (entity == null || entity.isRemoved()) {
      throw Status.NOT_FOUND.withDescription("Entity '%d' is not observable".formatted(entityId))
        .asRuntimeException();
    }
    return entity.position();
  }

  private static String currentDimension(BotConnection bot) {
    var level = bot.minecraft().level;
    if (level == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Level is not loaded")
        .asRuntimeException();
    }
    return level.dimension().identifier().toString();
  }

  private static void validateDimension(String current, String requested) {
    if (!requested.isBlank() && !current.equals(requested)) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Cross-dimension pathfinding is not supported: bot is in '%s', goal is in '%s'"
            .formatted(current, requested)
        )
        .asRuntimeException();
    }
  }

  private static Entity findEntityById(ClientLevel level, int id) {
    return StreamSupport.stream(level.entitiesForRendering().spliterator(), false)
      .filter(entity -> entity.getId() == id)
      .findFirst()
      .orElse(null);
  }

  public record ResolvedGoal(
    GoalScorer scorer,
    Function<BotConnection, Vec3> position
  ) {
  }
}
