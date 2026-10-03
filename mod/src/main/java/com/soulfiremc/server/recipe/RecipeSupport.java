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
package com.soulfiremc.server.recipe;

import com.soulfiremc.mod.mixin.soulfire.ClientRecipeBookAccessor;
import com.soulfiremc.server.bot.BotConnection;
import io.grpc.Status;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/// Shared recipe-book lookup and station rules for RPCs and durable tasks.
public final class RecipeSupport {
  private RecipeSupport() {
  }

  public static List<RecipeDisplayEntry> recipes(BotConnection bot) {
    var player = Objects.requireNonNull(
      bot.minecraft().player,
      "Bot player is not available"
    );
    var byId = new LinkedHashMap<Integer, RecipeDisplayEntry>();
    ((ClientRecipeBookAccessor) player.getRecipeBook())
      .soulfire$getKnownRecipes()
      .values()
      .stream()
      .forEach(entry -> byId.put(entry.id().index(), entry));
    return List.copyOf(byId.values());
  }

  public static RecipeDisplayEntry find(
    BotConnection bot,
    String requestedId
  ) {
    int id;
    try {
      var normalized = requestedId.startsWith("display:")
        ? requestedId.substring("display:".length())
        : requestedId;
      id = Integer.parseInt(normalized);
    } catch (NumberFormatException exception) {
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "recipe_id must be a display ID such as display:42"
        )
        .withCause(exception)
        .asRuntimeException();
    }
    return recipes(bot).stream()
      .filter(entry -> entry.id().index() == id)
      .findFirst()
      .orElseThrow(() -> Status.NOT_FOUND
        .withDescription("Recipe is not known to this bot: display:" + id)
        .asRuntimeException());
  }

  public static boolean isCraftingRecipe(RecipeDisplayEntry entry) {
    return entry.display() instanceof ShapedCraftingRecipeDisplay
      || entry.display() instanceof ShapelessCraftingRecipeDisplay;
  }

  /// The display type ListRecipes reports, such as "minecraft:crafting_shaped".
  public static String type(RecipeDisplayEntry entry) {
    return BuiltInRegistries.RECIPE_DISPLAY.getKey(entry.display().type())
      .toString();
  }

  public static boolean canCraftInInventory(RecipeDisplayEntry entry) {
    return switch (entry.display()) {
      case ShapedCraftingRecipeDisplay shaped ->
        shaped.width() <= 2 && shaped.height() <= 2;
      case ShapelessCraftingRecipeDisplay shapeless ->
        shapeless.ingredients().size() <= 4;
      default -> false;
    };
  }

  public static String requiredStation(
    BotConnection bot,
    RecipeDisplayEntry entry
  ) {
    if (canCraftInInventory(entry)) {
      return "";
    }
    var stack = entry.display()
      .craftingStation()
      .resolveForFirstStack(displayContext(bot));
    return stack.isEmpty() ? "" : stack.typeHolder().getRegisteredName();
  }

  public static ItemStack result(
    BotConnection bot,
    RecipeDisplayEntry entry
  ) {
    return entry.display().result().resolveForFirstStack(displayContext(bot));
  }

  public static ContextMap displayContext(BotConnection bot) {
    return SlotDisplayContext.fromLevel(Objects.requireNonNull(
      bot.minecraft().level,
      "Bot level is not available"
    ));
  }
}
