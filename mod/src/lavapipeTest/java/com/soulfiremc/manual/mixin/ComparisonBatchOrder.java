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
package com.soulfiremc.manual.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/// Keeps unordered vanilla feature batches reproducible between the two test processes.
@Mixin(targets = "net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase$FeatureSubmits")
public class ComparisonBatchOrder {
  @Shadow @Final private Map<Object, ?> batches;

  @Redirect(method = "<init>", at = @At(value = "NEW", target = "java/util/HashMap"))
  private HashMap<Object, Object> preserveSubmissionOrder() {
    return new LinkedHashMap<>();
  }

  @Inject(method = "clear", at = @At("TAIL"))
  private void discardPreviousFrameOrder(CallbackInfo ci) {
    batches.clear();
  }
}
