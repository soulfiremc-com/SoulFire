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

import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class CraftTaskProviderTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @Test
  void acceptsAnIngredientWithPlayerSpecificComponents() {
    var accepted = itemStack(Items.COBBLESTONE);
    var renamed = itemStack(Items.COBBLESTONE);
    renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Building stone"));

    assertTrue(CraftTaskProvider.ingredientMatches(
      List.of(accepted),
      renamed
    ));
  }

  @Test
  void rejectsAStackOfAnotherItem() {
    assertFalse(CraftTaskProvider.ingredientMatches(
      List.of(itemStack(Items.COBBLESTONE)),
      itemStack(Items.DIRT)
    ));
  }

  @Test
  void settlesOnceTheStateIdStaysTheSame() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.restart(menu);

    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  @Test
  void waitsForTheServersAnswerToGridClicks() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.awaitAnswer(menu);

    for (var tick = 0; tick < 10; tick++) {
      assertFalse(sync.settled(menu));
    }
    when(menu.getStateId()).thenReturn(6);
    assertFalse(sync.settled(menu));
    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  @Test
  void waitsForTheStateIdToSettleAgainAfterARestart() {
    var menu = mock(AbstractContainerMenu.class);
    when(menu.getStateId()).thenReturn(5);
    var sync = new CraftTaskProvider.MenuSync();
    sync.awaitAnswer(menu);
    when(menu.getStateId()).thenReturn(6);
    for (var tick = 0; tick < 3; tick++) {
      sync.settled(menu);
    }
    assertTrue(sync.settled(menu));

    sync.restart(menu);
    assertFalse(sync.settled(menu));
    assertTrue(sync.settled(menu));
  }

  @Test
  void acceptsAsManyCraftsAsTheIngredientsCover() {
    var cobblestone = Holder.direct(Items.COBBLESTONE, DataComponentMap.EMPTY);
    var available = List.of(new ItemStack(cobblestone, 12));

    assertDoesNotThrow(() -> CraftTaskProvider.requireIngredients(
      stairsIngredients(cobblestone),
      available,
      2,
      "display:268"
    ));
  }

  @Test
  void refusesMoreCraftsThanTheIngredientsCover() {
    var cobblestone = Holder.direct(Items.COBBLESTONE, DataComponentMap.EMPTY);
    var available = List.of(new ItemStack(cobblestone, 12));

    var error = assertThrows(
      StatusRuntimeException.class,
      () -> CraftTaskProvider.requireIngredients(
        stairsIngredients(cobblestone),
        available,
        3,
        "display:268"
      )
    );
    assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    assertEquals(
      "Not enough ingredients for display:268: count is 3, the bot has enough for 2",
      error.getStatus().getDescription()
    );
  }

  @Test
  void countsEveryAlternativeOfAnIngredient() {
    var coal = Holder.direct(Items.COAL, DataComponentMap.EMPTY);
    var charcoal = Holder.direct(Items.CHARCOAL, DataComponentMap.EMPTY);
    var stick = Holder.direct(Items.STICK, DataComponentMap.EMPTY);
    var torch = List.of(
      Ingredient.of(HolderSet.direct(coal, charcoal)),
      Ingredient.of(HolderSet.direct(stick))
    );
    var available = List.of(
      new ItemStack(coal, 1),
      new ItemStack(charcoal, 1),
      new ItemStack(stick, 2)
    );

    assertDoesNotThrow(() ->
      CraftTaskProvider.requireIngredients(torch, available, 2, "display:1"));
    assertThrows(
      StatusRuntimeException.class,
      () -> CraftTaskProvider.requireIngredients(torch, available, 3, "display:1")
    );
  }

  @Test
  void checksEverySetOfIngredients() {
    var iron = Holder.direct(Items.IRON_INGOT, DataComponentMap.EMPTY);
    var flint = Holder.direct(Items.FLINT, DataComponentMap.EMPTY);
    var ingredients = List.of(
      Ingredient.of(HolderSet.direct(iron)),
      Ingredient.of(HolderSet.direct(iron)),
      Ingredient.of(HolderSet.direct(flint))
    );
    var available = List.of(new ItemStack(iron, 2), new ItemStack(flint, 10));

    assertDoesNotThrow(() ->
      CraftTaskProvider.requireIngredients(ingredients, available, 1, "display:2"));
    assertThrows(
      StatusRuntimeException.class,
      () -> CraftTaskProvider.requireIngredients(ingredients, available, 2, "display:2")
    );
  }

  private static List<Ingredient> stairsIngredients(Holder<Item> cobblestone) {
    return Collections.nCopies(6, Ingredient.of(HolderSet.direct(cobblestone)));
  }

  @Test
  void namesTheTypeOfARecipeItCannotCraft() {
    var cutter = recipe(269, RecipeBookCategories.STONECUTTER, new StonecutterRecipeDisplay(
      new SlotDisplay.ItemSlotDisplay(Items.COBBLESTONE),
      new SlotDisplay.ItemSlotDisplay(Items.COBBLESTONE_STAIRS),
      new SlotDisplay.ItemSlotDisplay(Items.STONECUTTER)
    ));

    var error = assertThrows(
      StatusRuntimeException.class,
      () -> CraftTaskProvider.requireCraftingRecipe(cutter)
    );
    assertEquals(Status.Code.FAILED_PRECONDITION, error.getStatus().getCode());
    var description = error.getStatus().getDescription();
    assertTrue(description.startsWith("display:269 is a minecraft:stonecutter recipe"), description);
  }

  @Test
  void acceptsAShapedRecipe() {
    var cobblestone = new SlotDisplay.ItemSlotDisplay(Items.COBBLESTONE);
    var shaped = recipe(268, RecipeBookCategories.CRAFTING_BUILDING_BLOCKS, new ShapedCraftingRecipeDisplay(
      1,
      1,
      List.of(cobblestone),
      new SlotDisplay.ItemSlotDisplay(Items.COBBLESTONE_STAIRS),
      new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)
    ));

    assertDoesNotThrow(() -> CraftTaskProvider.requireCraftingRecipe(shaped));
  }

  private static RecipeDisplayEntry recipe(
    int id,
    RecipeBookCategory category,
    RecipeDisplay display
  ) {
    return new RecipeDisplayEntry(
      new RecipeDisplayId(id),
      display,
      OptionalInt.empty(),
      category,
      Optional.empty()
    );
  }

  private static ItemStack itemStack(Item item) {
    return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), 1);
  }
}
