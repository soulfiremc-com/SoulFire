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
package com.soulfiremc.server.automation;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class AutomationRecipes {
  private AutomationRecipes() {
  }

  public static Optional<CraftingRecipeDefinition> craftingRecipe(String targetKey) {
    return switch (targetKey) {
      case AutomationRequirements.ANY_PLANKS -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        4,
        CraftingStation.INVENTORY,
        List.of(new IngredientPlacement(1, AutomationRequirements.ANY_LOG))
      ));
      case "item:minecraft:stick" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        4,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(2, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(4, AutomationRequirements.ANY_PLANKS)
        )
      ));
      case "item:minecraft:crafting_table" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(2, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(3, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(4, AutomationRequirements.ANY_PLANKS)
        )
      ));
      case "item:minecraft:wooden_pickaxe" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(2, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(3, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(5, "item:minecraft:stick"),
          new IngredientPlacement(8, "item:minecraft:stick")
        )
      ));
      case "item:minecraft:stone_pickaxe" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, "item:minecraft:cobblestone"),
          new IngredientPlacement(2, "item:minecraft:cobblestone"),
          new IngredientPlacement(3, "item:minecraft:cobblestone"),
          new IngredientPlacement(5, "item:minecraft:stick"),
          new IngredientPlacement(8, "item:minecraft:stick")
        )
      ));
      case "item:minecraft:iron_pickaxe" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, "item:minecraft:iron_ingot"),
          new IngredientPlacement(2, "item:minecraft:iron_ingot"),
          new IngredientPlacement(3, "item:minecraft:iron_ingot"),
          new IngredientPlacement(5, "item:minecraft:stick"),
          new IngredientPlacement(8, "item:minecraft:stick")
        )
      ));
      case "item:minecraft:furnace" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, "item:minecraft:cobblestone"),
          new IngredientPlacement(2, "item:minecraft:cobblestone"),
          new IngredientPlacement(3, "item:minecraft:cobblestone"),
          new IngredientPlacement(4, "item:minecraft:cobblestone"),
          new IngredientPlacement(6, "item:minecraft:cobblestone"),
          new IngredientPlacement(7, "item:minecraft:cobblestone"),
          new IngredientPlacement(8, "item:minecraft:cobblestone"),
          new IngredientPlacement(9, "item:minecraft:cobblestone")
        )
      ));
      case "item:minecraft:blaze_powder" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        2,
        CraftingStation.INVENTORY,
        List.of(new IngredientPlacement(1, "item:minecraft:blaze_rod"))
      ));
      case "item:minecraft:ender_eye" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, "item:minecraft:ender_pearl"),
          new IngredientPlacement(2, "item:minecraft:blaze_powder")
        )
      ));
      case "item:minecraft:flint_and_steel" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, "item:minecraft:iron_ingot"),
          new IngredientPlacement(4, "item:minecraft:flint")
        )
      ));
      case "item:minecraft:gold_ingot" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, "item:minecraft:gold_nugget"),
          new IngredientPlacement(2, "item:minecraft:gold_nugget"),
          new IngredientPlacement(3, "item:minecraft:gold_nugget"),
          new IngredientPlacement(4, "item:minecraft:gold_nugget"),
          new IngredientPlacement(5, "item:minecraft:gold_nugget"),
          new IngredientPlacement(6, "item:minecraft:gold_nugget"),
          new IngredientPlacement(7, "item:minecraft:gold_nugget"),
          new IngredientPlacement(8, "item:minecraft:gold_nugget"),
          new IngredientPlacement(9, "item:minecraft:gold_nugget")
        )
      ));
      case "item:minecraft:bed" -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, "item:minecraft:white_wool"),
          new IngredientPlacement(2, "item:minecraft:white_wool"),
          new IngredientPlacement(3, "item:minecraft:white_wool"),
          new IngredientPlacement(4, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(5, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(6, AutomationRequirements.ANY_PLANKS)
        )
      ));
      default -> Optional.empty();
    };
  }

  public static Optional<SmeltingRecipeDefinition> smeltingRecipe(String targetKey) {
    return switch (targetKey) {
      case "item:minecraft:iron_ingot" -> Optional.of(new SmeltingRecipeDefinition(
        targetKey,
        1,
        List.of("item:minecraft:raw_iron", "item:minecraft:iron_ore")
      ));
      case "item:minecraft:gold_ingot" -> Optional.of(new SmeltingRecipeDefinition(
        targetKey,
        1,
        List.of("item:minecraft:raw_gold", "item:minecraft:gold_ore")
      ));
      case "item:minecraft:charcoal" -> Optional.of(new SmeltingRecipeDefinition(
        targetKey,
        1,
        List.of(AutomationRequirements.ANY_LOG)
      ));
      default -> Optional.empty();
    };
  }

  public static Optional<MineableSource> mineableSource(String targetKey) {
    return switch (targetKey) {
      case AutomationRequirements.ANY_LOG -> Optional.of(new MineableSource(
        state -> state.is(net.minecraft.tags.BlockTags.LOGS),
        ToolRequirement.NONE
      ));
      case "item:minecraft:cobblestone" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.STONE || state.getBlock() == Blocks.COBBLESTONE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case "item:minecraft:coal" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.COAL_ORE || state.getBlock() == Blocks.DEEPSLATE_COAL_ORE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case "item:minecraft:raw_iron", "item:minecraft:iron_ore" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.IRON_ORE || state.getBlock() == Blocks.DEEPSLATE_IRON_ORE,
        ToolRequirement.STONE_PICKAXE
      ));
      case "item:minecraft:raw_gold", "item:minecraft:gold_ore" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.GOLD_ORE || state.getBlock() == Blocks.DEEPSLATE_GOLD_ORE,
        ToolRequirement.IRON_PICKAXE
      ));
      case "item:minecraft:gold_nugget" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.NETHER_GOLD_ORE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case "item:minecraft:flint" -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.GRAVEL,
        ToolRequirement.NONE
      ));
      default -> Optional.empty();
    };
  }

  public static Optional<EntitySource> entitySource(String targetKey) {
    return switch (targetKey) {
      case AutomationRequirements.FOOD -> Optional.of(new EntitySource(
        entity -> entity.isAnimal(),
        1
      ));
      case "item:minecraft:blaze_rod" -> Optional.of(new EntitySource(
        entity -> entity.isBlaze(),
        1
      ));
      case "item:minecraft:ender_pearl" -> Optional.of(new EntitySource(
        entity -> entity.type() == net.minecraft.world.entity.EntityType.ENDERMAN,
        1
      ));
      case "item:minecraft:white_wool" -> Optional.of(new EntitySource(
        entity -> entity.type() == net.minecraft.world.entity.EntityType.SHEEP,
        1
      ));
      default -> Optional.empty();
    };
  }

  public enum CraftingStation {
    INVENTORY,
    CRAFTING_TABLE,
    FURNACE
  }

  public enum ToolRequirement {
    NONE,
    WOOD_PICKAXE,
    STONE_PICKAXE,
    IRON_PICKAXE
  }

  public record IngredientPlacement(int slot, String requirementKey) {
  }

  public record CraftingRecipeDefinition(
    String outputKey,
    int outputCount,
    CraftingStation station,
    List<IngredientPlacement> ingredients
  ) {
  }

  public record SmeltingRecipeDefinition(
    String outputKey,
    int outputCount,
    List<String> inputKeys
  ) {
  }

  public record MineableSource(
    Predicate<BlockState> predicate,
    ToolRequirement requiredTool
  ) {
  }

  public record EntitySource(
    Predicate<AutomationWorldMemory.RememberedEntity> predicate,
    int expectedDrops
  ) {
  }
}
