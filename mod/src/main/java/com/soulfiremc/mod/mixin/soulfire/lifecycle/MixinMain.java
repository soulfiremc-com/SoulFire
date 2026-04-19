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
package com.soulfiremc.mod.mixin.soulfire.lifecycle;

import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.server.util.log4j.GenericTerminalConsole;
import com.soulfiremc.shared.Base64Helpers;
import com.soulfiremc.shared.SoulFirePreMainBootstrap;
import io.github.headlesshq.headlessmc.lwjgl.agent.LwjglAgent;
import lombok.SneakyThrows;
import net.lenni0451.reflect.Agents;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

@Mixin(Main.class)
public final class MixinMain {
  @Unique
  private static final long soulfire$INITIAL_CLIENT_LOAD_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

  @SneakyThrows
  @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"))
  private static void init(CallbackInfo cir) {
    SoulFirePreMainBootstrap.preMainBootstrap();
    Agents.getInstrumentation().addTransformer(new LwjglAgent());
    GenericTerminalConsole.setupStreams();
    SharedConstants.CHECK_DATA_FIXER_SCHEMA = false;
    SFConstants.NOT_REGISTRY_INIT_PHASE = false;
  }

  @SneakyThrows
  @Redirect(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;run()V"))
  private static void init(Minecraft instance) {
    // We want this to not inject anywhere else
    SFConstants.MINECRAFT_INSTANCE.remove();
    SFConstants.NOT_REGISTRY_INIT_PHASE = true;

    SFConstants.BASE_MC_INSTANCE = instance;
    soulfireFinishInitialClientLoad(instance);

    try {
      var args = Base64Helpers.splitBase64(System.getProperty("sf.initial.arguments"));
      Class.forName(System.getProperty("sf.bootstrap.class"))
        .getDeclaredMethod("bootstrap", String[].class)
        .invoke(null, (Object) args);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  @Redirect(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Ljava/lang/Thread;setName(Ljava/lang/String;)V", remap = false))
  private static void setThreadName(Thread instance, String name) {
    // Prevent changing main thread name
  }

  @Redirect(method = "main([Ljava/lang/String;)V", at = @At(value = "INVOKE", target = "Ljava/lang/Runtime;addShutdownHook(Ljava/lang/Thread;)V", remap = false))
  private static void addShutdownHook(Runtime instance, Thread thread) {
    // Prevent registering an integrated server shutdown hook
    // Because it uses getInstance() during shutdown
  }

  @Unique
  private static void soulfireFinishInitialClientLoad(Minecraft instance) {
    var deadline = System.nanoTime() + soulfire$INITIAL_CLIENT_LOAD_TIMEOUT_NANOS;
    while (!soulfireHasInitializedBlockModels(instance) && System.nanoTime() < deadline) {
      instance.runTick(true);
    }

    if (!soulfireHasInitializedBlockModels(instance)) {
      throw new IllegalStateException("Timed out while waiting for the base Minecraft instance to initialize block models");
    }
  }

  @Unique
  private static boolean soulfireHasInitializedBlockModels(Minecraft instance) {
    try {
      instance.getModelManager().getBlockStateModelSet();
      return true;
    } catch (RuntimeException _) {
      return false;
    }
  }
}
