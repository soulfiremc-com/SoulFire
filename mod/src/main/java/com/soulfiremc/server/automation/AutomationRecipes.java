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

import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
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
      case AutomationRequirements.STICK -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        4,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(2, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(4, AutomationRequirements.ANY_PLANKS)
        )
      ));
      case AutomationRequirements.CRAFTING_TABLE -> Optional.of(new CraftingRecipeDefinition(
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
      case AutomationRequirements.WOODEN_PICKAXE -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(2, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(3, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(5, AutomationRequirements.STICK),
          new IngredientPlacement(8, AutomationRequirements.STICK)
        )
      ));
      case AutomationRequirements.STONE_PICKAXE -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(2, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(3, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(5, AutomationRequirements.STICK),
          new IngredientPlacement(8, AutomationRequirements.STICK)
        )
      ));
      case AutomationRequirements.IRON_PICKAXE -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(2, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(3, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(5, AutomationRequirements.STICK),
          new IngredientPlacement(8, AutomationRequirements.STICK)
        )
      ));
      case AutomationRequirements.FURNACE -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(2, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(3, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(4, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(6, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(7, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(8, AutomationRequirements.COBBLESTONE),
          new IngredientPlacement(9, AutomationRequirements.COBBLESTONE)
        )
      ));
      case AutomationRequirements.BLAZE_POWDER -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        2,
        CraftingStation.INVENTORY,
        List.of(new IngredientPlacement(1, AutomationRequirements.BLAZE_ROD))
      ));
      case AutomationRequirements.ENDER_EYE -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.ENDER_PEARL),
          new IngredientPlacement(2, AutomationRequirements.BLAZE_POWDER)
        )
      ));
      case AutomationRequirements.FLINT_AND_STEEL -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(4, AutomationRequirements.FLINT)
        )
      ));
      case AutomationRequirements.BUCKET -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(3, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(5, AutomationRequirements.IRON_INGOT)
        )
      ));
      case AutomationRequirements.GOLD_INGOT -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(2, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(3, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(4, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(5, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(6, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(7, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(8, AutomationRequirements.GOLD_NUGGET),
          new IngredientPlacement(9, AutomationRequirements.GOLD_NUGGET)
        )
      ));
      case AutomationRequirements.SHIELD -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(2, AutomationRequirements.IRON_INGOT),
          new IngredientPlacement(3, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(4, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(5, AutomationRequirements.ANY_PLANKS),
          new IngredientPlacement(6, AutomationRequirements.ANY_PLANKS)
        )
      ));
      case AutomationRequirements.BOW -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.STICK),
          new IngredientPlacement(2, AutomationRequirements.STRING),
          new IngredientPlacement(4, AutomationRequirements.STICK),
          new IngredientPlacement(5, AutomationRequirements.STRING),
          new IngredientPlacement(7, AutomationRequirements.STICK),
          new IngredientPlacement(8, AutomationRequirements.STRING)
        )
      ));
      case AutomationRequirements.ARROW -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        4,
        CraftingStation.INVENTORY,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.FLINT),
          new IngredientPlacement(2, AutomationRequirements.STICK),
          new IngredientPlacement(3, AutomationRequirements.FEATHER)
        )
      ));
      case AutomationRequirements.ANY_BED -> Optional.of(new CraftingRecipeDefinition(
        targetKey,
        1,
        CraftingStation.CRAFTING_TABLE,
        List.of(
          new IngredientPlacement(1, AutomationRequirements.WHITE_WOOL),
          new IngredientPlacement(2, AutomationRequirements.WHITE_WOOL),
          new IngredientPlacement(3, AutomationRequirements.WHITE_WOOL),
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
      case AutomationRequirements.IRON_INGOT -> Optional.of(new SmeltingRecipeDefinition(
        targetKey,
        1,
        List.of(AutomationRequirements.RAW_IRON, AutomationRequirements.IRON_ORE)
      ));
      case AutomationRequirements.GOLD_INGOT -> Optional.of(new SmeltingRecipeDefinition(
        targetKey,
        1,
        List.of(AutomationRequirements.RAW_GOLD, AutomationRequirements.GOLD_ORE)
      ));
      case AutomationRequirements.CHARCOAL -> Optional.of(new SmeltingRecipeDefinition(
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
        state -> state.is(BlockTags.LOGS),
        ToolRequirement.NONE
      ));
      case AutomationRequirements.COBBLESTONE -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.STONE || state.getBlock() == Blocks.COBBLESTONE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case AutomationRequirements.COAL -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.COAL_ORE || state.getBlock() == Blocks.DEEPSLATE_COAL_ORE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case AutomationRequirements.RAW_IRON, AutomationRequirements.IRON_ORE -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.IRON_ORE || state.getBlock() == Blocks.DEEPSLATE_IRON_ORE,
        ToolRequirement.STONE_PICKAXE
      ));
      case AutomationRequirements.RAW_GOLD, AutomationRequirements.GOLD_ORE -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.GOLD_ORE || state.getBlock() == Blocks.DEEPSLATE_GOLD_ORE,
        ToolRequirement.IRON_PICKAXE
      ));
      case AutomationRequirements.GOLD_NUGGET -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.NETHER_GOLD_ORE,
        ToolRequirement.WOOD_PICKAXE
      ));
      case AutomationRequirements.FLINT -> Optional.of(new MineableSource(
        state -> state.getBlock() == Blocks.GRAVEL,
        ToolRequirement.NONE
      ));
      default -> Optional.empty();
    };
  }

  public static Optional<EntitySource> entitySource(String targetKey) {
    return switch (targetKey) {
      case AutomationRequirements.FOOD -> Optional.of(new EntitySource(
        AutomationWorldMemory.RememberedEntity::isAnimal,
        1
      ));
      case AutomationRequirements.BLAZE_ROD -> Optional.of(new EntitySource(
        AutomationWorldMemory.RememberedEntity::isBlaze,
        1
      ));
      case AutomationRequirements.ENDER_PEARL -> Optional.of(new EntitySource(
        entity -> entity.type() == EntityType.ENDERMAN,
        1
      ));
      case AutomationRequirements.STRING -> Optional.of(new EntitySource(
        entity -> entity.type() == EntityType.SPIDER
          || entity.type() == EntityType.CAVE_SPIDER,
        1
      ));
      case AutomationRequirements.FEATHER -> Optional.of(new EntitySource(
        entity -> entity.type() == EntityType.CHICKEN,
        1
      ));
      case AutomationRequirements.WHITE_WOOL -> Optional.of(new EntitySource(
        entity -> entity.type() == EntityType.SHEEP,
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
