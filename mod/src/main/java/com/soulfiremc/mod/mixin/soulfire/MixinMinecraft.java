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
package com.soulfiremc.mod.mixin.soulfire;

import com.soulfiremc.mod.access.IMinecraft;
import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.Bootstrap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.util.Objects;

@Mixin(Minecraft.class)
public class MixinMinecraft implements IMinecraft {
  @Unique
  public BotConnection soulfire$botConnection;
  @Unique
  public GameConfig soulfire$gameConfig;

  @Inject(method = "getInstance", at = @At("HEAD"), cancellable = true)
  private static void getInstance(CallbackInfoReturnable<Minecraft> cir) {
    var currentInstance = SFConstants.MINECRAFT_INSTANCE.get();
    if (currentInstance == null) {
      if (SFConstants.NOT_REGISTRY_INIT_PHASE) {
        new RuntimeException().printStackTrace();
        throw new IllegalStateException("Minecraft instance is null");
      } else {
        cir.setReturnValue(null);
      }
    } else {
      cir.setReturnValue(currentInstance);
    }
  }

  @Inject(method = "crash", at = @At("HEAD"), cancellable = true)
  private static void preventCrash(Minecraft minecraft, File gameDirectory, CrashReport crashReport, CallbackInfo ci) {
    Bootstrap.realStdoutPrintln(crashReport.getFriendlyReport(ReportType.CRASH));
    ci.cancel();
  }

  @Inject(method = "<init>", at = @At("RETURN"))
  private void setGaeConfig(GameConfig gameConfig, CallbackInfo ci) {
    soulfire$setGameConfig(gameConfig);
  }

  @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ReentrantBlockableEventLoop;<init>(Ljava/lang/String;Z)V", shift = At.Shift.AFTER))
  private void injectLocalHook(GameConfig arg, CallbackInfo ci) {
    SFConstants.MINECRAFT_INSTANCE.set((Minecraft) (Object) this);
  }

  @Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
  private void preventDestroy(CallbackInfo ci) {
    ci.cancel();
  }

  @Redirect(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;resetData()V"))
  private void preventResetData(GameRenderer instance) {
    // Prevent resetting because it causes race conditions
  }

  @Override
  public BotConnection soulfire$getConnection() {
    return Objects.requireNonNull(soulfire$botConnection, "BotConnection is null");
  }

  @Override
  public void soulfire$setConnection(BotConnection connection) {
    if (soulfire$botConnection != null) {
      throw new IllegalStateException("BotConnection is already set");
    }

    this.soulfire$botConnection = connection;
  }

  @Override
  public GameConfig soulfire$getGameConfig() {
    if (soulfire$gameConfig == null) {
      throw new IllegalStateException("GameConfig is not set");
    }
    return soulfire$gameConfig;
  }

  @Override
  public void soulfire$setGameConfig(GameConfig gameConfig) {
    if (soulfire$gameConfig != null) {
      throw new IllegalStateException("GameConfig is already set");
    }

    this.soulfire$gameConfig = gameConfig;
  }
}
