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
package com.soulfiremc.server.grpc;

import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.bot.BotThreadExecution;
import com.soulfiremc.server.bot.CompletableControlTask;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlStopReason;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.pathfinding.cost.Costs;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.SFInventoryHelpers;
import com.soulfiremc.server.util.SFItemHelpers;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Semantic, revision-aware inventory and container operations.
public final class InventoryServiceImpl
  extends InventoryServiceGrpc.InventoryServiceImplBase {
  private static final Duration MUTATION_TIMEOUT = Duration.ofSeconds(10);
  private static final Map<AbstractContainerMenu, Long> MENU_IDS =
    Collections.synchronizedMap(new WeakHashMap<>());
  private static final AtomicLong NEXT_MENU_ID = new AtomicLong();
  private static final Set<ControlResource> INVENTORY_RESOURCES = Set.of(
    ControlResource.INVENTORY,
    ControlResource.CONTAINER
  );

  private final SoulFireServer server;
  private final RpcIdempotencyStore<InventoryMutationResponse> idempotency =
    new RpcIdempotencyStore<>();

  public InventoryServiceImpl(SoulFireServer server) {
    this.server = server;
  }

  @Override
  public void getContainerSnapshot(
    GetContainerSnapshotRequest request,
    StreamObserver<GetContainerSnapshotResponse> responseObserver
  ) {
    read(
      request.getScope(),
      responseObserver,
      context -> GetContainerSnapshotResponse.newBuilder()
        .setContainer(snapshot(context))
        .build()
    );
  }

  @Override
  public void countItems(
    CountItemsRequest request,
    StreamObserver<CountItemsResponse> responseObserver
  ) {
    read(request.getScope(), responseObserver, context -> {
      var areas = areas(request.getAreasList());
      long count = 0;
      for (var slot : context.menu.slots) {
        if (areas.contains(area(context.layout, slot.index))
          && matches(slot.getItem(), request.getSelector())) {
          count += slot.getItem().getCount();
        }
      }
      if (areas.contains(InventoryArea.INVENTORY_AREA_CURSOR)
        && matches(context.menu.getCarried(), request.getSelector())) {
        count += context.menu.getCarried().getCount();
      }
      return CountItemsResponse.newBuilder().setCount(count).build();
    });
  }

  @Override
  public void findInventorySlots(
    FindInventorySlotsRequest request,
    StreamObserver<FindInventorySlotsResponse> responseObserver
  ) {
    read(request.getScope(), responseObserver, context -> {
      var areas = areas(request.getAreasList());
      var snapshot = snapshot(context);
      return FindInventorySlotsResponse.newBuilder()
        .addAllSlots(context.menu.slots.stream()
          .filter(slot -> areas.contains(area(context.layout, slot.index)))
          .filter(slot -> matches(slot.getItem(), request.getSelector()))
          .map(slot -> snapshot.getSlots(slot.index))
          .toList())
        .setRevision(snapshot.getRevision())
        .build();
    });
  }

  @Override
  public void rankInventoryItems(
    RankInventoryItemsRequest request,
    StreamObserver<RankInventoryItemsResponse> responseObserver
  ) {
    read(request.getScope(), responseObserver, context -> {
      var kind = request.getKind();
      if (kind == InventoryRecommendationKind
        .INVENTORY_RECOMMENDATION_KIND_UNSPECIFIED
        || kind == InventoryRecommendationKind.UNRECOGNIZED) {
        throw invalid("kind must be a recognized recommendation kind");
      }
      var limit = request.getLimit() == 0 ? 10 : request.getLimit();
      if (limit > 100) {
        throw invalid("limit may not exceed 100");
      }
      var requestedAreas = recommendationAreas(request.getAreasList());
      var equipmentSlot = kind == InventoryRecommendationKind
        .INVENTORY_RECOMMENDATION_KIND_ARMOR
        ? parseArmorSlot(request)
        : null;
      var targetBlock = kind == InventoryRecommendationKind
        .INVENTORY_RECOMMENDATION_KIND_TOOL
        ? targetBlock(context, request)
        : null;
      var excludedEnchantments = request
        .getExcludedEnchantmentIdsList()
        .stream()
        .map(InventoryServiceImpl::normalizeIdentifier)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
      var preferredEnchantments = request
        .getPreferredEnchantmentIdsList()
        .stream()
        .map(InventoryServiceImpl::normalizeIdentifier)
        .toList();

      var container = snapshot(context);
      var recommendations = context.menu.slots.stream()
        .filter(slot -> requestedAreas.contains(
          area(context.layout, slot.index)
        ))
        .filter(slot -> !slot.getItem().isEmpty())
        .filter(slot -> !request.hasSelector()
          || matches(slot.getItem(), request.getSelector()))
        .filter(slot -> excludedEnchantments.isEmpty()
          || slot.getItem().getEnchantments().entrySet().stream()
          .map(entry -> entry.getKey().unwrapKey())
          .flatMap(java.util.Optional::stream)
          .map(key -> key.identifier().toString())
          .noneMatch(excludedEnchantments::contains))
        .map(slot -> score(
          context,
          slot,
          kind,
          targetBlock,
          equipmentSlot,
          request.getPreferHotbar(),
          request.getPreferHighDurability(),
          preferredEnchantments
        ))
        .flatMap(java.util.Optional::stream)
        .sorted(Comparator
          .comparingDouble(RankedItem::score)
          .reversed()
          .thenComparingInt(value -> value.slot.index))
        .limit(limit)
        .map(value -> InventoryItemRecommendation.newBuilder()
          .setSlot(container.getSlotsList().stream()
            .filter(slot -> slot.getSlot() == value.slot.index)
            .findFirst()
            .orElseThrow())
          .setScore(value.score)
          .addAllFactors(value.factors)
          .build())
        .toList();
      return RankInventoryItemsResponse.newBuilder()
        .addAllRecommendations(recommendations)
        .setRevision(container.getRevision())
        .build();
    });
  }

  @Override
  public void moveInventoryItem(
    MoveInventoryItemRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "move",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK move inventory item",
      context -> {
        var source = requireSlot(context.menu, request.getSourceSlot(), "source_slot");
        var destination = requireSlot(
          context.menu,
          request.getDestinationSlot(),
          "destination_slot"
        );
        var requested = request.hasCount()
          ? request.getCount()
          : source.getItem().getCount();
        moveExact(context, source, destination, requested);
      },
      responseObserver
    );
  }

  @Override
  public void transferItems(
    TransferItemsRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "transfer",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK transfer inventory items",
      context -> {
        requireMenu(context.menu, request);
        transfer(
          context,
          request.getSelector(),
          request.getCount(),
          requireArea(request.getFrom(), "from"),
          requireArea(request.getTo(), "to")
        );
      },
      responseObserver
    );
  }

  @Override
  public void tossItems(
    TossItemsRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "toss",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK toss inventory items",
      context -> toss(context, request.getSelector(), request.getCount()),
      responseObserver
    );
  }

  @Override
  public void selectHotbarItem(
    SelectHotbarItemRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "select-hotbar",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK select hotbar item",
      context -> selectHotbar(context, request),
      responseObserver
    );
  }

  @Override
  public void equipItem(
    EquipItemRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "equip",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK equip item",
      context -> equip(context, request),
      responseObserver
    );
  }

  @Override
  public void unequipItem(
    UnequipItemRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "unequip",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      request.getExpectedRevision(),
      "SDK unequip item",
      context -> unequip(context, request),
      responseObserver
    );
  }

  @Override
  public void openBlockContainer(
    OpenBlockContainerRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    try {
      var bot = requireBot(
        request.getScope(),
        InstancePermission.CONTROL_BOT_ACTIONS,
        true
      );
      var position = request.getPosition();
      var action = (java.util.function.Supplier<
        CompletableFuture<InventoryMutationResponse>>) () ->
        submitOpenContainer(bot, position);
      var future = request.hasIdempotencyKey()
        ? idempotency.execute(
          ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
          request.getScope().getInstanceId(),
          request.getScope().getBotId(),
          "open-container",
          request.getIdempotencyKey(),
          fingerprint(request.toByteArray()),
          action
        )
        : action.get();
      future.whenComplete((response, error) -> {
        if (error != null) {
          responseObserver.onError(toGrpcError(error));
          return;
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
      });
    } catch (Throwable throwable) {
      responseObserver.onError(toGrpcError(throwable));
    }
  }

  @Override
  public void closeSemanticContainer(
    CloseSemanticContainerRequest request,
    StreamObserver<InventoryMutationResponse> responseObserver
  ) {
    mutate(
      request.getScope(),
      "close-container",
      request.hasIdempotencyKey() ? request.getIdempotencyKey() : null,
      fingerprint(request.toByteArray()),
      0,
      "SDK close container",
      context -> {
        if (context.menu.containerId != request.getContainerId()) {
          throw Status.ABORTED
            .withDescription(
              "The active container changed before it could be closed"
            )
            .asRuntimeException();
        }
        context.player.closeContainer();
        context.refresh();
      },
      responseObserver
    );
  }

  private <T> void read(
    InventoryScope scope,
    StreamObserver<T> observer,
    InventoryRead<T> read
  ) {
    try {
      var bot = requireBot(scope, InstancePermission.READ_BOT_INFO, false);
      observer.onNext(BotThreadExecution.call(
        bot,
        () -> read.apply(Context.create(bot))
      ));
      observer.onCompleted();
    } catch (Throwable throwable) {
      observer.onError(toGrpcError(throwable));
    }
  }

  private void mutate(
    InventoryScope scope,
    String operation,
    @Nullable String idempotencyKey,
    String fingerprint,
    long expectedRevision,
    String description,
    InventoryMutation mutation,
    StreamObserver<InventoryMutationResponse> observer
  ) {
    try {
      var bot = requireBot(
        scope,
        InstancePermission.CONTROL_BOT_ACTIONS,
        true
      );
      CompletableFuture<InventoryMutationResponse> future;
      if (idempotencyKey == null) {
        future = submitMutation(
          bot,
          expectedRevision,
          description,
          mutation
        );
      } else {
        future = idempotency.execute(
          ServerRPCConstants.USER_CONTEXT_KEY.get().getUniqueId(),
          scope.getInstanceId(),
          scope.getBotId(),
          operation,
          idempotencyKey,
          fingerprint,
          () -> submitMutation(
            bot,
            expectedRevision,
            description,
            mutation
          )
        );
      }
      future.whenComplete((response, error) -> {
        if (error != null) {
          observer.onError(toGrpcError(error));
          return;
        }
        observer.onNext(response);
        observer.onCompleted();
      });
    } catch (Throwable throwable) {
      observer.onError(toGrpcError(throwable));
    }
  }

  private static CompletableFuture<InventoryMutationResponse> submitMutation(
    BotConnection bot,
    long expectedRevision,
    String description,
    InventoryMutation mutation
  ) {
    var result = new AtomicReference<ContainerSnapshot>();
    var task = new CompletableControlTask(ControlTask.once(
      description,
      INVENTORY_RESOURCES,
      () -> {
        var context = Context.create(bot);
        requireRevision(context, expectedRevision);
        requireEmptyCursor(context);
        mutation.apply(context);
        requireEmptyCursor(context);
        result.set(snapshot(context));
      }
    ));
    try {
      BotThreadExecution.call(bot, () -> {
        bot.botControl().replace(task);
        return null;
      });
    } catch (Throwable throwable) {
      return CompletableFuture.failedFuture(throwable);
    }

    var completion = task.completion()
      .orTimeout(MUTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    completion.whenComplete((_, error) -> {
      if (unwrap(error) instanceof TimeoutException) {
        bot.botControl().cancel(task);
      }
    });
    return completion.thenApply(reason -> {
      if (!successful(reason)) {
        throw Status.ABORTED
          .withDescription(
            "Inventory mutation was "
              + reason.name().toLowerCase(Locale.ROOT)
          )
          .asRuntimeException();
      }
      return InventoryMutationResponse.newBuilder()
        .setActionId(task.actionId().toString())
        .setContainer(Objects.requireNonNull(result.get()))
        .build();
    });
  }

  private static CompletableFuture<InventoryMutationResponse>
  submitOpenContainer(BotConnection bot, BlockPosition position) {
    var opened = new AtomicReference<ContainerSnapshot>();
    var task = new CompletableControlTask(
      new OpenContainerTask(bot, position, opened)
    );
    try {
      BotThreadExecution.call(bot, () -> {
        bot.botControl().replace(task);
        return null;
      });
    } catch (Throwable throwable) {
      return CompletableFuture.failedFuture(throwable);
    }
    var completion = task.completion()
      .orTimeout(MUTATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    completion.whenComplete((_, error) -> {
      if (unwrap(error) instanceof TimeoutException) {
        bot.botControl().cancel(task);
      }
    });
    return completion.thenApply(reason -> {
      if (!successful(reason)) {
        throw Status.ABORTED
          .withDescription(
            "Container open was " + reason.name().toLowerCase(Locale.ROOT)
          )
          .asRuntimeException();
      }
      return InventoryMutationResponse.newBuilder()
        .setActionId(task.actionId().toString())
        .setContainer(Objects.requireNonNull(opened.get()))
        .build();
    });
  }

  private BotConnection requireBot(
    InventoryScope scope,
    InstancePermission permission,
    boolean requireControl
  ) {
    if (scope.getInstanceId().isBlank() || scope.getBotId().isBlank()) {
      throw invalid("scope.instance_id and scope.bot_id are required");
    }
    var instanceId = parseUuid(scope.getInstanceId(), "scope.instance_id");
    var botId = parseUuid(scope.getBotId(), "scope.bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get()
      .hasPermissionOrThrow(PermissionContext.instance(permission, instanceId));
    var instance = server.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance '%s' not found".formatted(instanceId))
        .asRuntimeException());
    if (requireControl) {
      try {
        instance.botControlLeaseManager().authorize(
          botId,
          ServerRPCConstants.BOT_CONTROL_TOKEN_CONTEXT_KEY.get()
        );
      } catch (BotControlLeaseManager.InvalidLeaseException exception) {
        throw Status.PERMISSION_DENIED
          .withDescription(exception.getMessage())
          .asRuntimeException();
      }
    }
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot '%s' is not online".formatted(botId))
        .asRuntimeException();
    }
    return bot;
  }

  private static ContainerSnapshot snapshot(Context context) {
    context.refresh();
    var menu = context.menu;
    var builder = ContainerSnapshot.newBuilder()
      .setContainerId(menu.containerId)
      .setStateId(menu.getStateId())
      .setRevision(revision(context))
      .setMenuId(menuId(menu))
      .setContainerType(context.layout.getContainerType())
      .setTitle(TextComponent.newBuilder()
        .setPlainText(context.layout.getTitle()))
      .setLayout(context.layout)
      .setSelectedHotbarSlot(
        context.player.getInventory().getSelectedSlot()
      );
    var pathBuildingBlockCount = 0;
    for (var slot : menu.slots) {
      var item = slot.getItem();
      var slotArea = area(context.layout, slot.index);
      var itemSnapshot = InventorySlotSnapshot.newBuilder()
        .setSlot(slot.index)
        .setArea(slotArea)
        .setMayPlace(slot.mayPlace(item.isEmpty() ? ItemStack.EMPTY : item))
        .setMayPickup(!item.isEmpty() && slot.mayPickup(context.player));
      if (!item.isEmpty()) {
        itemSnapshot.setItem(MinecraftDomainMapper.item(item));
        if (
          (slotArea == InventoryArea.INVENTORY_AREA_MAIN
            || slotArea == InventoryArea.INVENTORY_AREA_HOTBAR)
            && SFItemHelpers.isPathBuildingBlockItem(item)
        ) {
          pathBuildingBlockCount += item.getCount();
        }
      }
      builder.addSlots(itemSnapshot);
    }
    builder.setPathBuildingBlockCount(pathBuildingBlockCount);
    if (!menu.getCarried().isEmpty()) {
      builder.setCarried(MinecraftDomainMapper.item(menu.getCarried()));
    }
    return builder.build();
  }

  /// Never reused, unlike containerId, which restarts with each new player.
  static long menuId(AbstractContainerMenu menu) {
    return MENU_IDS.computeIfAbsent(menu, _ -> NEXT_MENU_ID.incrementAndGet());
  }

  private static long revision(Context context) {
    var hash = 0xcbf29ce484222325L;
    hash = mix(hash, context.menu.containerId);
    hash = mix(hash, context.menu.getStateId());
    hash = mix(hash, context.player.getInventory().getSelectedSlot());
    for (var slot : context.menu.slots) {
      hash = mix(hash, slot.index);
      hash = mix(hash, stackIdentity(slot.getItem()));
    }
    return mix(hash, stackIdentity(context.menu.getCarried()));
  }

  private static long mix(long hash, int value) {
    return mix(hash, Integer.toString(value));
  }

  private static long mix(long hash, String value) {
    var result = hash;
    for (var valueByte : value.getBytes(StandardCharsets.UTF_8)) {
      result ^= Byte.toUnsignedInt(valueByte);
      result *= 0x100000001b3L;
    }
    return result;
  }

  private static String stackIdentity(ItemStack stack) {
    if (stack.isEmpty()) {
      return "-";
    }
    var snapshot = MinecraftDomainMapper.item(stack);
    return "%s:%d:%s".formatted(
      snapshot.getItemId(),
      snapshot.getCount(),
      snapshot.getFingerprint()
    );
  }

  private static InventoryArea area(ContainerLayout layout, int slotIndex) {
    for (var region : layout.getRegionsList()) {
      if (slotIndex < region.getStartIndex()
        || slotIndex >= region.getStartIndex() + region.getSlotCount()) {
        continue;
      }
      return switch (region.getId()) {
        case "player_inventory" -> InventoryArea.INVENTORY_AREA_MAIN;
        case "player_hotbar" -> InventoryArea.INVENTORY_AREA_HOTBAR;
        case "armor" -> InventoryArea.INVENTORY_AREA_ARMOR;
        case "offhand" -> InventoryArea.INVENTORY_AREA_OFFHAND;
        case "crafting_output", "crafting_grid" ->
          InventoryArea.INVENTORY_AREA_CRAFTING;
        default -> InventoryArea.INVENTORY_AREA_CONTAINER;
      };
    }
    return InventoryArea.INVENTORY_AREA_CONTAINER;
  }

  private static EnumSet<InventoryArea> recommendationAreas(
    List<InventoryArea> requested
  ) {
    if (requested.isEmpty()) {
      return EnumSet.of(
        InventoryArea.INVENTORY_AREA_MAIN,
        InventoryArea.INVENTORY_AREA_HOTBAR,
        InventoryArea.INVENTORY_AREA_ARMOR,
        InventoryArea.INVENTORY_AREA_OFFHAND
      );
    }
    return areas(requested);
  }

  private static EquipmentSlot parseArmorSlot(
    RankInventoryItemsRequest request
  ) {
    if (!request.hasEquipmentSlot()) {
      throw invalid("equipment_slot is required when kind is ARMOR");
    }
    return switch (
      request.getEquipmentSlot().toLowerCase(Locale.ROOT)
    ) {
      case "head", "helmet" -> EquipmentSlot.HEAD;
      case "chest", "chestplate" -> EquipmentSlot.CHEST;
      case "legs", "leggings" -> EquipmentSlot.LEGS;
      case "feet", "boots" -> EquipmentSlot.FEET;
      default -> throw invalid(
        "equipment_slot must be head, chest, legs, or feet"
      );
    };
  }

  private static BlockState targetBlock(
    Context context,
    RankInventoryItemsRequest request
  ) {
    if (!request.hasTargetBlock()) {
      throw invalid("target_block is required when kind is TOOL");
    }
    var requested = request.getTargetBlock();
    var level = Objects.requireNonNull(context.bot.minecraft().level);
    var currentDimension = level.dimension().identifier().toString();
    if (!requested.getDimension().isBlank()
      && !requested.getDimension().equals(currentDimension)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Target block is in another dimension")
        .asRuntimeException();
    }
    var position = new BlockPos(
      requested.getX(),
      requested.getY(),
      requested.getZ()
    );
    if (!level.getChunkSource().hasChunk(
      position.getX() >> 4,
      position.getZ() >> 4
    )) {
      throw Status.NOT_FOUND
        .withDescription("Target block is not loaded")
        .asRuntimeException();
    }
    return level.getBlockState(position);
  }

  private static java.util.Optional<RankedItem> score(
    Context context,
    Slot slot,
    InventoryRecommendationKind kind,
    @Nullable BlockState targetBlock,
    @Nullable EquipmentSlot equipmentSlot,
    boolean preferHotbar,
    boolean preferHighDurability,
    List<String> preferredEnchantments
  ) {
    var stack = slot.getItem();
    var factors = new ArrayList<InventoryItemScoreFactor>();
    var score = switch (kind) {
      case INVENTORY_RECOMMENDATION_KIND_TOOL ->
        toolScore(context, stack, Objects.requireNonNull(targetBlock), factors);
      case INVENTORY_RECOMMENDATION_KIND_MELEE_WEAPON ->
        weaponScore(stack, factors);
      case INVENTORY_RECOMMENDATION_KIND_ARMOR ->
        armorScore(stack, Objects.requireNonNull(equipmentSlot), factors);
      case INVENTORY_RECOMMENDATION_KIND_FOOD ->
        foodScore(stack, factors);
      case INVENTORY_RECOMMENDATION_KIND_SCAFFOLD ->
        scaffoldScore(stack, factors);
      case INVENTORY_RECOMMENDATION_KIND_UNSPECIFIED, UNRECOGNIZED ->
        Double.NaN;
    };
    if (!Double.isFinite(score)) {
      return java.util.Optional.empty();
    }

    if (preferHotbar
      && area(context.layout, slot.index)
      == InventoryArea.INVENTORY_AREA_HOTBAR) {
      score += addFactor(
        factors,
        "hotbar",
        1,
        "Already available in the hotbar"
      );
    }
    if (preferHighDurability && stack.isDamageableItem()) {
      var remaining = stack.getMaxDamage() - stack.getDamageValue();
      var ratio = (double) remaining / stack.getMaxDamage();
      score += addFactor(
        factors,
        "durability",
        ratio * 2,
        "%d of %d durability remains".formatted(
          remaining,
          stack.getMaxDamage()
        )
      );
      if (remaining <= 5) {
        score += addFactor(
          factors,
          "near_breaking",
          -100,
          "Five or fewer uses remain"
        );
      }
    }
    if (!preferredEnchantments.isEmpty()) {
      var enchantments = stack.getEnchantments().entrySet().stream()
        .map(entry -> entry.getKey().unwrapKey())
        .flatMap(java.util.Optional::stream)
        .map(key -> key.identifier().toString())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
      for (var preferred : preferredEnchantments) {
        if (enchantments.contains(preferred)) {
          score += addFactor(
            factors,
            "preferred_enchantment",
            25,
            "Carries " + preferred
          );
        }
      }
    }
    return java.util.Optional.of(new RankedItem(slot, score, factors));
  }

  private static double toolScore(
    Context context,
    ItemStack stack,
    BlockState target,
    List<InventoryItemScoreFactor> factors
  ) {
    if (stack.get(DataComponents.TOOL) == null) {
      return Double.NaN;
    }
    var ticks = Costs.getRequiredMiningTicks(
      context.player,
      stack,
      target
    ).ticks();
    var score = addFactor(
      factors,
      "mining_speed",
      1_000.0 / (ticks + 1),
      "Estimated to break the block in %d tick(s)".formatted(ticks)
    );
    if (stack.isCorrectToolForDrops(target)) {
      score += addFactor(
        factors,
        "correct_tool",
        1_000,
        "Preserves block drops that require the correct tool"
      );
    }
    return score;
  }

  private static double weaponScore(
    ItemStack stack,
    List<InventoryItemScoreFactor> factors
  ) {
    var stats = SFItemHelpers.meleeWeaponStats(stack).orElse(null);
    if (stats == null) {
      return Double.NaN;
    }
    var damageContribution = addFactor(
      factors,
      "attack_damage",
      stats.attackDamage() * 10,
      "%.2f attack damage".formatted(stats.attackDamage())
    );
    var speedContribution = addFactor(
      factors,
      "attack_speed",
      Math.max(0, stats.attackSpeed()),
      "%.2f attacks per second".formatted(stats.attackSpeed())
    );
    return damageContribution + speedContribution;
  }

  private static double armorScore(
    ItemStack stack,
    EquipmentSlot slot,
    List<InventoryItemScoreFactor> factors
  ) {
    var equippable = stack.get(DataComponents.EQUIPPABLE);
    if (equippable == null || equippable.slot() != slot) {
      return Double.NaN;
    }
    var modifiers = Objects.requireNonNullElse(
      stack.get(DataComponents.ATTRIBUTE_MODIFIERS),
      ItemAttributeModifiers.EMPTY
    );
    var armor = modifiers.compute(Attributes.ARMOR, 0, slot);
    var toughness = modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
    var knockback = modifiers.compute(
      Attributes.KNOCKBACK_RESISTANCE,
      0,
      slot
    );
    return addFactor(
      factors,
      "armor",
      armor * 10,
      "%.2f armor".formatted(armor)
    ) + addFactor(
      factors,
      "toughness",
      toughness * 2,
      "%.2f armor toughness".formatted(toughness)
    ) + addFactor(
      factors,
      "knockback_resistance",
      knockback * 10,
      "%.2f knockback resistance".formatted(knockback)
    );
  }

  private static double foodScore(
    ItemStack stack,
    List<InventoryItemScoreFactor> factors
  ) {
    var food = stack.get(DataComponents.FOOD);
    if (food == null || !SFItemHelpers.isGoodEdibleFood(stack)) {
      return Double.NaN;
    }
    return addFactor(
      factors,
      "nutrition",
      food.nutrition() * 10,
      "%d hunger points".formatted(food.nutrition())
    ) + addFactor(
      factors,
      "saturation",
      food.saturation() * 5,
      "%.2f saturation".formatted(food.saturation())
    );
  }

  private static double scaffoldScore(
    ItemStack stack,
    List<InventoryItemScoreFactor> factors
  ) {
    if (!SFItemHelpers.isSafeFullBlockItem(stack)) {
      return Double.NaN;
    }
    return addFactor(
      factors,
      "available_count",
      stack.getCount(),
      stack.getCount() + " safe placement block(s) in this stack"
    );
  }

  private static double addFactor(
    List<InventoryItemScoreFactor> factors,
    String name,
    double contribution,
    String detail
  ) {
    factors.add(InventoryItemScoreFactor.newBuilder()
      .setName(name)
      .setContribution(contribution)
      .setDetail(detail)
      .build());
    return contribution;
  }

  private static EnumSet<InventoryArea> areas(List<InventoryArea> requested) {
    if (requested.isEmpty()) {
      return EnumSet.of(
        InventoryArea.INVENTORY_AREA_CONTAINER,
        InventoryArea.INVENTORY_AREA_MAIN,
        InventoryArea.INVENTORY_AREA_HOTBAR,
        InventoryArea.INVENTORY_AREA_ARMOR,
        InventoryArea.INVENTORY_AREA_OFFHAND,
        InventoryArea.INVENTORY_AREA_CRAFTING
      );
    }
    var result = EnumSet.noneOf(InventoryArea.class);
    for (var area : requested) {
      var required = requireArea(area, "areas");
      if (required == InventoryArea.INVENTORY_AREA_PLAYER) {
        result.add(InventoryArea.INVENTORY_AREA_MAIN);
        result.add(InventoryArea.INVENTORY_AREA_HOTBAR);
      } else {
        result.add(required);
      }
    }
    return result;
  }

  private static InventoryArea requireArea(
    InventoryArea area,
    String field
  ) {
    if (area == InventoryArea.INVENTORY_AREA_UNSPECIFIED
      || area == InventoryArea.UNRECOGNIZED) {
      throw invalid(field + " must contain a recognized inventory area");
    }
    return area;
  }

  public static boolean matches(ItemStack stack, ItemSelector selector) {
    if (stack.isEmpty()) {
      return false;
    }
    var snapshot = MinecraftDomainMapper.item(stack);
    if (!matchesSnapshot(snapshot, selector)) {
      return false;
    }
    for (var tag : selector.getTagsList()) {
      try {
        if (!stack.is(TagKey.create(
          Registries.ITEM,
          Identifier.parse(normalizeIdentifier(tag))
        ))) {
          return false;
        }
      } catch (RuntimeException exception) {
        throw invalid("Invalid item tag: " + tag);
      }
    }
    return true;
  }

  /// Preflights and applies a batch from an already-open menu while a
  /// server-side task owns the bot's inventory and container resources. No
  /// click is sent until every exact operation has a complete plan.
  public static List<Integer> transferBatchForTask(
    BotConnection bot,
    List<ContainerTransferOperation> operations,
    InventoryArea from,
    InventoryArea to
  ) {
    var context = Context.create(bot);
    requireEmptyCursor(context);
    var planned = planTaskBatch(context, operations, from, to);
    applyMoves(context, planned.moves());
    requireEmptyCursor(context);
    return planned.transferred();
  }

  public static long containerRevisionForTask(BotConnection bot) {
    return revision(Context.create(bot));
  }

  private static TaskBatchPlan planTaskBatch(
    Context context,
    List<ContainerTransferOperation> operations,
    InventoryArea from,
    InventoryArea to
  ) {
    var sources = context.menu.slots.stream()
      .filter(slot -> matchesArea(
        from,
        area(context.layout, slot.index)
      ))
      .toList();
    var destinations = context.menu.slots.stream()
      .filter(slot -> matchesArea(
        to,
        area(context.layout, slot.index)
      ))
      .toList();
    var simulated = context.menu.slots.stream()
      .collect(java.util.stream.Collectors.toMap(
        slot -> slot.index,
        slot -> slot.getItem().copy()
      ));
    var moves = new ArrayList<Move>();
    var transferred = new ArrayList<Integer>(operations.size());
    for (var operationIndex = 0;
         operationIndex < operations.size();
         operationIndex++) {
      var operation = operations.get(operationIndex);
      var remaining = operation.getCount();
      for (var source : sources) {
        if (remaining == 0) {
          break;
        }
        var sourceStack = simulated.get(source.index);
        if (!matches(sourceStack, operation.getSelector())) {
          continue;
        }
        for (var destination : destinations) {
          if (remaining == 0 || sourceStack.isEmpty()) {
            break;
          }
          var destinationStack = simulated.get(destination.index);
          if (
            !destination.mayPlace(sourceStack)
              || !destinationStack.isEmpty()
              && !ItemStack.isSameItemSameComponents(
                sourceStack,
                destinationStack
              )
          ) {
            continue;
          }
          var capacity = destination.getMaxStackSize(sourceStack)
            - destinationStack.getCount();
          if (capacity <= 0) {
            continue;
          }
          var moved = Math.min(
            Math.min(sourceStack.getCount(), capacity),
            remaining
          );
          moves.add(new Move(source.index, destination.index, moved));
          if (destinationStack.isEmpty()) {
            simulated.put(
              destination.index,
              sourceStack.copyWithCount(moved)
            );
          } else {
            destinationStack.grow(moved);
          }
          sourceStack.shrink(moved);
          remaining -= moved;
        }
      }
      var operationTransferred = operation.getCount() - remaining;
      if (remaining > 0 && !operation.getAllowPartial()) {
        throw Status.FAILED_PRECONDITION
          .withDescription(
            "Container transfer operation %d can move only %d of %d items"
              .formatted(
                operationIndex + 1,
                operationTransferred,
                operation.getCount()
              )
          )
          .asRuntimeException();
      }
      transferred.add(operationTransferred);
    }
    return new TaskBatchPlan(moves, List.copyOf(transferred));
  }

  private static boolean matchesSnapshot(
    ItemStackSnapshot stack,
    ItemSelector selector
  ) {
    if (!selector.getItemIdsList().isEmpty()
      && selector.getItemIdsList().stream()
      .map(InventoryServiceImpl::normalizeIdentifier)
      .noneMatch(stack.getItemId()::equals)) {
      return false;
    }
    if (selector.hasFingerprint()
      && !selector.getFingerprint().equals(stack.getFingerprint())) {
      return false;
    }
    if (selector.hasNameContains()) {
      var name = stack.hasCustomName()
        ? stack.getCustomName().getPlainText()
        : stack.getItemId();
      if (!name.toLowerCase(Locale.ROOT).contains(
        selector.getNameContains().toLowerCase(Locale.ROOT)
      )) {
        return false;
      }
    }
    if (selector.hasMinimumCount()
      && stack.getCount() < selector.getMinimumCount()) {
      return false;
    }
    if (selector.hasMinimumRemainingDurability()) {
      var remaining = stack.getMaxDamage() <= 0
        ? Integer.MAX_VALUE
        : stack.getMaxDamage() - stack.getDamage();
      if (remaining < selector.getMinimumRemainingDurability()) {
        return false;
      }
    }
    for (var enchantment : selector.getEnchantmentIdsList()) {
      var normalized = normalizeIdentifier(enchantment);
      if (stack.getEnchantmentsList().stream()
        .noneMatch(value -> value.getEnchantmentId().equals(normalized))) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeIdentifier(String value) {
    return value.indexOf(':') < 0 ? "minecraft:" + value : value;
  }

  private static void moveExact(
    Context context,
    Slot source,
    Slot destination,
    int count
  ) {
    if (source.index == destination.index) {
      throw invalid("source_slot and destination_slot must differ");
    }
    var sourceStack = source.getItem();
    if (sourceStack.isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Source slot is empty")
        .asRuntimeException();
    }
    if (count <= 0 || count > sourceStack.getCount()) {
      throw invalid(
        "count must be between one and the source stack count"
      );
    }
    if (!source.mayPickup(context.player)
      || !destination.mayPlace(sourceStack)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("The requested slots do not allow this move")
        .asRuntimeException();
    }
    var destinationStack = destination.getItem();
    if (!destinationStack.isEmpty()
      && !ItemStack.isSameItemSameComponents(
        sourceStack,
        destinationStack
      )) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Destination contains an incompatible item")
        .asRuntimeException();
    }
    var capacity = destination.getMaxStackSize(sourceStack)
      - destinationStack.getCount();
    if (capacity < count) {
      throw Status.RESOURCE_EXHAUSTED
        .withDescription("Destination does not have enough space")
        .asRuntimeException();
    }
    if (takenWholeOnly(source) && count != sourceStack.getCount()) {
      throw wholeOnly(source);
    }

    click(context, source.index, 0, ContainerInput.PICKUP);
    for (var moved = 0; moved < count; moved++) {
      click(context, destination.index, 1, ContainerInput.PICKUP);
    }
    if (!context.menu.getCarried().isEmpty()) {
      click(context, source.index, 0, ContainerInput.PICKUP);
    }
  }

  /// Whether a slot only gives its stack whole: an output slot (a furnace's, a
  /// crafting result) takes nothing back, so the rest of a partly taken stack
  /// would stay on the cursor, as it would for a player.
  private static boolean takenWholeOnly(Slot slot) {
    var stack = slot.getItem();
    return !stack.isEmpty() && !slot.mayPlace(stack);
  }

  private static StatusRuntimeException wholeOnly(Slot slot) {
    var stack = slot.getItem();
    return Status.FAILED_PRECONDITION
      .withDescription(
        "Slot %d is an output: it can only be taken whole, all %d %s"
          .formatted(
            slot.index,
            stack.getCount(),
            BuiltInRegistries.ITEM.getKey(stack.getItem())
          )
      )
      .asRuntimeException();
  }

  /// Applies planned moves. Moves from one source are consecutive (the
  /// planners go source by source). A source that only gives its stack whole
  /// is picked up once and spread over its moves, which must take all of it:
  /// checked for every source before the first click.
  private static void applyMoves(Context context, List<Move> moves) {
    for (var i = 0; i < moves.size(); ) {
      var source = context.menu.getSlot(moves.get(i).source);
      var planned = 0;
      for (; i < moves.size() && moves.get(i).source == source.index; i++) {
        planned += moves.get(i).count;
      }
      if (takenWholeOnly(source) && planned != source.getItem().getCount()) {
        throw wholeOnly(source);
      }
    }
    for (var i = 0; i < moves.size(); ) {
      var move = moves.get(i);
      var source = context.menu.getSlot(move.source);
      if (!takenWholeOnly(source)) {
        moveExact(context, source, context.menu.getSlot(move.destination), move.count);
        i++;
        continue;
      }
      click(context, source.index, 0, ContainerInput.PICKUP);
      for (; i < moves.size() && moves.get(i).source == source.index; i++) {
        for (var placed = 0; placed < moves.get(i).count; placed++) {
          click(context, moves.get(i).destination, 1, ContainerInput.PICKUP);
        }
      }
    }
  }

  private static void transfer(
    Context context,
    ItemSelector selector,
    int count,
    InventoryArea from,
    InventoryArea to
  ) {
    if (from == to) {
      throw invalid("from and to must differ");
    }
    if (from == InventoryArea.INVENTORY_AREA_CURSOR
      || to == InventoryArea.INVENTORY_AREA_CURSOR) {
      throw invalid("Semantic transfers do not use the cursor area");
    }
    if (count <= 0) {
      throw invalid("count must be greater than zero");
    }

    var sources = context.menu.slots.stream()
      .filter(slot -> matchesArea(
        from,
        area(context.layout, slot.index)
      ))
      .filter(slot -> matches(slot.getItem(), selector))
      .toList();
    var available = sources.stream()
      .mapToInt(slot -> slot.getItem().getCount())
      .sum();
    if (available < count) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Only %d matching items are available".formatted(available)
        )
        .asRuntimeException();
    }
    var destinations = context.menu.slots.stream()
      .filter(slot -> matchesArea(
        to,
        area(context.layout, slot.index)
      ))
      .toList();
    applyMoves(context, planMoves(sources, destinations, count));
  }

  private static boolean matchesArea(
    InventoryArea requested,
    InventoryArea actual
  ) {
    return requested == actual
      || requested == InventoryArea.INVENTORY_AREA_PLAYER
      && (
        actual == InventoryArea.INVENTORY_AREA_MAIN
        || actual == InventoryArea.INVENTORY_AREA_HOTBAR
      );
  }

  private static List<Move> planMoves(
    List<Slot> sources,
    List<Slot> destinations,
    int requested
  ) {
    var planned = planMovesUpTo(sources, destinations, requested);
    if (planned.transferred() != requested) {
      throw Status.RESOURCE_EXHAUSTED
        .withDescription("Destination area does not have enough space")
        .asRuntimeException();
    }
    return planned.moves();
  }

  private static PlannedMoves planMovesUpTo(
    List<Slot> sources,
    List<Slot> destinations,
    int requested
  ) {
    var simulated = destinations.stream()
      .map(slot -> new SimulatedSlot(
        slot,
        slot.getItem().copy(),
        slot.getItem().getCount()
      ))
      .toList();
    var plan = new ArrayList<Move>();
    var remaining = requested;
    for (var source : sources) {
      var sourceStack = source.getItem();
      var sourceRemaining = Math.min(sourceStack.getCount(), remaining);
      for (var destination : simulated) {
        if (sourceRemaining == 0) {
          break;
        }
        if (!destination.slot.mayPlace(sourceStack)
          || !destination.stack.isEmpty()
          && !ItemStack.isSameItemSameComponents(
            sourceStack,
            destination.stack
          )) {
          continue;
        }
        var capacity = destination.slot.getMaxStackSize(sourceStack)
          - destination.count;
        if (capacity <= 0) {
          continue;
        }
        var moved = Math.min(sourceRemaining, capacity);
        plan.add(new Move(source.index, destination.slot.index, moved));
        if (destination.stack.isEmpty()) {
          destination.stack = sourceStack.copyWithCount(moved);
        }
        destination.count += moved;
        sourceRemaining -= moved;
        remaining -= moved;
      }
      if (remaining == 0) {
        return new PlannedMoves(plan, requested);
      }
    }
    return new PlannedMoves(plan, requested - remaining);
  }

  private static void toss(
    Context context,
    ItemSelector selector,
    int count
  ) {
    if (count <= 0) {
      throw invalid("count must be greater than zero");
    }
    var sources = context.menu.slots.stream()
      .filter(slot -> matches(slot.getItem(), selector))
      .filter(slot -> slot.mayPickup(context.player))
      .toList();
    var available = sources.stream()
      .mapToInt(slot -> slot.getItem().getCount())
      .sum();
    if (available < count) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Only %d matching items are available".formatted(available)
        )
        .asRuntimeException();
    }
    var remaining = count;
    for (var source : sources) {
      if (remaining == 0) {
        return;
      }
      var inStack = source.getItem().getCount();
      if (inStack <= remaining) {
        click(context, source.index, 1, ContainerInput.THROW);
        remaining -= inStack;
      } else {
        for (var dropped = 0; dropped < remaining; dropped++) {
          click(context, source.index, 0, ContainerInput.THROW);
        }
        return;
      }
    }
  }

  private static void selectHotbar(
    Context context,
    SelectHotbarItemRequest request
  ) {
    switch (request.getSelectionCase()) {
      case HOTBAR_SLOT -> {
        if (request.getHotbarSlot() < 0 || request.getHotbarSlot() > 8) {
          throw invalid("hotbar_slot must be between zero and eight");
        }
        context.player.getInventory().setSelectedSlot(
          request.getHotbarSlot()
        );
      }
      case SELECTOR -> selectMatchingHotbar(context, request.getSelector());
      case SELECTION_NOT_SET ->
        throw invalid("Either hotbar_slot or selector is required");
    }
  }

  private static void selectMatchingHotbar(
    Context context,
    ItemSelector selector
  ) {
    var selected = context.player.getInventory().getSelectedSlot();
    var hotbar = context.menu.slots.stream()
      .filter(slot -> area(context.layout, slot.index)
        == InventoryArea.INVENTORY_AREA_HOTBAR)
      .toList();
    for (var index = 0; index < hotbar.size(); index++) {
      if (matches(hotbar.get(index).getItem(), selector)) {
        context.player.getInventory().setSelectedSlot(index);
        return;
      }
    }
    var source = context.menu.slots.stream()
      .filter(slot -> area(context.layout, slot.index)
        != InventoryArea.INVENTORY_AREA_HOTBAR)
      .filter(slot -> matches(slot.getItem(), selector))
      .findFirst()
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("No matching item is available")
        .asRuntimeException());
    if (area(context.layout, source.index) == InventoryArea.INVENTORY_AREA_OFFHAND) {
      // Through an empty hotbar slot: a swap with the held one would put
      // what you hold into the off-hand.
      var hotbarIndex = SFInventoryHelpers.hotbarForSwapIn(context.player.getInventory());
      click(context, source.index, hotbarIndex, ContainerInput.SWAP);
      context.player.getInventory().setSelectedSlot(hotbarIndex);
      return;
    }
    click(context, source.index, selected, ContainerInput.SWAP);
    context.player.getInventory().setSelectedSlot(selected);
  }

  private static void equip(Context context, EquipItemRequest request) {
    var slot = switch (request.getEquipmentSlot().toLowerCase(Locale.ROOT)) {
      case "mainhand", "main_hand" -> EquipmentSlot.MAINHAND;
      case "offhand", "off_hand" -> EquipmentSlot.OFFHAND;
      case "head", "helmet" -> EquipmentSlot.HEAD;
      case "chest", "chestplate" -> EquipmentSlot.CHEST;
      case "legs", "leggings" -> EquipmentSlot.LEGS;
      case "feet", "boots" -> EquipmentSlot.FEET;
      default -> throw invalid(
        "equipment_slot must be mainhand, offhand, head, chest, legs, or feet"
      );
    };
    if (slot == EquipmentSlot.MAINHAND) {
      selectMatchingHotbar(context, request.getSelector());
      return;
    }

    if (!(context.menu instanceof InventoryMenu)) {
      context.player.closeContainer();
      context.refresh();
    }
    var targetIndex = switch (slot) {
      case HEAD -> 5;
      case CHEST -> 6;
      case LEGS -> 7;
      case FEET -> 8;
      case OFFHAND -> 45;
      default -> throw new AssertionError("Unexpected equipment slot");
    };
    var source = context.menu.slots.stream()
      .filter(value -> value.index != targetIndex)
      .filter(value -> matches(value.getItem(), request.getSelector()))
      .filter(value -> context.player.isEquippableInSlot(
        value.getItem(),
        slot
      ))
      .findFirst()
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription(
          "No matching item can be equipped in " + slot.getSerializedName()
        )
        .asRuntimeException());
    swap(context, source, context.menu.getSlot(targetIndex));
  }

  private static void unequip(
    Context context,
    UnequipItemRequest request
  ) {
    var slot = switch (request.getEquipmentSlot().toLowerCase(Locale.ROOT)) {
      case "mainhand", "main_hand" -> EquipmentSlot.MAINHAND;
      case "offhand", "off_hand" -> EquipmentSlot.OFFHAND;
      case "head", "helmet" -> EquipmentSlot.HEAD;
      case "chest", "chestplate" -> EquipmentSlot.CHEST;
      case "legs", "leggings" -> EquipmentSlot.LEGS;
      case "feet", "boots" -> EquipmentSlot.FEET;
      default -> throw invalid(
        "equipment_slot must be mainhand, offhand, head, chest, legs, or feet"
      );
    };
    var destinationArea = request.hasDestinationArea()
      ? request.getDestinationArea()
      : InventoryArea.INVENTORY_AREA_PLAYER;
    if (destinationArea != InventoryArea.INVENTORY_AREA_MAIN
      && destinationArea != InventoryArea.INVENTORY_AREA_HOTBAR
      && destinationArea != InventoryArea.INVENTORY_AREA_PLAYER) {
      throw invalid("destination_area must be MAIN, HOTBAR, or PLAYER");
    }
    if (!(context.menu instanceof InventoryMenu)) {
      context.player.closeContainer();
      context.refresh();
    }
    var source = switch (slot) {
      case MAINHAND -> selectedHotbarSlot(context);
      case HEAD -> context.menu.getSlot(5);
      case CHEST -> context.menu.getSlot(6);
      case LEGS -> context.menu.getSlot(7);
      case FEET -> context.menu.getSlot(8);
      case OFFHAND -> context.menu.getSlot(45);
      default -> throw new AssertionError("Unexpected equipment slot");
    };
    if (source.getItem().isEmpty()) {
      return;
    }
    var destination = context.menu.slots.stream()
      .filter(candidate -> candidate.index != source.index)
      .filter(candidate -> candidate.getItem().isEmpty())
      .filter(candidate -> isUnequipDestination(
        context,
        candidate,
        destinationArea
      ))
      .filter(candidate -> candidate.mayPlace(source.getItem()))
      .findFirst()
      .orElseThrow(() -> Status.RESOURCE_EXHAUSTED
        .withDescription(
          "No empty destination slot is available for the equipped item"
        )
        .asRuntimeException());
    moveExact(context, source, destination, source.getItem().getCount());
  }

  private static Slot selectedHotbarSlot(Context context) {
    var hotbar = context.menu.slots.stream()
      .filter(slot -> area(context.layout, slot.index)
        == InventoryArea.INVENTORY_AREA_HOTBAR)
      .sorted(Comparator.comparingInt(slot -> slot.index))
      .toList();
    var selected = context.player.getInventory().getSelectedSlot();
    if (selected < 0 || selected >= hotbar.size()) {
      throw Status.FAILED_PRECONDITION
        .withDescription("The selected hotbar slot is unavailable")
        .asRuntimeException();
    }
    return hotbar.get(selected);
  }

  private static boolean isUnequipDestination(
    Context context,
    Slot slot,
    InventoryArea requested
  ) {
    var actual = area(context.layout, slot.index);
    return switch (requested) {
      case INVENTORY_AREA_MAIN ->
        actual == InventoryArea.INVENTORY_AREA_MAIN;
      case INVENTORY_AREA_HOTBAR ->
        actual == InventoryArea.INVENTORY_AREA_HOTBAR;
      case INVENTORY_AREA_PLAYER ->
        actual == InventoryArea.INVENTORY_AREA_MAIN
          || actual == InventoryArea.INVENTORY_AREA_HOTBAR;
      default -> false;
    };
  }

  private static void swap(Context context, Slot first, Slot second) {
    var firstItem = first.getItem();
    var secondItem = second.getItem();
    if (!first.mayPickup(context.player)
      || !second.mayPickup(context.player)
      || !second.mayPlace(firstItem)
      || !secondItem.isEmpty() && !first.mayPlace(secondItem)) {
      throw Status.FAILED_PRECONDITION
        .withDescription("The requested slots do not allow this swap")
        .asRuntimeException();
    }
    click(context, first.index, 0, ContainerInput.PICKUP);
    click(context, second.index, 0, ContainerInput.PICKUP);
    if (!context.menu.getCarried().isEmpty()) {
      click(context, first.index, 0, ContainerInput.PICKUP);
    }
  }

  private static void click(
    Context context,
    int slot,
    int button,
    ContainerInput input
  ) {
    context.gameMode.handleContainerInput(
      context.menu.containerId,
      slot,
      button,
      input,
      context.player
    );
  }

  private static Slot requireSlot(
    AbstractContainerMenu menu,
    int index,
    String field
  ) {
    if (index < 0 || index >= menu.slots.size()) {
      throw invalid(
        "%s must be between zero and %d".formatted(
          field,
          menu.slots.size() - 1
        )
      );
    }
    return menu.getSlot(index);
  }

  static void requireMenu(
    AbstractContainerMenu menu,
    TransferItemsRequest request
  ) {
    if (request.hasMenuId() && menuId(menu) != request.getMenuId()) {
      throw Status.ABORTED
        .withDescription("The open menu changed before the transfer")
        .asRuntimeException();
    }
  }

  private static void requireRevision(Context context, long expected) {
    if (expected == 0) {
      return;
    }
    var current = revision(context);
    if (current != expected) {
      throw Status.ABORTED
        .withDescription(
          "Container revision changed; current revision is "
            + Long.toUnsignedString(current)
        )
        .asRuntimeException();
    }
  }

  private static void requireEmptyCursor(Context context) {
    if (!context.menu.getCarried().isEmpty()) {
      throw Status.FAILED_PRECONDITION
        .withDescription(
          "Semantic inventory operations require an empty cursor"
        )
        .asRuntimeException();
    }
  }

  private static boolean successful(ControlStopReason reason) {
    return reason == ControlStopReason.COMPLETED
      || reason == ControlStopReason.CLAIMED;
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw invalid(field + " must be a UUID");
    }
  }

  private static RuntimeException invalid(String description) {
    return Status.INVALID_ARGUMENT
      .withDescription(description)
      .asRuntimeException();
  }

  private static String fingerprint(byte[] request) {
    return Base64.getEncoder().encodeToString(request);
  }

  private static RuntimeException toGrpcError(Throwable throwable) {
    var cause = unwrap(throwable);
    if (cause instanceof StatusRuntimeException statusError) {
      return statusError;
    }
    if (cause instanceof TimeoutException) {
      return Status.DEADLINE_EXCEEDED
        .withDescription("Inventory operation timed out")
        .withCause(cause)
        .asRuntimeException();
    }
    return Status.INTERNAL
      .withDescription(Objects.requireNonNullElse(
        cause == null ? null : cause.getMessage(),
        cause == null ? "Unknown inventory error" : cause.getClass().getSimpleName()
      ))
      .withCause(cause)
      .asRuntimeException();
  }

  private static @Nullable Throwable unwrap(@Nullable Throwable throwable) {
    var current = throwable;
    while ((current instanceof CompletionException
      || current instanceof ExecutionException)
      && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  @FunctionalInterface
  private interface InventoryRead<T> {
    T apply(Context context);
  }

  @FunctionalInterface
  private interface InventoryMutation {
    void apply(Context context);
  }

  private record Move(int source, int destination, int count) {}

  private record PlannedMoves(List<Move> moves, int transferred) {}

  private record TaskBatchPlan(
    List<Move> moves,
    List<Integer> transferred
  ) {}

  private record RankedItem(
    Slot slot,
    double score,
    List<InventoryItemScoreFactor> factors
  ) {}

  private static final class SimulatedSlot {
    private final Slot slot;
    private ItemStack stack;
    private int count;

    private SimulatedSlot(Slot slot, ItemStack stack, int count) {
      this.slot = slot;
      this.stack = stack;
      this.count = count;
    }
  }

  private static final class Context {
    private final BotConnection bot;
    private net.minecraft.client.player.LocalPlayer player;
    private net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode;
    private AbstractContainerMenu menu;
    private ContainerLayout layout;

    private Context(BotConnection bot) {
      this.bot = bot;
      refresh();
    }

    private static Context create(BotConnection bot) {
      return new Context(bot);
    }

    private void refresh() {
      player = Objects.requireNonNull(
        bot.minecraft().player,
        "Bot player is not available"
      );
      gameMode = Objects.requireNonNull(
        bot.minecraft().gameMode,
        "Bot game mode is not available"
      );
      menu = player.containerMenu;
      layout = BotServiceImpl.buildContainerLayout(
        menu,
        BotServiceImpl.getContainerTitle(menu, bot.minecraft())
      );
    }
  }

  private static final class OpenContainerTask implements ControlTask {
    private final BotConnection bot;
    private final BlockPosition requestedPosition;
    private final AtomicReference<ContainerSnapshot> result;
    private boolean interacted;
    private int initialContainerId;
    private int elapsedTicks;
    private int contentsTicks;
    private boolean done;

    private OpenContainerTask(
      BotConnection bot,
      BlockPosition requestedPosition,
      AtomicReference<ContainerSnapshot> result
    ) {
      this.bot = bot;
      this.requestedPosition = requestedPosition;
      this.result = result;
    }

    @Override
    public void tick() {
      var context = Context.create(bot);
      if (!interacted) {
        var level = Objects.requireNonNull(
          bot.minecraft().level,
          "Bot level is not available"
        );
        var dimension = level.dimension().identifier().toString();
        if (!requestedPosition.getDimension().isBlank()
          && !requestedPosition.getDimension().equals(dimension)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Container position is in another dimension")
            .asRuntimeException();
        }
        var position = new BlockPos(
          requestedPosition.getX(),
          requestedPosition.getY(),
          requestedPosition.getZ()
        );
        if (!level.getChunkSource().hasChunk(
          position.getX() >> 4,
          position.getZ() >> 4
        )) {
          throw Status.NOT_FOUND
            .withDescription("Container position is not loaded")
            .asRuntimeException();
        }
        if (!context.player.isWithinBlockInteractionRange(position, 0)) {
          throw Status.FAILED_PRECONDITION
            .withDescription("Container is outside interaction range")
            .asRuntimeException();
        }
        initialContainerId = context.menu.containerId;
        context.gameMode.useItemOn(
          context.player,
          InteractionHand.MAIN_HAND,
          new BlockHitResult(
            Vec3.atCenterOf(position),
            Direction.UP,
            position,
            false
          )
        );
        interacted = true;
        return;
      }

      if (context.menu.containerId != initialContainerId
        && !(context.menu instanceof InventoryMenu)) {
        // The slots arrive in a packet after the screen opens. A snapshot
        // taken before that shows an empty container, and its revision is
        // stale as soon as they arrive (the first transfer then aborts).
        if (
          !SFInventoryHelpers.hasReceivedContents(context.menu)
            && ++contentsTicks < SFInventoryHelpers.MENU_CONTENTS_GRACE_TICKS
        ) {
          return;
        }
        result.set(snapshot(context));
        done = true;
        return;
      }
      if (++elapsedTicks >= 100) {
        throw Status.DEADLINE_EXCEEDED
          .withDescription("Server did not open a container")
          .asRuntimeException();
      }
    }

    @Override
    public boolean isDone() {
      return done;
    }

    @Override
    public Set<ControlResource> resources() {
      return Set.of(
        ControlResource.ROTATION,
        ControlResource.MAIN_HAND,
        ControlResource.INVENTORY,
        ControlResource.CONTAINER
      );
    }

    @Override
    public String description() {
      return "SDK open block container";
    }
  }
}
