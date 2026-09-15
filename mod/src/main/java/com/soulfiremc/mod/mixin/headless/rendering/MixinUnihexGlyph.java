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

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

@Mixin(targets = "net.minecraft.client.gui.font.providers.UnihexProvider$Glyph$2")
public class MixinUnihexGlyph {
  @Redirect(method = "upload", at = @At(value = "INVOKE",
    target = "Lorg/lwjgl/system/MemoryUtil;memByteBuffer(Ljava/nio/IntBuffer;)Ljava/nio/ByteBuffer;"))
  private ByteBuffer copyGlyphBytes(IntBuffer pixels) {
    var bytes = ByteBuffer.allocateDirect(pixels.remaining() * Integer.BYTES).order(ByteOrder.nativeOrder());
    bytes.asIntBuffer().put(pixels.duplicate());
    return bytes;
  }
}
