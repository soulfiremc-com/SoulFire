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
package com.soulfiremc.mod.mixin.headless.rendering;

import com.mojang.blaze3d.platform.InputConstants;
import com.soulfiremc.server.bot.BotConnection;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Mixin(KeyMapping.class)
public class MixinPovKeyMapping {
  @Shadow @Final private static Map<String, KeyMapping> ALL;
  @Inject(method = "forAllKeyMappings", at = @At("HEAD"), cancellable = true)
  private static void botMappings(InputConstants.Key key, Consumer<KeyMapping> operation, CallbackInfo ci) {
    var bot = BotConnection.currentOptional().orElse(null);
    if (bot == null) return;
    for (var mapping : bot.minecraft().options.keyMappings) {
      if (mapping.key.equals(key)) operation.accept(mapping);
    }
    ci.cancel();
  }

  @Redirect(method = {"releaseAll", "setAll", "restoreToggleStatesOnScreenClosed", "resetToggleKeys"},
    at = @At(value = "FIELD", target = "Lnet/minecraft/client/KeyMapping;ALL:Ljava/util/Map;"))
  private static Map<String, KeyMapping> botMappings() {
    var bot = BotConnection.currentOptional().orElse(null);
    if (bot == null) return ALL;
    var minecraft = bot.minecraft();
    return Arrays.stream(minecraft.options.keyMappings).collect(Collectors.toMap(KeyMapping::getName, key -> key));
  }
}
