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

import com.soulfiremc.mod.mixin.soulfire.GameRendererAccessor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VulkanRenderSessionTest {
  @Test
  void retainsFailuresAcrossSceneHandoffsWithoutSharingMutableLists() {
    var cached = new PostEffectState();
    cached.failed.add(GameRenderer.END_OF_FRAME_POST_EFFECT);
    var scene = new PostEffectState();
    VulkanRenderSession.copyPostEffectState(cached, scene);
    scene.failed.add(Identifier.withDefaultNamespace("blur"));
    assertEquals(List.of(GameRenderer.END_OF_FRAME_POST_EFFECT), cached.failed);

    VulkanRenderSession.copyPostEffectState(scene, cached);
    var replacement = new PostEffectState();
    VulkanRenderSession.copyPostEffectState(cached, replacement);
    assertEquals(scene.failed, replacement.failed);
    assertFalse(replacement.reset);
  }

  @Test
  void transfersReloadInvalidationAndPreservesReloadsOfAnInactiveRenderer() {
    var cached = new PostEffectState();
    cached.reset = true;
    var scene = new PostEffectState();
    VulkanRenderSession.copyPostEffectState(cached, scene);
    assertTrue(scene.reset);
    assertFalse(cached.reset);

    // Vanilla consumes the flag when preparing a frame. A later reload targets the cached renderer.
    scene.reset = false;
    cached.reset = true;
    VulkanRenderSession.copyPostEffectState(scene, cached);
    assertTrue(cached.reset);

    var replacement = new PostEffectState();
    VulkanRenderSession.copyPostEffectState(cached, replacement);
    assertTrue(replacement.reset);
    assertFalse(cached.reset);
  }

  private static final class PostEffectState implements GameRendererAccessor {
    private final List<Identifier> failed = new ArrayList<>();
    private boolean reset;

    @Override
    public List<Identifier> soulfire$failedPostEffects() { return failed; }

    @Override
    public boolean soulfire$shouldResetFailedPostEffects() { return reset; }

    @Override
    public void soulfire$shouldResetFailedPostEffects(boolean reset) { this.reset = reset; }
  }
}
