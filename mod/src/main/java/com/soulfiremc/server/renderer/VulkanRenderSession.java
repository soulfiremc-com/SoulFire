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

import com.soulfiremc.mod.access.IFogEnvironmentState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import org.jspecify.annotations.Nullable;

/// One cached bot owns the native scene. Switching bots releases all previous scene resources.
final class VulkanRenderSession implements AutoCloseable {
  private final Minecraft minecraft;
  private final GameRenderer previousGameRenderer;
  private final LevelRenderer previousLevelRenderer;
  private final LevelExtractor previousExtractor;
  private boolean closed;

  VulkanRenderSession(Minecraft minecraft) {
    this.minecraft = minecraft;
    previousGameRenderer = minecraft.gameRenderer;
    previousLevelRenderer = minecraft.levelRenderer;
    previousExtractor = minecraft.levelExtractor;
    var renderer = new GameRenderer(minecraft, previousGameRenderer.firstPersonHandsAndItemsRenderer, minecraft.getModelManager(), minecraft.getItemModelResolver());
    copySimulationState(previousGameRenderer, renderer);
    renderer.lightmapRenderStateExtractor.needsUpdate = true;
    LevelRenderer levelRenderer = null;
    try {
      levelRenderer = new LevelRenderer(minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(),
        minecraft.getModelManager(), minecraft.getTextureManager(), minecraft.getAtlasManager(), minecraft.getShaderManager(),
        renderer, minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
      levelRenderer.cloudRenderer().texture = previousLevelRenderer.cloudRenderer().texture;
      var extractor = new LevelExtractor(minecraft, renderer.gameRenderState().levelRenderState, levelRenderer);
      extractor.setLevel(minecraft.level);
      renderer.setLevel(minecraft.level);
      minecraft.gameRenderer = renderer;
      minecraft.levelRenderer = levelRenderer;
      minecraft.levelExtractor = extractor;
      minecraft.gui.guiRenderState = renderer.gameRenderState().guiRenderState;
      if (minecraft.level != null) {
        // A new scene has not seen chunk notifications consumed by the previous scene.
        // Replay the current cache through vanilla's normal extraction path.
        var chunkCache = minecraft.level.getChunkSource();
        var chunks = chunkCache.storage.chunks;
        for (var index = 0; index < chunks.length(); index++) {
          var chunk = chunks.get(index);
          if (chunk == null) continue;
          var pos = chunk.getPos();
          chunkCache.addedLoadedChunks().add(pos.pack());
          for (var sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
            if (chunk.getSections()[sectionIndex].hasOnlyAir()) {
              chunkCache.addedEmptySections().add(SectionPos.asLong(pos.x(), chunk.getSectionYFromSectionIndex(sectionIndex), pos.z()));
            }
          }
        }
      }
    } catch (RuntimeException | Error error) {
      try {
        closeResources(renderer, levelRenderer);
      } catch (RuntimeException | Error closeError) {
        error.addSuppressed(closeError);
      }
      throw error;
    }
  }

  private static void copySimulationState(GameRenderer source, GameRenderer target) {
    var targetFog = ((IFogEnvironmentState) target.gameRenderState()).soulfire$fogEnvironments();
    targetFog.clear();
    targetFog.addAll(((IFogEnvironmentState) source.gameRenderState()).soulfire$fogEnvironments());
    target.bossOverlayWorldDarkening = source.bossOverlayWorldDarkening;
    target.bossOverlayWorldDarkeningO = source.bossOverlayWorldDarkeningO;
    target.renderBlockOutline = source.renderBlockOutline;
    target.spectatedEntityPostEffect = source.spectatedEntityPostEffect;
    target.spectatedEntityEffectActive = source.spectatedEntityEffectActive;
    target.mainCamera = source.mainCamera;
    target.lightmapRenderStateExtractor.blockLightFlicker = source.lightmapRenderStateExtractor.blockLightFlicker;
  }

  Minecraft minecraft() { return minecraft; }

  @Override
  public void close() {
    if (closed) return;
    VulkanRenderer.awaitDevice();
    closed = true;
    var renderer = minecraft.gameRenderer;
    var levelRenderer = minecraft.levelRenderer;
    copySimulationState(renderer, previousGameRenderer);
    minecraft.gameRenderer = previousGameRenderer;
    minecraft.levelRenderer = previousLevelRenderer;
    minecraft.levelExtractor = previousExtractor;
    minecraft.gui.guiRenderState = previousGameRenderer.gameRenderState().guiRenderState;
    try {
      previousExtractor.setLevel(minecraft.level);
      previousGameRenderer.setLevel(minecraft.level);
    } finally {
      closeResources(renderer, levelRenderer);
    }
  }

  private static void closeResources(GameRenderer renderer, @Nullable LevelRenderer levelRenderer) {
    try (var fixedBuffers = renderer.renderBuffers().fixedBufferPack(); renderer; levelRenderer) {
      // Vanilla closes its worker pool but leaves the fixed native buffers to the process lifetime.
    }
  }
}
