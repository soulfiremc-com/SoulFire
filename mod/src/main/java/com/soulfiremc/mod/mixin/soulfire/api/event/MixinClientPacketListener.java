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
package com.soulfiremc.mod.mixin.soulfire.api.event;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.bot.BotDamageEvent;
import com.soulfiremc.server.api.event.bot.BotOpenContainerEvent;
import com.soulfiremc.server.api.event.bot.BotShouldRespawnEvent;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.HealthDamageTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
  @Unique
  private final HealthDamageTracker soulfire$damageTracker = new HealthDamageTracker();

  @Inject(method = {"handleContainerContent", "handleContainerSetSlot", "handleSetCursorItem", "handleSetPlayerInventory"}, at = @At("RETURN"))
  private void onInventoryUpdate(CallbackInfo ci) {
    BotConnection.currentOptional().ifPresent(BotConnection::onInventoryUpdate);
  }

  @Inject(method = "handleContainerContent", at = @At("RETURN"))
  private void onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
    BotConnection.currentOptional().ifPresent(bot -> bot.onContainerContent(packet.containerId()));
  }

  @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
  private void onLevelChunkWithLight(
    ClientboundLevelChunkWithLightPacket packet,
    CallbackInfo ci
  ) {
    BotConnection.current().navigationWorldState().markChanged();
  }

  @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
  private void onForgetLevelChunk(
    ClientboundForgetLevelChunkPacket packet,
    CallbackInfo ci
  ) {
    BotConnection.current().navigationWorldState().markChanged();
  }

  @WrapOperation(method = "handlePlayerCombatKill",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;shouldShowDeathScreen()Z"))
  private boolean shouldRespawnEvent(LocalPlayer instance, Operation<Boolean> original) {
    var event = new BotShouldRespawnEvent(BotConnection.current(), !original.call(instance));
    SoulFireAPI.postEvent(event);
    return !event.shouldRespawn();
  }

  // Vanilla packet handlers start with PacketUtils.ensureRunningOnSameThread: called on the network
  // thread, it queues the packet for the game thread and throws, and the handler runs again there.
  // An injection at HEAD sees both calls, so the two below only act on the game-thread one (the
  // check vanilla makes).
  @Inject(method = "handleSetHealth", at = @At("HEAD"))
  private void onSetHealth(ClientboundSetHealthPacket packet, CallbackInfo ci) {
    var bot = BotConnection.current();
    if (!bot.minecraft().packetProcessor().isSameThread() || bot.minecraft().player == null) {
      return;
    }

    var damage = soulfire$damageTracker.update(packet.getHealth());
    if (damage != null) {
      var event = new BotDamageEvent(
        BotConnection.current(),
        damage.previousHealth(),
        damage.newHealth(),
        damage.amount()
      );
      SoulFireAPI.postEvent(event);
    }
  }

  @Inject(method = "handleOpenScreen", at = @At("HEAD"))
  private void onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
    var bot = BotConnection.current();
    if (!bot.minecraft().packetProcessor().isSameThread() || bot.minecraft().player == null) {
      return;
    }

    var containerId = packet.getContainerId();
    var title = packet.getTitle().getString();
    var containerType = BuiltInRegistries.MENU.getKey(packet.getType()).getPath();

    var event = new BotOpenContainerEvent(
      bot,
      containerId,
      title,
      containerType
    );

    SoulFireAPI.postEvent(event);
  }
}
