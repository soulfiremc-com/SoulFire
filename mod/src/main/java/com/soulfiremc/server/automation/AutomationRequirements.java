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
  public static final String FOOD = "group:food";
  public static final String FUEL = "group:fuel";

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

  private AutomationRequirements() {
  }

  public static String itemKey(Item item) {
    return "item:" + BuiltInRegistries.ITEM.getKey(item);
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
      case "food", "group:food" -> FOOD;
      case "fuel", "group:fuel" -> FUEL;
      case "pearl", "pearls" -> itemKey(Items.ENDER_PEARL);
      case "eye", "eyes" -> itemKey(Items.ENDER_EYE);
      case "rod", "rods" -> itemKey(Items.BLAZE_ROD);
      case "powder" -> itemKey(Items.BLAZE_POWDER);
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
    aliases.add("food");
    aliases.add("fuel");
    aliases.add("pearls");
    aliases.add("eyes");
    aliases.add("rods");
    return aliases;
  }
}
