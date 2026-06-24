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

import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(TextureAtlas.class)
public abstract class MixinTextureAtlas {
  @Shadow
  @Final
  @Mutable
  private int maxSupportedTextureSize;

  @Shadow
  public List<TextureAtlasSprite> sprites;

  @Shadow
  private List<SpriteContents.AnimationState> animatedTexturesStates;

  @Shadow
  private Map<Identifier, TextureAtlasSprite> texturesByName;

  @Shadow
  private @Nullable TextureAtlasSprite missingSprite;

  @Shadow
  private int width;

  @Shadow
  private int height;

  @Shadow
  private int maxMipLevel;

  @Shadow
  private int mipLevelCount;

  @Shadow
  public abstract void clearTextureData();

  @Inject(method = "<init>", at = @At("TAIL"))
  private void initHook(Identifier location, CallbackInfo ci) {
    this.maxSupportedTextureSize = Math.max(this.maxSupportedTextureSize, 16_384);
  }

  @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
  private void uploadHook(SpriteLoader.Preparations preparations, CallbackInfo ci) {
    this.clearTextureData();
    this.texturesByName = Map.copyOf(preparations.regions());
    this.missingSprite = this.texturesByName.get(MissingTextureAtlasSprite.getLocation());
    if (this.missingSprite == null) {
      throw new IllegalStateException("Atlas has no missing texture sprite");
    }

    this.sprites = List.copyOf(preparations.regions().values());
    this.animatedTexturesStates = List.of();
    this.width = preparations.width();
    this.height = preparations.height();
    this.maxMipLevel = preparations.mipLevel();
    this.mipLevelCount = preparations.mipLevel() + 1;
    ci.cancel();
  }

  @Inject(method = "cycleAnimationFrames", at = @At("HEAD"), cancellable = true)
  private void cycleAnimationFramesHook(CallbackInfo ci) {
    ci.cancel();
  }
}
