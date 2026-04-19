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

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;

public final class RasterBuffers {
  private final BufferedImage image;
  private final int[] colorBuffer;
  private final float[] depthBuffer;

  public RasterBuffers(int width, int height) {
    this.image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    this.colorBuffer = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    this.depthBuffer = new float[width * height];
    clearDepth();
  }

  public void clearColor(int argb) {
    Arrays.fill(colorBuffer, argb);
  }

  public void clearDepth() {
    Arrays.fill(depthBuffer, Float.POSITIVE_INFINITY);
  }

  public BufferedImage image() {
    return image;
  }

  public int[] colorBuffer() {
    return colorBuffer;
  }

  public float[] depthBuffer() {
    return depthBuffer;
  }
}
