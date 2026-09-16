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

import net.minecraft.TracingExecutor;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionRenderDispatcher.class)
public class MixinCaptureChunks {
  @Redirect(method = {"schedule", "runTask"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/TracingExecutor;execute(Ljava/lang/Runnable;)V"))
  private void completeRequestedGeometry(TracingExecutor executor, Runnable task) {
    // Finish both compilation and translucent sorting before capture or scene disposal.
    task.run();
  }
}
