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

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.mojang.serialization.JsonOps;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.util.SFEntityHelpers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/// Converts version-specific Minecraft objects into the stable SDK domain
/// model. All conversion happens on a bot game thread.
public final class MinecraftDomainMapper {
  private MinecraftDomainMapper() {}

  static WorldPosition worldPosition(Vec3 position, String dimension) {
    return WorldPosition.newBuilder()
      .setX(position.x)
      .setY(position.y)
      .setZ(position.z)
      .setDimension(dimension)
      .build();
  }

  static BlockPosition blockPosition(BlockPos position, String dimension) {
    return BlockPosition.newBuilder()
      .setX(position.getX())
      .setY(position.getY())
      .setZ(position.getZ())
      .setDimension(dimension)
      .build();
  }

  static com.soulfiremc.grpc.generated.Vec3 vector(Vec3 vector) {
    return com.soulfiremc.grpc.generated.Vec3.newBuilder()
      .setX(vector.x)
      .setY(vector.y)
      .setZ(vector.z)
      .build();
  }

  public static ItemStackSnapshot item(ItemStack stack) {
    if (stack.isEmpty()) {
      return ItemStackSnapshot.getDefaultInstance();
    }
    var itemId = stack.typeHolder().getRegisteredName();
    var builder = ItemStackSnapshot.newBuilder()
      .setItemId(itemId)
      .setCount(stack.getCount())
      .setMaxStackSize(stack.getMaxStackSize())
      .setDamage(stack.getDamageValue())
      .setMaxDamage(stack.getMaxDamage())
      .setFingerprint(fingerprint(stack));
    var customName = stack.getCustomName();
    if (customName != null) {
      builder.setCustomName(text(customName.getString()));
    }
    for (var entry : stack.getEnchantments().entrySet()) {
      var enchantmentId = entry.getKey().unwrapKey()
        .map(key -> key.identifier().toString())
        .orElse("minecraft:unknown");
      builder.addEnchantments(EnchantmentSnapshot.newBuilder()
        .setEnchantmentId(enchantmentId)
        .setLevel(entry.getIntValue()));
    }
    var lore = stack.get(DataComponents.LORE);
    if (lore != null) {
      lore.lines().forEach(line -> builder.addLore(text(line)));
    }
    var food = stack.get(DataComponents.FOOD);
    if (food != null) {
      var foodBuilder = FoodProperties.newBuilder()
        .setNutrition(food.nutrition())
        .setSaturationModifier(food.saturation())
        .setCanAlwaysEat(food.canAlwaysEat());
      var consumable = stack.get(DataComponents.CONSUMABLE);
      if (consumable != null) {
        foodBuilder.setEatSeconds(consumable.consumeSeconds());
      }
      builder.setFood(foodBuilder);
    }
    var tool = stack.get(DataComponents.TOOL);
    if (tool != null) {
      var toolBuilder = ToolProperties.newBuilder()
        .setDefaultMiningSpeed(tool.defaultMiningSpeed());
      tool.rules().stream()
        .map(rule -> rule.blocks().unwrapKey())
        .flatMap(java.util.Optional::stream)
        .map(key -> key.location().toString())
        .distinct()
        .sorted()
        .forEach(toolBuilder::addToolTags);
      builder.setTool(toolBuilder);
    }
    var equippable = stack.get(DataComponents.EQUIPPABLE);
    if (equippable != null) {
      var slot = equippable.slot();
      var modifiers = stack.getOrDefault(
        DataComponents.ATTRIBUTE_MODIFIERS,
        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY
      );
      builder.setArmor(ArmorProperties.newBuilder()
        .setEquipmentSlot(slot.getSerializedName())
        .setDefense((int) Math.round(modifiers.compute(Attributes.ARMOR, 0, slot)))
        .setToughness((float) modifiers.compute(
          Attributes.ARMOR_TOUGHNESS,
          0,
          slot
        ))
        .setKnockbackResistance((float) modifiers.compute(
          Attributes.KNOCKBACK_RESISTANCE,
          0,
          slot
        )));
    }
    var potion = stack.get(DataComponents.POTION_CONTENTS);
    if (potion != null) {
      var potionBuilder = PotionProperties.newBuilder().setColor(potion.getColor());
      potion.potion().ifPresent(value ->
        potionBuilder.setPotionId(value.getRegisteredName()));
      potion.getAllEffects().forEach(effect ->
        potionBuilder.addEffects(effect(effect)));
      builder.setPotion(potionBuilder);
    }
    var customData = stack.get(DataComponents.CUSTOM_DATA);
    if (customData != null && !customData.isEmpty()) {
      builder.setCustomDataNbt(
        com.google.protobuf.ByteString.copyFrom(serializeNbt(customData.copyTag()))
      );
    }
    var components = Struct.newBuilder();
    stack.getComponents().stream()
      .sorted(Comparator.comparing(component ->
        BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()).toString()))
      .forEach(component -> components.putFields(
        BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(component.type()).toString(),
        Value.newBuilder().setStringValue(component.value().toString()).build()
      ));
    builder.setComponents(components);
    return builder.build();
  }

  static EntitySnapshot entity(BotConnection bot, Entity entity) {
    var level = bot.minecraft().level;
    var dimension = level == null
      ? ""
      : level.dimension().identifier().toString();
    var builder = EntitySnapshot.newBuilder()
      .setReference(reference(bot, entity))
      .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
      .setCategory(category(entity))
      .setPosition(worldPosition(entity.position(), dimension))
      .setVelocity(vector(entity.getDeltaMovement()))
      .setRotation(Rotation.newBuilder()
        .setYaw(entity.getYRot())
        .setPitch(entity.getXRot()))
      .setBoundingBox(box(entity.getBoundingBox()))
      .setPose(entity.getPose().getSerializedName())
      .setOnGround(entity.onGround())
      .setDisplayName(text(entity.getName().getString()))
      .setAlive(SFEntityHelpers.isAliveAndTargetable(entity));
    if (entity instanceof Player player) {
      builder.setPlayerName(player.getGameProfile().name());
    }
    if (entity instanceof LivingEntity living) {
      builder
        .setHealth(living.getHealth())
        .setMaxHealth(living.getMaxHealth());
      addLivingState(builder, living);
    }
    var vehicle = entity.getVehicle();
    if (vehicle != null) {
      builder.setVehicle(reference(bot, vehicle));
    }
    builder.addAllPassengers(entity.getPassengers().stream()
      .map(passenger -> reference(bot, passenger))
      .toList());
    if (entity instanceof ItemEntity itemEntity && !itemEntity.getItem().isEmpty()) {
      builder.setItem(item(itemEntity.getItem()));
    }
    if (entity instanceof OwnableEntity ownable) {
      var owner = ownable.getOwner();
      if (owner != null) {
        builder.setOwner(reference(bot, owner));
      }
    } else if (entity instanceof Projectile projectile) {
      var owner = projectile.getOwner();
      if (owner != null) {
        builder.setOwner(reference(bot, owner));
      }
    }
    if (entity instanceof Mob mob) {
      builder.setAggressive(
        mob.isAggressive()
          || (mob instanceof NeutralMob neutralMob && neutralMob.isAngry())
          || (mob instanceof Enderman enderMan && enderMan.isCreepy())
      );
      if (mob.getTarget() != null) {
        builder.setTarget(reference(bot, mob.getTarget()));
      }
    }
    if (entity instanceof AgeableMob ageable) {
      builder.setAgeTicks(ageable.getAge());
    }
    if (entity instanceof TamableAnimal tamable) {
      builder.setTamed(tamable.isTame());
    }
    if (entity instanceof Creeper creeper) {
      builder.setMetadata(Struct.newBuilder()
        .putFields("creeper_fuse_progress", Value.newBuilder()
          .setNumberValue(creeper.getSwelling(1.0F))
          .build())
        .putFields("creeper_swell_direction", Value.newBuilder()
          .setNumberValue(creeper.getSwellDir())
          .build())
        .putFields("creeper_powered", Value.newBuilder()
          .setBoolValue(creeper.isPowered())
          .build())
        .putFields("creeper_ignited", Value.newBuilder()
          .setBoolValue(creeper.isIgnited())
          .build()));
    }
    return builder.build();
  }

  static BlockSnapshot block(
    ClientLevel level,
    BlockPos position,
    BlockState state,
    boolean includeBlockEntity,
    boolean includeShapes
  ) {
    var dimension = level.dimension().identifier().toString();
    var builder = BlockSnapshot.newBuilder()
      .setPosition(blockPosition(position, dimension))
      .setBlockId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
      .setHardness(state.getDestroySpeed(level, position))
      .setDiggable(state.getDestroySpeed(level, position) >= 0)
      .setReplaceable(state.canBeReplaced())
      .setSolid(state.isSolidRender())
      .setInteractive(isInteractive(level, position, state))
      .setChunkRevision(Math.max(0, level.getGameTime()));
    for (var property : state.getProperties()) {
      builder.putProperties(
        property.getName(),
        blockPropertyValue(state, property)
      );
    }
    var biome = level.getBiome(position).unwrapKey();
    biome.ifPresent(key -> builder.setBiomeId(key.identifier().toString()));
    builder
      .setSkyLight(level.getBrightness(LightLayer.SKY, position))
      .setBlockLight(level.getBrightness(LightLayer.BLOCK, position));
    state.getBlock().builtInRegistryHolder().tags()
      .map(key -> key.location().toString())
      .filter(id -> id.contains("mineable/") || id.contains("needs_"))
      .sorted()
      .forEach(builder::addEffectiveToolTags);
    var fluid = state.getFluidState();
    if (!fluid.isEmpty()) {
      builder.setFluid(FluidSnapshot.newBuilder()
        .setFluidId(fluid.typeHolder().getRegisteredName())
        .setSource(fluid.isSource())
        .setHeight(fluid.getHeight(level, position)));
    }
    if (includeBlockEntity) {
      var blockEntity = level.getBlockEntity(position);
      if (blockEntity != null) {
        builder.setBlockEntity(Struct.newBuilder()
          .putFields("type", Value.newBuilder()
            .setStringValue(blockEntity.getType().toString())
            .build()));
      }
    }
    if (includeShapes) {
      builder
        .setCollisionShape(shape(state.getCollisionShape(level, position)))
        .setInteractionShape(shape(state.getInteractionShape(level, position)));
    }
    return builder.build();
  }

  static PlayerSnapshot player(
    BotConnection bot,
    LocalPlayer player,
    ClientLevel level,
    long revision
  ) {
    var abilities = player.getAbilities();
    var builder = PlayerSnapshot.newBuilder()
      .setPosition(worldPosition(
        player.position(),
        level.dimension().identifier().toString()
      ))
      .setVelocity(vector(player.getDeltaMovement()))
      .setRotation(Rotation.newBuilder()
        .setYaw(player.getYRot())
        .setPitch(player.getXRot())
        .setHeadYaw(player.getYHeadRot()))
      .setOnGround(player.onGround())
      .setPose(player.getPose().getSerializedName())
      .setHealth(player.getHealth())
      .setMaxHealth(player.getMaxHealth())
      .setFood(player.getFoodData().getFoodLevel())
      .setSaturation(player.getFoodData().getSaturationLevel())
      .setAir(player.getAirSupply())
      .setMaxAir(player.getMaxAirSupply())
      .setFireTicks(player.getRemainingFireTicks())
      .setFreezingTicks(player.getTicksFrozen())
      .setExperience(ExperienceSnapshot.newBuilder()
        .setLevel(player.experienceLevel)
        .setProgress(player.experienceProgress)
        .setTotal(player.totalExperience))
      .setAbilities(PlayerAbilitiesSnapshot.newBuilder()
        .setInvulnerable(abilities.invulnerable)
        .setFlying(abilities.flying)
        .setMayFly(abilities.mayfly)
        .setInstantBuild(abilities.instabuild)
        .setFlyingSpeed(abilities.getFlyingSpeed())
        .setWalkingSpeed(abilities.getWalkingSpeed()))
      .setSelectedHotbarSlot(player.getInventory().getSelectedSlot())
      .setSleeping(player.isSleeping())
      .setUsingItem(player.isUsingItem())
      .setDead(player.isDeadOrDying())
      .setConnectionEpoch(bot.connectionEpoch().toString())
      .setRevision(revision);
    var gameMode = bot.minecraft().gameMode;
    if (gameMode != null) {
      builder.setGameMode(switch (gameMode.getPlayerMode()) {
        case SURVIVAL -> GameMode.GAME_MODE_SURVIVAL;
        case CREATIVE -> GameMode.GAME_MODE_CREATIVE;
        case ADVENTURE -> GameMode.GAME_MODE_ADVENTURE;
        case SPECTATOR -> GameMode.GAME_MODE_SPECTATOR;
      });
    }
    var mainHand = player.getMainHandItem();
    if (!mainHand.isEmpty()) {
      builder.setMainHand(item(mainHand));
    }
    var offHand = player.getOffhandItem();
    if (!offHand.isEmpty()) {
      builder.setOffHand(item(offHand));
    }
    addPlayerEquipment(builder, player);
    addPlayerLivingState(builder, player);
    var vehicle = player.getVehicle();
    if (vehicle != null) {
      builder.setVehicle(reference(bot, vehicle));
    }
    return builder.build();
  }

  static EntityReference reference(BotConnection bot, Entity entity) {
    return EntityReference.newBuilder()
      .setConnectionEpoch(bot.connectionEpoch().toString())
      .setNetworkId(entity.getId())
      .setUuid(entity.getUUID().toString())
      .build();
  }

  static EntityCategory category(Entity entity) {
    if (entity instanceof Player) {
      return EntityCategory.ENTITY_CATEGORY_PLAYER;
    }
    if (entity instanceof Enemy) {
      return EntityCategory.ENTITY_CATEGORY_HOSTILE;
    }
    if (entity instanceof Projectile) {
      return EntityCategory.ENTITY_CATEGORY_PROJECTILE;
    }
    if (entity instanceof ItemEntity) {
      return EntityCategory.ENTITY_CATEGORY_DROPPED_ITEM;
    }
    if (entity.isVehicle()) {
      return EntityCategory.ENTITY_CATEGORY_VEHICLE;
    }
    if (entity instanceof LivingEntity) {
      return EntityCategory.ENTITY_CATEGORY_PASSIVE;
    }
    return EntityCategory.ENTITY_CATEGORY_OTHER;
  }

  private static void addLivingState(
    EntitySnapshot.Builder builder,
    LivingEntity living
  ) {
    for (var slot : EquipmentSlot.values()) {
      var stack = living.getItemBySlot(slot);
      if (!stack.isEmpty()) {
        builder.putEquipment(slot.getSerializedName(), item(stack));
      }
    }
    living.getActiveEffects().forEach(effect ->
      builder.addEffects(effect(effect)));
    living.getAttributes().getSyncableAttributes().stream()
      .sorted(Comparator.comparing(attribute ->
        attribute.getAttribute().getRegisteredName()))
      .forEach(attribute -> builder.addAttributes(attribute(attribute)));
  }

  private static void addPlayerEquipment(
    PlayerSnapshot.Builder builder,
    LivingEntity living
  ) {
    for (var slot : EquipmentSlot.values()) {
      var stack = living.getItemBySlot(slot);
      if (!stack.isEmpty()) {
        builder.putEquipment(slot.getSerializedName(), item(stack));
      }
    }
  }

  private static void addPlayerLivingState(
    PlayerSnapshot.Builder builder,
    LivingEntity living
  ) {
    living.getActiveEffects().forEach(effect ->
      builder.addEffects(effect(effect)));
    living.getAttributes().getSyncableAttributes().stream()
      .sorted(Comparator.comparing(attribute ->
        attribute.getAttribute().getRegisteredName()))
      .forEach(attribute -> builder.addAttributes(attribute(attribute)));
  }

  private static EffectSnapshot effect(
    net.minecraft.world.effect.MobEffectInstance effect
  ) {
    return EffectSnapshot.newBuilder()
      .setEffectId(effect.getEffect().getRegisteredName())
      .setAmplifier(effect.getAmplifier())
      .setDurationTicks(effect.getDuration())
      .setAmbient(effect.isAmbient())
      .setVisible(effect.isVisible())
      .setShowIcon(effect.showIcon())
      .build();
  }

  private static AttributeSnapshot attribute(
    net.minecraft.world.entity.ai.attributes.AttributeInstance attribute
  ) {
    var builder = AttributeSnapshot.newBuilder()
      .setAttributeId(attribute.getAttribute().getRegisteredName())
      .setBaseValue(attribute.getBaseValue())
      .setValue(attribute.getValue());
    attribute.getModifiers().stream()
      .sorted(Comparator.comparing(modifier -> modifier.id().toString()))
      .forEach(modifier ->
        builder.addModifiers(AttributeModifierSnapshot.newBuilder()
          .setId(modifier.id().toString())
          .setAmount(modifier.amount())
          .setOperation(modifier.operation().name())));
    return builder.build();
  }

  private static BoundingBox box(AABB box) {
    return BoundingBox.newBuilder()
      .setMinimum(com.soulfiremc.grpc.generated.Vec3.newBuilder()
        .setX(box.minX)
        .setY(box.minY)
        .setZ(box.minZ))
      .setMaximum(com.soulfiremc.grpc.generated.Vec3.newBuilder()
        .setX(box.maxX)
        .setY(box.maxY)
        .setZ(box.maxZ))
      .build();
  }

  private static com.soulfiremc.grpc.generated.VoxelShape shape(
    net.minecraft.world.phys.shapes.VoxelShape shape
  ) {
    return com.soulfiremc.grpc.generated.VoxelShape.newBuilder()
      .addAllBoxes(shape.toAabbs().stream()
        .map(MinecraftDomainMapper::box)
        .toList())
      .build();
  }

  private static TextComponent text(String plainText) {
    return TextComponent.newBuilder().setPlainText(plainText).build();
  }

  static TextComponent text(Component component) {
    var builder = TextComponent.newBuilder().setPlainText(component.getString());
    ComponentSerialization.CODEC
      .encodeStart(JsonOps.INSTANCE, component)
      .result()
      .ifPresent(json -> builder.setJson(json.toString()));
    return builder.build();
  }

  static boolean isInteractive(
    ClientLevel level,
    BlockPos position,
    BlockState state
  ) {
    return state.getMenuProvider(level, position) != null
      || state.isSignalSource()
      || state.hasAnalogOutputSignal();
  }

  private static byte[] serializeNbt(net.minecraft.nbt.CompoundTag tag) {
    try {
      var output = new ByteArrayOutputStream();
      try (var data = new DataOutputStream(output)) {
        NbtIo.write(tag, data);
      }
      return output.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to serialize item custom data", exception);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static String blockPropertyValue(
    BlockState state,
    net.minecraft.world.level.block.state.properties.Property property
  ) {
    return property.getName(state.getValue(property));
  }

  private static String fingerprint(ItemStack stack) {
    var canonical = "%s|%s".formatted(
      stack.typeHolder().getRegisteredName(),
      stack.getComponentsPatch()
    );
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8))
      );
    } catch (NoSuchAlgorithmException exception) {
      throw new AssertionError("SHA-256 must be available", exception);
    }
  }
}
