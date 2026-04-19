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
package com.soulfiremc.server.renderer;

import lombok.experimental.UtilityClass;
import net.minecraft.util.Mth;

@UtilityClass
public class SkyRenderer {
  public static int sampleSky(RenderContext ctx, double dirX, double dirY, double dirZ) {
    var level = ctx.level();
    var horizon = Mth.clamp((float) ((dirY + 0.15) / 1.15), 0.0F, 1.0F);
    var rain = level.getRainLevel(1.0F);
    var thunder = level.getThunderLevel(1.0F);
    var clock = Math.floorMod(level.getOverworldClockTime(), 24_000L) / 24_000.0F;

    var zenith = 0xFF6EA7FF;
    var horizonColor = 0xFFC7DCF7;
    if (clock < 0.23F || clock > 0.77F) {
      zenith = 0xFF0B1028;
      horizonColor = 0xFF28365A;
    } else if ((clock > 0.23F && clock < 0.30F) || (clock > 0.70F && clock < 0.77F)) {
      zenith = 0xFF4D6FA6;
      horizonColor = 0xFFFFB067;
    }

    var sky = lerpColor(horizonColor, zenith, horizon);
    var cloudMask = proceduralClouds(dirX, dirY, dirZ, ctx.animationTick());
    sky = lerpColor(sky, 0xFFF2F5FA, cloudMask * horizon * (1.0F - rain * 0.7F));

    var dayCenter = 1.0F - Math.min(1.0F, Math.abs(clock - 0.5F) * 3.2F);
    var sunX = Math.cos((clock - 0.25F) * Math.PI * 2.0);
    var sunY = Math.sin((clock - 0.25F) * Math.PI * 2.0);
    var sunGlow = (float) Math.pow(Math.max(0.0, dirX * sunX + dirY * sunY), 48.0);
    sky = lerpColor(sky, 0xFFFFF1C7, sunGlow * dayCenter * (1.0F - rain * 0.8F));

    if (dayCenter < 0.15F && dirY > 0.15) {
      var stars = starField(dirX, dirY, dirZ);
      sky = lerpColor(sky, 0xFFFFFFFF, stars * (1.0F - dayCenter) * (1.0F - rain * 0.8F));
    }

    return multiplyColor(sky, 1.0F - thunder * 0.45F);
  }

  private static float proceduralClouds(double dirX, double dirY, double dirZ, long tick) {
    if (dirY <= 0.05) {
      return 0.0F;
    }

    var u = dirX * 42.0 + tick * 0.006;
    var v = dirZ * 42.0 + tick * 0.006;
    var n = (Math.sin(u) + Math.cos(v * 1.3) + Math.sin(u * 0.4 + v * 0.75)) / 3.0;
    return Mth.clamp((float) ((n - 0.1) * 1.5), 0.0F, 1.0F);
  }

  private static float starField(double dirX, double dirY, double dirZ) {
    var hash = Math.sin(dirX * 381.31 + dirY * 511.73 + dirZ * 197.47) * 43758.5453;
    var fract = hash - Math.floor(hash);
    return fract > 0.996 ? (float) ((fract - 0.996) / 0.004) : 0.0F;
  }

  private static int lerpColor(int from, int to, float amount) {
    amount = Mth.clamp(amount, 0.0F, 1.0F);
    var r = Mth.lerpInt(amount, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
    var g = Mth.lerpInt(amount, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
    var b = Mth.lerpInt(amount, from & 0xFF, to & 0xFF);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private static int multiplyColor(int color, float factor) {
    var r = Math.min(255, Math.max(0, (int) (((color >> 16) & 0xFF) * factor)));
    var g = Math.min(255, Math.max(0, (int) (((color >> 8) & 0xFF) * factor)));
    var b = Math.min(255, Math.max(0, (int) ((color & 0xFF) * factor)));
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }
}
