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

import net.minecraft.client.CloudStatus;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudMeshCollectorTest {
  @Test
  void cloudTextureDataUsesVanillaAlphaCutoffAndNeighborFlags() {
    var image = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(1, 1, 0x09FFFFFF);
    image.setRGB(2, 1, 0x0AFFFFFF);

    var textureData = CloudMeshCollector.buildTextureData(RendererAssets.TextureImage.from(image, null));

    assertEquals(0L, textureData.cells()[1 + 1 * textureData.width()]);
    var cellData = textureData.cells()[2 + 1 * textureData.width()];
    assertTrue(cellData != 0L);
    assertTrue(((cellData >> 3) & 1L) != 0L);
    assertTrue(((cellData >> 2) & 1L) != 0L);
    assertTrue(((cellData >> 1) & 1L) != 0L);
    assertTrue((cellData & 1L) != 0L);
  }

  @Test
  void cloudTextureDataWrapsHorizontalNeighborsByTextureWidth() {
    var image = new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(1, 0, 0x0AFFFFFF);
    image.setRGB(2, 0, 0x0AFFFFFF);

    var textureData = CloudMeshCollector.buildTextureData(RendererAssets.TextureImage.from(image, null));

    var leftCellData = textureData.cells()[1];
    var rightCellData = textureData.cells()[2];
    assertTrue(leftCellData != 0L);
    assertTrue(rightCellData != 0L);
    assertEquals(0L, (leftCellData >> 2) & 1L);
    assertEquals(0L, rightCellData & 1L);
  }

  @Test
  void fancyCloudsEmitDedicatedDepthWritingCloudQuads() {
    var textureData = CloudMeshCollector.buildTextureData(solidTexture(0xFFFFFFFF));
    var camera = new Camera(new Vec3(0.0, 64.0, 0.0), 0.0F, 0.0F, 64, 64, 70.0, 256.0F);

    var scene = CloudMeshCollector.collect(camera, textureData, CloudStatus.FANCY, 0xFFFFFFFF, 96.0F, 2, 0L, 128.0F);

    assertEquals(0, scene.opaque().length);
    assertEquals(0, scene.cutout().length);
    assertEquals(0, scene.translucent().length);
    assertTrue(scene.clouds().length > 0);
    assertEquals(0, scene.weather().length);
    assertTrue(scene.clouds()[0].material().depthWrite());
    assertEquals(RenderMaterial.DepthTest.LESS_THAN_OR_EQUAL, scene.clouds()[0].material().depthTest());
  }

  private static RendererAssets.TextureImage solidTexture(int color) {
    var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, color);
    return RendererAssets.TextureImage.from(image, null);
  }
}
