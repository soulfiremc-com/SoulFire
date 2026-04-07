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

import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class AutomationWorldMemory {
  private static final int BLOCK_SCAN_RADIUS = 32;
  private static final int BLOCK_SCAN_INTERVAL_TICKS = 20;
  private static final long MEMORY_EXPIRY_TICKS = 20L * 60L * 5L;
  private static final long UNREACHABLE_EXPIRY_TICKS = 20L * 30L;

  private final Map<Long, RememberedBlock> blocks = new HashMap<>();
  private final Map<Long, RememberedContainer> containers = new HashMap<>();
  private final Map<UUID, RememberedEntity> entities = new HashMap<>();
  private final Map<UUID, RememberedItem> droppedItems = new HashMap<>();
  private final Map<Long, Long> unreachablePositions = new HashMap<>();
  private long ticks;
  private long lastBlockScan;

  public void observe(BotConnection bot) {
    ticks++;

    var player = bot.minecraft().player;
    var level = bot.minecraft().level;
    if (player == null || level == null) {
      return;
    }

    for (var entity : level.entitiesForRendering()) {
      if (entity instanceof ItemEntity itemEntity) {
        droppedItems.put(itemEntity.getUUID(), new RememberedItem(
          itemEntity.getUUID(),
          itemEntity.getItem().copy(),
          itemEntity.position(),
          ticks
        ));
      } else {
        entities.put(entity.getUUID(), new RememberedEntity(
          entity.getUUID(),
          entity.getType(),
          entity.position(),
          ticks
        ));
      }
    }

    if (ticks - lastBlockScan >= BLOCK_SCAN_INTERVAL_TICKS) {
      lastBlockScan = ticks;
      var origin = player.blockPosition();
      var minY = Math.max(level.getMinY(), origin.getY() - BLOCK_SCAN_RADIUS);
      var maxY = Math.min(level.getMaxY(), origin.getY() + BLOCK_SCAN_RADIUS);
      for (int dx = -BLOCK_SCAN_RADIUS; dx <= BLOCK_SCAN_RADIUS; dx++) {
        for (int dz = -BLOCK_SCAN_RADIUS; dz <= BLOCK_SCAN_RADIUS; dz++) {
          for (int y = minY; y <= maxY; y++) {
            var pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
            var state = level.getBlockState(pos);
            if (!isInterestingBlock(state)) {
              continue;
            }

            var immutablePos = pos.immutable();
            blocks.put(immutablePos.asLong(), new RememberedBlock(immutablePos, state, ticks));
            if (isContainerBlock(state.getBlock())) {
              containers.compute(immutablePos.asLong(), (_, existing) ->
                existing == null
                  ? new RememberedContainer(immutablePos, state, Map.of(), false, ticks)
                  : existing.withState(state, ticks));
            }
          }
        }
      }
    }

    prune();
  }

  public Optional<RememberedBlock> findNearestBlock(BotConnection bot, Predicate<BlockState> predicate) {
    var player = bot.minecraft().player;
    if (player == null) {
      return Optional.empty();
    }

    return blocks.values().stream()
      .filter(block -> predicate.test(block.state()))
      .filter(block -> isReachable(block.pos()))
      .min((a, b) -> Double.compare(
        a.pos().distSqr(player.blockPosition()),
        b.pos().distSqr(player.blockPosition())));
  }

  public Optional<RememberedContainer> findNearestContainerWithItem(BotConnection bot, String requirementKey) {
    var player = bot.minecraft().player;
    if (player == null) {
      return Optional.empty();
    }

    return containers.values().stream()
      .filter(container -> container.inspected())
      .filter(container -> container.contents().entrySet().stream()
        .anyMatch(entry -> AutomationRequirements.matchesItem(requirementKey, entry.getKey()) && entry.getValue() > 0))
      .filter(container -> isReachable(container.pos()))
      .min((a, b) -> Double.compare(
        a.pos().distSqr(player.blockPosition()),
        b.pos().distSqr(player.blockPosition())));
  }

  public Optional<RememberedContainer> findNearestUninspectedContainer(BotConnection bot) {
    var player = bot.minecraft().player;
    if (player == null) {
      return Optional.empty();
    }

    return containers.values().stream()
      .filter(container -> !container.inspected())
      .filter(container -> isReachable(container.pos()))
      .min((a, b) -> Double.compare(
        a.pos().distSqr(player.blockPosition()),
        b.pos().distSqr(player.blockPosition())));
  }

  public Optional<RememberedItem> findNearestDroppedItem(BotConnection bot, String requirementKey) {
    var player = bot.minecraft().player;
    if (player == null) {
      return Optional.empty();
    }

    return droppedItems.values().stream()
      .filter(item -> AutomationRequirements.matches(requirementKey, item.stack()))
      .min((a, b) -> Double.compare(
        a.position().distanceToSqr(player.position()),
        b.position().distanceToSqr(player.position())));
  }

  public Optional<RememberedEntity> findNearestHostile(BotConnection bot) {
    return findNearestEntity(bot, RememberedEntity::isMonster);
  }

  public Optional<RememberedEntity> findNearestAnimal(BotConnection bot) {
    return findNearestEntity(bot, entity -> entity.type().getBaseClass() != null
      && Animal.class.isAssignableFrom(entity.type().getBaseClass()));
  }

  public Optional<RememberedEntity> findNearestPiglin(BotConnection bot) {
    return findNearestEntity(bot, entity -> entity.type() == EntityType.PIGLIN);
  }

  public Optional<RememberedEntity> findNearestBlaze(BotConnection bot) {
    return findNearestEntity(bot, entity -> entity.type() == EntityType.BLAZE);
  }

  public Optional<RememberedEntity> findNearestEntity(BotConnection bot, Predicate<RememberedEntity> predicate) {
    var player = bot.minecraft().player;
    if (player == null) {
      return Optional.empty();
    }

    return entities.values().stream()
      .filter(predicate)
      .min((a, b) -> Double.compare(
        a.position().distanceToSqr(player.position()),
        b.position().distanceToSqr(player.position())));
  }

  public void rememberContainerContents(BlockPos pos, AbstractContainerMenu menu) {
    if (menu instanceof InventoryMenu) {
      return;
    }

    var slotCount = containerSlotCount(menu);
    var contentMap = new HashMap<Item, Integer>();
    for (int i = 0; i < slotCount && i < menu.slots.size(); i++) {
      var stack = menu.getSlot(i).getItem();
      if (stack.isEmpty()) {
        continue;
      }
      contentMap.merge(stack.getItem(), stack.getCount(), Integer::sum);
    }

    var immutablePos = pos.immutable();
    var state = containers.containsKey(immutablePos.asLong())
      ? containers.get(immutablePos.asLong()).state()
      : Blocks.CHEST.defaultBlockState();
    containers.put(immutablePos.asLong(), new RememberedContainer(immutablePos, state, Map.copyOf(contentMap), true, ticks));
  }

  public void markUnreachable(BlockPos pos) {
    unreachablePositions.put(pos.asLong(), ticks + UNREACHABLE_EXPIRY_TICKS);
  }

  public boolean isReachable(BlockPos pos) {
    var until = unreachablePositions.get(pos.asLong());
    return until == null || until < ticks;
  }

  public long ticks() {
    return ticks;
  }

  public Collection<RememberedBlock> rememberedBlocks() {
    return java.util.List.copyOf(blocks.values());
  }

  public Collection<RememberedContainer> rememberedContainers() {
    return java.util.List.copyOf(containers.values());
  }

  public Collection<RememberedEntity> rememberedEntities() {
    return java.util.List.copyOf(entities.values());
  }

  public Collection<RememberedItem> rememberedDroppedItems() {
    return java.util.List.copyOf(droppedItems.values());
  }

  public static boolean isInterestingBlock(BlockState state) {
    var block = state.getBlock();
    return state.is(BlockTags.LOGS)
      || block == Blocks.STONE
      || block == Blocks.COBBLESTONE
      || block == Blocks.COAL_ORE
      || block == Blocks.DEEPSLATE_COAL_ORE
      || block == Blocks.IRON_ORE
      || block == Blocks.DEEPSLATE_IRON_ORE
      || block == Blocks.GOLD_ORE
      || block == Blocks.DEEPSLATE_GOLD_ORE
      || block == Blocks.NETHER_GOLD_ORE
      || block == Blocks.GRAVEL
      || block == Blocks.OBSIDIAN
      || block == Blocks.CRYING_OBSIDIAN
      || block == Blocks.GOLD_BLOCK
      || block == Blocks.NETHERRACK
      || block == Blocks.MAGMA_BLOCK
      || block == Blocks.CRAFTING_TABLE
      || block == Blocks.FURNACE
      || block == Blocks.BLAST_FURNACE
      || block == Blocks.SMOKER
      || block == Blocks.CHEST
      || block == Blocks.TRAPPED_CHEST
      || block == Blocks.BARREL
      || block == Blocks.NETHER_PORTAL
      || block == Blocks.NETHER_BRICKS
      || block == Blocks.NETHER_BRICK_FENCE
      || block == Blocks.NETHER_BRICK_STAIRS
      || block == Blocks.SPAWNER
      || block == Blocks.END_PORTAL_FRAME
      || block == Blocks.END_PORTAL
      || block == Blocks.LAVA
      || block == Blocks.WATER
      || block instanceof BedBlock;
  }

  public static boolean isContainerBlock(Block block) {
    return block == Blocks.CHEST
      || block == Blocks.TRAPPED_CHEST
      || block instanceof BarrelBlock
      || block == Blocks.FURNACE
      || block == Blocks.BLAST_FURNACE
      || block == Blocks.SMOKER;
  }

  public static int containerSlotCount(AbstractContainerMenu menu) {
    if (menu instanceof ChestMenu chestMenu) {
      return chestMenu.getRowCount() * 9;
    }

    return Math.max(0, menu.slots.size() - 36);
  }

  private void prune() {
    blocks.values().removeIf(block -> ticks - block.lastSeenTick() > MEMORY_EXPIRY_TICKS);
    containers.values().removeIf(container -> ticks - container.lastSeenTick() > MEMORY_EXPIRY_TICKS);
    droppedItems.values().removeIf(item -> ticks - item.lastSeenTick() > 40L);
    entities.values().removeIf(entity -> ticks - entity.lastSeenTick() > 40L);
    unreachablePositions.entrySet().removeIf(entry -> entry.getValue() < ticks);
  }

  public record RememberedBlock(BlockPos pos, BlockState state, long lastSeenTick) {
  }

  public record RememberedItem(UUID uuid, ItemStack stack, Vec3 position, long lastSeenTick) {
  }

  public record RememberedEntity(UUID uuid, EntityType<?> type, Vec3 position, long lastSeenTick) {
    public BlockPos pos() {
      return BlockPos.containing(position);
    }

    public boolean isMonster() {
      return type.getBaseClass() != null && Monster.class.isAssignableFrom(type.getBaseClass());
    }

    public boolean isBlaze() {
      return type.getBaseClass() != null && Blaze.class.isAssignableFrom(type.getBaseClass());
    }

    public boolean isPiglin() {
      return type.getBaseClass() != null && Piglin.class.isAssignableFrom(type.getBaseClass());
    }

    public boolean isAnimal() {
      return type.getBaseClass() != null && Animal.class.isAssignableFrom(type.getBaseClass());
    }
  }

  public record RememberedContainer(
    BlockPos pos,
    BlockState state,
    Map<Item, Integer> contents,
    boolean inspected,
    long lastSeenTick
  ) {
    private RememberedContainer withState(BlockState newState, long tick) {
      return new RememberedContainer(pos, newState, contents, inspected, tick);
    }
  }
}
