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

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;
import java.util.Map;

/// Resolves durable item data against the current connection's bound components.
/// The cache belongs to one execution; only StoredItem values enter recovery state.
final class RecipeItemValues {
  static final StoredItem EMPTY = new StoredItem(new CompoundTag());
  private final RegistryOps<Tag> ops;
  private final Map<StoredItem, ItemStack> cache = new IdentityHashMap<>();

  RecipeItemValues(ClientLevel level) {
    ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
  }

  StoredItem capture(ItemStack stack) {
    if (stack.isEmpty()) return EMPTY;
    var data = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
    if (!(data instanceof CompoundTag compound)) {
      throw new IllegalStateException("Item serialization did not produce a compound");
    }
    var stored = new StoredItem(compound);
    cache.put(stored, stack.copy());
    return stored;
  }

  ItemStack resolve(StoredItem stored) {
    if (stored == EMPTY) return ItemStack.EMPTY;
    return cache.computeIfAbsent(stored, value -> ItemStack.CODEC.parse(ops, value.data()).getOrThrow());
  }

  record StoredItem(CompoundTag data) {
  }
}
