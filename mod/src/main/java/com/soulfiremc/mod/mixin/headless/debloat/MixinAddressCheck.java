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
package com.soulfiremc.mod.mixin.headless.debloat;

import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AddressCheck.class)
public interface MixinAddressCheck {
  @Inject(method = "createFromService", at = @At("HEAD"), cancellable = true)
  private static void createFromService(CallbackInfoReturnable<AddressCheck> cir) {
    // Headless proxy checks do not need Mojang's client-side blocked-server list,
    // and fetching it can block every status ping during ServerNameResolver init.
    cir.setReturnValue(new AddressCheck() {
      @Override
      public boolean isAllowed(ResolvedServerAddress address) {
        return true;
      }

      @Override
      public boolean isAllowed(ServerAddress address) {
        return true;
      }
    });
  }
}
