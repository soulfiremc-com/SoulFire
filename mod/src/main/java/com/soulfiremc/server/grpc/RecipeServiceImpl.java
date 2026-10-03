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
import com.soulfiremc.server.bot.BotThreadExecution;
import com.soulfiremc.server.recipe.RecipeSupport;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.SFInventoryHelpers;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/// Recipes known to an online bot and exact craftability against its inventory.
public final class RecipeServiceImpl
  extends RecipeServiceGrpc.RecipeServiceImplBase {
  private static final int MAX_PAGE_SIZE = 1_000;

  private final SoulFireServer server;

  public RecipeServiceImpl(SoulFireServer server) {
    this.server = server;
  }

  @Override
  public void listRecipes(
    ListRecipesRequest request,
    StreamObserver<ListRecipesResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      var bot = requireBot(request.getScope());
      return BotThreadExecution.call(bot, () -> {
        var recipes = RecipeSupport.recipes(bot);
        var snapshots = recipes.stream()
          .map(entry -> snapshot(bot, entry))
          .filter(recipe -> !request.hasResultItemId()
            || recipe.getResult().getItemId().equals(
            normalizeIdentifier(request.getResultItemId())
          ))
          .filter(recipe -> request.getRecipeTypesList().isEmpty()
            || request.getRecipeTypesList().stream()
            .map(RecipeServiceImpl::normalizeIdentifier)
            .anyMatch(recipe.getRecipeType()::equals))
          .filter(recipe -> !request.hasIngredient()
            || recipe.getIngredientsList().stream()
            .flatMap(choice -> choice.getItemIdsList().stream())
            .map(RecipeServiceImpl::stack)
            .anyMatch(item -> InventoryServiceImpl.matches(
              item,
              request.getIngredient()
            )))
          .sorted(Comparator.comparing(RecipeSnapshot::getRecipeId))
          .toList();
        var page = page(
          snapshots,
          request.getPageSize(),
          request.getPageToken()
        );
        return ListRecipesResponse.newBuilder()
          .addAllRecipes(page.values)
          .setNextPageToken(page.nextToken)
          .setRegistryHash(hash(snapshots))
          .build();
      });
    });
  }

  @Override
  public void canCraft(
    CanCraftRequest request,
    StreamObserver<CanCraftResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      var bot = requireBot(request.getScope());
      return BotThreadExecution.call(bot, () -> {
        var entry = RecipeSupport.find(bot, request.getRecipeId());
        var requested = request.getCount() <= 0 ? 1 : request.getCount();
        var requirements = entry.craftingRequirements()
          .orElseThrow(() -> Status.FAILED_PRECONDITION
            .withDescription(
              "This special recipe cannot be planned from fixed ingredients"
            )
            .asRuntimeException());
        var player = Objects.requireNonNull(
          bot.minecraft().player,
          "Bot player is not available"
        );
        var menu = player.containerMenu;
        var inventory = SFInventoryHelpers.playerInventorySlots(menu)
          .mapToObj(slot -> menu.getSlot(slot).getItem())
          .toList();
        var contents = new StackedContents<Holder<Item>>();
        for (var stack : inventory) {
          if (!stack.isEmpty()) {
            contents.account(stack.typeHolder(), stack.getCount());
          }
        }
        var maximum = contents.tryPickAll(
          requirements,
          Integer.MAX_VALUE,
          null
        );
        var response = CanCraftResponse.newBuilder()
          .setCanCraft(maximum >= requested)
          .setMaximumCraftCount(maximum);
        var station = station(bot, entry);
        if (station != null) {
          response.setRequiredStation(station);
        }
        if (maximum < requested) {
          for (var ingredient : groupIngredients(requirements)) {
            var available = available(inventory, ingredient);
            var needed = Math.multiplyExact(ingredient.count, requested);
            if (available < needed) {
              response.addMissing(MissingIngredient.newBuilder()
                .setIngredient(ingredient.toProto())
                .setAvailable(available)
                .setMissing(needed - available));
            }
          }
        }
        return response.build();
      });
    });
  }

  @Override
  public void listVillagerTrades(
    ListVillagerTradesRequest request,
    StreamObserver<ListVillagerTradesResponse> responseObserver
  ) {
    unary(responseObserver, () -> {
      var bot = requireBot(request.getScope());
      return BotThreadExecution.call(bot, () -> {
        var player = Objects.requireNonNull(
          bot.minecraft().player,
          "Bot player is not available"
        );
        if (!(player.containerMenu instanceof MerchantMenu menu)) {
          throw Status.FAILED_PRECONDITION
            .withDescription(
              "Open a villager merchant menu before listing its trades"
            )
            .asRuntimeException();
        }
        var response = ListVillagerTradesResponse.newBuilder()
          .setVillagerLevel(menu.getTraderLevel())
          .setVillagerExperience(menu.getTraderXp())
          .setCanRestock(menu.canRestock())
          .setContainerId(menu.containerId)
          .setContainerRevision(menu.getStateId());
        var offers = menu.getOffers();
        for (var index = 0; index < offers.size(); index++) {
          var offer = offers.get(index);
          var snapshot = VillagerTradeOfferSnapshot.newBuilder()
            .setOfferIndex(index)
            .setFirstCost(MinecraftDomainMapper.item(offer.getCostA()))
            .setResult(MinecraftDomainMapper.item(offer.getResult()))
            .setUses(offer.getUses())
            .setMaximumUses(offer.getMaxUses())
            .setOutOfStock(offer.isOutOfStock())
            .setRewardsExperience(offer.shouldRewardExp())
            .setVillagerExperience(offer.getXp())
            .setDemand(offer.getDemand())
            .setSpecialPrice(offer.getSpecialPriceDiff())
            .setPriceMultiplier(offer.getPriceMultiplier());
          var secondCost = offer.getCostB();
          if (!secondCost.isEmpty()) {
            snapshot.setSecondCost(MinecraftDomainMapper.item(secondCost));
          }
          response.addOffers(snapshot);
        }
        return response.build();
      });
    });
  }

  private BotConnection requireBot(InventoryScope scope) {
    if (scope.getInstanceId().isBlank() || scope.getBotId().isBlank()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("scope.instance_id and scope.bot_id are required")
        .asRuntimeException();
    }
    var instanceId = parseUuid(scope.getInstanceId(), "scope.instance_id");
    var botId = parseUuid(scope.getBotId(), "scope.bot_id");
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_BOT_INFO, instanceId)
    );
    var instance = server.getInstance(instanceId)
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Instance not found: " + instanceId)
        .asRuntimeException());
    var bot = instance.botConnections().get(botId);
    if (bot == null || bot.isDisconnected() || bot.minecraft().level == null) {
      throw Status.FAILED_PRECONDITION
        .withDescription("Bot must be online to access recipes")
        .asRuntimeException();
    }
    return bot;
  }

  private static RecipeSnapshot snapshot(
    BotConnection bot,
    RecipeDisplayEntry entry
  ) {
    var display = entry.display();
    var context = RecipeSupport.displayContext(bot);
    var result = display.result().resolveForFirstStack(context);
    var builder = RecipeSnapshot.newBuilder()
      .setRecipeId("display:" + entry.id().index())
      .setRecipeType(RecipeSupport.type(entry))
      .setResult(MinecraftDomainMapper.item(result))
      .setSpecial(entry.craftingRequirements().isEmpty());
    entry.group().ifPresent(group -> builder.setGroup(Integer.toString(group)));
    entry.craftingRequirements()
      .map(RecipeServiceImpl::groupIngredients)
      .orElseGet(List::of)
      .forEach(ingredient -> builder.addIngredients(ingredient.toProto()));
    if (display instanceof FurnaceRecipeDisplay furnace) {
      builder
        .setExperience(furnace.experience())
        .setCookingTimeTicks(furnace.duration());
    }
    return builder.build();
  }

  private static @org.checkerframework.checker.nullness.qual.Nullable String
  station(BotConnection bot, RecipeDisplayEntry entry) {
    var station = RecipeSupport.requiredStation(bot, entry);
    return station.isBlank() ? null : station;
  }

  private static List<GroupedIngredient> groupIngredients(
    List<Ingredient> requirements
  ) {
    var grouped = new LinkedHashMap<String, GroupedIngredient>();
    for (var ingredient : requirements) {
      var ids = ingredient.items()
        .map(holder -> holder.getRegisteredName())
        .sorted()
        .toList();
      var key = String.join("\u0000", ids);
      grouped.compute(key, (_, existing) -> existing == null
        ? new GroupedIngredient(ids, 1)
        : new GroupedIngredient(ids, existing.count + 1));
    }
    return List.copyOf(grouped.values());
  }

  private static int available(
    List<ItemStack> inventory,
    GroupedIngredient ingredient
  ) {
    return inventory.stream()
      .filter(stack -> ingredient.itemIds.contains(
        stack.typeHolder().getRegisteredName()
      ))
      .mapToInt(ItemStack::getCount)
      .sum();
  }

  private static ItemStack stack(String itemId) {
    var item = BuiltInRegistries.ITEM.getValue(
      Identifier.parse(normalizeIdentifier(itemId))
    );
    return item == null ? ItemStack.EMPTY : new ItemStack(item);
  }

  private static String normalizeIdentifier(String value) {
    return value.indexOf(':') < 0 ? "minecraft:" + value : value;
  }

  private static <T> Page<T> page(
    List<T> values,
    int requestedSize,
    String token
  ) {
    var pageSize = requestedSize <= 0
      ? 100
      : Math.min(requestedSize, MAX_PAGE_SIZE);
    var offset = decodePageToken(token);
    if (offset > values.size()) {
      throw Status.INVALID_ARGUMENT
        .withDescription("page_token is outside the current result set")
        .asRuntimeException();
    }
    var end = Math.min(values.size(), offset + pageSize);
    return new Page<>(
      values.subList(offset, end),
      end < values.size() ? encodePageToken(end) : ""
    );
  }

  private static int decodePageToken(String token) {
    if (token.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(new String(
        Base64.getUrlDecoder().decode(token),
        StandardCharsets.UTF_8
      ));
    } catch (IllegalArgumentException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Invalid page_token")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static String encodePageToken(int offset) {
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        Integer.toString(offset).getBytes(StandardCharsets.UTF_8)
      );
  }

  private static String hash(List<RecipeSnapshot> recipes) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var recipe : recipes) {
        digest.update(recipe.toByteArray());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }

  private static UUID parseUuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription(field + " must be a UUID")
        .withCause(exception)
        .asRuntimeException();
    }
  }

  private static <T> void unary(
    StreamObserver<T> observer,
    Callable<T> action
  ) {
    try {
      observer.onNext(action.call());
      observer.onCompleted();
    } catch (Throwable throwable) {
      observer.onError(toGrpcError(throwable));
    }
  }

  private static RuntimeException toGrpcError(Throwable throwable) {
    if (throwable instanceof StatusRuntimeException statusError) {
      return statusError;
    }
    return Status.INTERNAL
      .withDescription(Objects.requireNonNullElse(
        throwable.getMessage(),
        throwable.getClass().getSimpleName()
      ))
      .withCause(throwable)
      .asRuntimeException();
  }

  private record Page<T>(List<T> values, String nextToken) {}

  private record GroupedIngredient(List<String> itemIds, int count) {
    private IngredientChoice toProto() {
      return IngredientChoice.newBuilder()
        .addAllItemIds(itemIds)
        .setCount(count)
        .build();
    }
  }
}
