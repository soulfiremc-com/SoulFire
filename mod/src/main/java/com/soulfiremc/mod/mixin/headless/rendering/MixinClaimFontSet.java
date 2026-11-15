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

import com.soulfiremc.server.renderer.VulkanRenderer;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Bots share the base client's fonts. Glyph lookups fill an unsynchronized cache and upload
/// glyphs into shared font textures.
@Mixin(FontSet.class)
public class MixinClaimFontSet {
  @Inject(method = "getGlyph(I)Lnet/minecraft/client/gui/font/FontSet$SelectedGlyphs;", at = @At("HEAD"))
  private void claimSharedGlyphs(int codepoint, CallbackInfoReturnable<?> cir) {
    VulkanRenderer.claimDeviceAccess();
  }
}
