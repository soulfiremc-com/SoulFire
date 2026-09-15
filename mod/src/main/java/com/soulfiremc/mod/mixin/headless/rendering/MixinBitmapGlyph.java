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

import com.mojang.blaze3d.textures.GpuTexture;
import com.soulfiremc.server.renderer.RendererRuntimeTextureMirror;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.font.providers.BitmapProvider$Glyph$1")
public class MixinBitmapGlyph {
  @Shadow
  @Final
  private BitmapProvider.Glyph this$0;

  @Inject(method = "upload", at = @At("TAIL"))
  private void mirrorGlyphPixels(int x, int y, GpuTexture texture, CallbackInfo ci) {
    RendererRuntimeTextureMirror.mirrorWrite(texture, this$0.imageData().image, 0, 0,
      x, y, this$0.width(), this$0.height(), this$0.offsetX(), this$0.offsetY());
  }
}
