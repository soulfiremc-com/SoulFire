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

import com.soulfiremc.server.util.SFItemHelpers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class AutomationRequirements {
  public static final String ANY_LOG = "group:any_log";
  public static final String ANY_PLANKS = "group:any_planks";
  public static final String ANY_BED = "group:any_bed";
  public static final String FOOD = "group:food";
  public static final String FUEL = "group:fuel";
  public static final String STICK = "item:minecraft:stick";
  public static final String CRAFTING_TABLE = "item:minecraft:crafting_table";
  public static final String WOODEN_PICKAXE = "item:minecraft:wooden_pickaxe";
  public static final String STONE_PICKAXE = "item:minecraft:stone_pickaxe";
  public static final String IRON_PICKAXE = "item:minecraft:iron_pickaxe";
  public static final String DIAMOND_PICKAXE = "item:minecraft:diamond_pickaxe";
  public static final String FURNACE = "item:minecraft:furnace";
  public static final String COBBLESTONE = "item:minecraft:cobblestone";
  public static final String COAL = "item:minecraft:coal";
  public static final String CHARCOAL = "item:minecraft:charcoal";
  public static final String RAW_IRON = "item:minecraft:raw_iron";
  public static final String IRON_ORE = "item:minecraft:iron_ore";
  public static final String IRON_INGOT = "item:minecraft:iron_ingot";
  public static final String RAW_GOLD = "item:minecraft:raw_gold";
  public static final String GOLD_ORE = "item:minecraft:gold_ore";
  public static final String GOLD_INGOT = "item:minecraft:gold_ingot";
  public static final String GOLD_NUGGET = "item:minecraft:gold_nugget";
  public static final String BLAZE_ROD = "item:minecraft:blaze_rod";
  public static final String BLAZE_POWDER = "item:minecraft:blaze_powder";
  public static final String ENDER_PEARL = "item:minecraft:ender_pearl";
  public static final String ENDER_EYE = "item:minecraft:ender_eye";
  public static final String BOW = "item:minecraft:bow";
  public static final String ARROW = "item:minecraft:arrow";
  public static final String SHIELD = "item:minecraft:shield";
  public static final String BUCKET = "item:minecraft:bucket";
  public static final String WATER_BUCKET = "item:minecraft:water_bucket";
  public static final String LAVA_BUCKET = "item:minecraft:lava_bucket";
  public static final String FLINT_AND_STEEL = "item:minecraft:flint_and_steel";
  public static final String FLINT = "item:minecraft:flint";
  public static final String OBSIDIAN = "item:minecraft:obsidian";
  public static final String STRING = "item:minecraft:string";
  public static final String FEATHER = "item:minecraft:feather";
  public static final String WHITE_WOOL = "item:minecraft:white_wool";

  private static final Set<Item> LOG_ITEMS = Set.of(
    Items.OAK_LOG,
    Items.SPRUCE_LOG,
    Items.BIRCH_LOG,
    Items.JUNGLE_LOG,
    Items.ACACIA_LOG,
    Items.DARK_OAK_LOG,
    Items.MANGROVE_LOG,
    Items.CHERRY_LOG,
    Items.PALE_OAK_LOG,
    Items.CRIMSON_STEM,
    Items.WARPED_STEM,
    Items.BAMBOO_BLOCK
  );
  private static final Set<Item> PLANK_ITEMS = Set.of(
    Items.OAK_PLANKS,
    Items.SPRUCE_PLANKS,
    Items.BIRCH_PLANKS,
    Items.JUNGLE_PLANKS,
    Items.ACACIA_PLANKS,
    Items.DARK_OAK_PLANKS,
    Items.MANGROVE_PLANKS,
    Items.CHERRY_PLANKS,
    Items.PALE_OAK_PLANKS,
    Items.CRIMSON_PLANKS,
    Items.WARPED_PLANKS,
    Items.BAMBOO_PLANKS
  );
  private static final Set<Item> FUEL_ITEMS = Set.of(Items.COAL, Items.CHARCOAL);
  private static final Set<Item> BED_ITEMS = Set.of(
    Items.WHITE_BED,
    Items.ORANGE_BED,
    Items.MAGENTA_BED,
    Items.LIGHT_BLUE_BED,
    Items.YELLOW_BED,
    Items.LIME_BED,
    Items.PINK_BED,
    Items.GRAY_BED,
    Items.LIGHT_GRAY_BED,
    Items.CYAN_BED,
    Items.PURPLE_BED,
    Items.BLUE_BED,
    Items.BROWN_BED,
    Items.GREEN_BED,
    Items.RED_BED,
    Items.BLACK_BED
  );

  private AutomationRequirements() {
  }

  static {
    verifyExactKey(STICK, Items.STICK);
    verifyExactKey(CRAFTING_TABLE, Items.CRAFTING_TABLE);
    verifyExactKey(WOODEN_PICKAXE, Items.WOODEN_PICKAXE);
    verifyExactKey(STONE_PICKAXE, Items.STONE_PICKAXE);
    verifyExactKey(IRON_PICKAXE, Items.IRON_PICKAXE);
    verifyExactKey(DIAMOND_PICKAXE, Items.DIAMOND_PICKAXE);
    verifyExactKey(FURNACE, Items.FURNACE);
    verifyExactKey(COBBLESTONE, Items.COBBLESTONE);
    verifyExactKey(COAL, Items.COAL);
    verifyExactKey(CHARCOAL, Items.CHARCOAL);
    verifyExactKey(RAW_IRON, Items.RAW_IRON);
    verifyExactKey(IRON_ORE, Items.IRON_ORE);
    verifyExactKey(IRON_INGOT, Items.IRON_INGOT);
    verifyExactKey(RAW_GOLD, Items.RAW_GOLD);
    verifyExactKey(GOLD_ORE, Items.GOLD_ORE);
    verifyExactKey(GOLD_INGOT, Items.GOLD_INGOT);
    verifyExactKey(GOLD_NUGGET, Items.GOLD_NUGGET);
    verifyExactKey(BLAZE_ROD, Items.BLAZE_ROD);
    verifyExactKey(BLAZE_POWDER, Items.BLAZE_POWDER);
    verifyExactKey(ENDER_PEARL, Items.ENDER_PEARL);
    verifyExactKey(ENDER_EYE, Items.ENDER_EYE);
    verifyExactKey(BOW, Items.BOW);
    verifyExactKey(ARROW, Items.ARROW);
    verifyExactKey(SHIELD, Items.SHIELD);
    verifyExactKey(BUCKET, Items.BUCKET);
    verifyExactKey(WATER_BUCKET, Items.WATER_BUCKET);
    verifyExactKey(LAVA_BUCKET, Items.LAVA_BUCKET);
    verifyExactKey(FLINT_AND_STEEL, Items.FLINT_AND_STEEL);
    verifyExactKey(FLINT, Items.FLINT);
    verifyExactKey(OBSIDIAN, Items.OBSIDIAN);
    verifyExactKey(STRING, Items.STRING);
    verifyExactKey(FEATHER, Items.FEATHER);
    verifyExactKey(WHITE_WOOL, Items.WHITE_WOOL);
  }

  public static String itemKey(Item item) {
    return "item:" + BuiltInRegistries.ITEM.getKey(item);
  }

  private static void verifyExactKey(String key, Item item) {
    var actualKey = itemKey(item);
    if (!key.equals(actualKey)) {
      throw new IllegalStateException("Automation requirement key mismatch for " + item + ": expected " + key + " but registry resolved " + actualKey);
    }
  }

  public static Optional<Item> exactItem(String key) {
    if (!key.startsWith("item:")) {
      return Optional.empty();
    }

    var id = Identifier.parse(key.substring("item:".length()));
    return Optional.ofNullable(BuiltInRegistries.ITEM.getValue(id));
  }

  public static boolean isExactItemKey(String key) {
    return key.startsWith("item:");
  }

  public static String normalize(String raw) {
    var normalized = raw.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "log", "logs", "wood", "group:any_log" -> ANY_LOG;
      case "plank", "planks", "group:any_planks" -> ANY_PLANKS;
      case "bed", "beds", "group:any_bed" -> ANY_BED;
      case "food", "group:food" -> FOOD;
      case "fuel", "group:fuel" -> FUEL;
      case "pearl", "pearls" -> ENDER_PEARL;
      case "eye", "eyes" -> ENDER_EYE;
      case "rod", "rods" -> BLAZE_ROD;
      case "powder" -> BLAZE_POWDER;
      case "arrow", "arrows" -> ARROW;
      case "bow" -> BOW;
      case "shield" -> SHIELD;
      case "bucket" -> BUCKET;
      default -> {
        var identifier = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(identifier));
        if (item == null || item == Items.AIR) {
          throw new IllegalArgumentException("Unknown automation requirement: " + raw);
        }
        yield itemKey(item);
      }
    };
  }

  public static boolean matches(String key, ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }

    return switch (key) {
      case ANY_LOG -> LOG_ITEMS.contains(stack.getItem());
      case ANY_PLANKS -> PLANK_ITEMS.contains(stack.getItem());
      case ANY_BED -> BED_ITEMS.contains(stack.getItem());
      case FOOD -> SFItemHelpers.isGoodEdibleFood(stack);
      case FUEL -> FUEL_ITEMS.contains(stack.getItem()) || LOG_ITEMS.contains(stack.getItem()) || PLANK_ITEMS.contains(stack.getItem());
      default -> exactItem(key).map(item -> item == stack.getItem()).orElse(false);
    };
  }

  public static boolean matchesItem(String key, Item item) {
    return matches(key, item.getDefaultInstance());
  }

  public static int countMatching(AbstractContainerMenu menu, String key) {
    var total = 0;
    for (var slot : menu.slots) {
      var stack = slot.getItem();
      if (matches(key, stack)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  public static Set<Item> acceptedItems(String key) {
    return switch (key) {
      case ANY_LOG -> LOG_ITEMS;
      case ANY_PLANKS -> PLANK_ITEMS;
      case ANY_BED -> BED_ITEMS;
      case FUEL -> {
        var fuels = new LinkedHashSet<Item>();
        fuels.addAll(FUEL_ITEMS);
        fuels.addAll(LOG_ITEMS);
        fuels.addAll(PLANK_ITEMS);
        yield Set.copyOf(fuels);
      }
      case FOOD -> Set.of();
      default -> exactItem(key).map(Set::of).orElse(Set.of());
    };
  }

  public static String describe(String key) {
    return switch (key) {
      case ANY_LOG -> "any log";
      case ANY_PLANKS -> "any planks";
      case ANY_BED -> "any bed";
      case FOOD -> "food";
      case FUEL -> "fuel";
      default -> exactItem(key)
        .map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
        .orElse(key);
    };
  }

  public static Set<String> aliases() {
    var aliases = new LinkedHashSet<String>();
    aliases.add("log");
    aliases.add("planks");
    aliases.add("bed");
    aliases.add("food");
    aliases.add("fuel");
    aliases.add("pearls");
    aliases.add("eyes");
    aliases.add("rods");
    aliases.add("arrows");
    aliases.add("bow");
    aliases.add("shield");
    aliases.add("bucket");
    return aliases;
  }
}
