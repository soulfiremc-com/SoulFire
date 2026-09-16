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

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(SectionRenderDispatcher.class)
public class MixinCaptureChunkBuffers {
  // Vanilla's allocators grow these heaps when needed. Captures need not reserve desktop-sized heaps.
  @ModifyConstant(method = "<init>", constant = @Constant(intValue = 102760448))
  private int initialStagingBytes(int original) { return 8 * 1024 * 1024; }

  @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 134217728))
  private int initialVertexBytes(int original) { return 4 * 1024 * 1024; }

  @ModifyConstant(method = "lambda$new$0", constant = @Constant(intValue = 33554432))
  private int initialIndexBytes(int original) { return 1024 * 1024; }
}
