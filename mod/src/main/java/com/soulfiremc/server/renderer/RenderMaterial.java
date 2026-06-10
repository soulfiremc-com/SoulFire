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

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import net.minecraft.client.renderer.rendertype.RenderType;

import org.jetbrains.annotations.Nullable;

/// Raster state shared by all triangles emitted from one source primitive.
public record RenderMaterial(
  RendererAssets.TextureImage texture,
  RendererAssets.AlphaMode alphaMode,
  int color,
  boolean doubleSided,
  float depthBias,
  float polygonOffsetFactor,
  float polygonOffsetUnits,
  int alphaCutoutThreshold,
  DepthTest depthTest,
  boolean depthWrite,
  BlendState blendState,
  int colorWriteMask,
  UvTransform uvTransform
) {
  public static RenderMaterial create(
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    boolean doubleSided,
    float depthBias
  ) {
    return create(texture, alphaMode, color, doubleSided, depthBias, defaultAlphaCutoutThreshold(alphaMode));
  }

  public static RenderMaterial create(
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    boolean doubleSided,
    float depthBias,
    int alphaCutoutThreshold
  ) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      0.0F,
      0.0F,
      alphaCutoutThreshold,
      DepthTest.LESS_THAN_OR_EQUAL,
      defaultDepthWrite(alphaMode),
      defaultBlendState(alphaMode),
      ColorTargetState.WRITE_ALL,
      UvTransform.IDENTITY
    );
  }

  public RenderMaterial withDepthState(@Nullable DepthStencilState depthStencilState) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      polygonOffsetFactor + depthBiasScaleFactor(depthStencilState),
      polygonOffsetUnits + depthBiasConstant(depthStencilState),
      alphaCutoutThreshold,
      depthTest(depthStencilState),
      depthWrite(depthStencilState),
      blendState,
      colorWriteMask,
      uvTransform
    );
  }

  public RenderMaterial withRenderType(RenderType renderType) {
    var pipeline = renderType.pipeline();
    var colorTargetState = pipeline.getColorTargetState();
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided || !pipeline.isCull(),
      depthBias,
      polygonOffsetFactor + depthBiasScaleFactor(pipeline.getDepthStencilState()),
      polygonOffsetUnits + depthBiasConstant(pipeline.getDepthStencilState()),
      alphaCutoutThreshold,
      depthTest(pipeline.getDepthStencilState()),
      depthWrite(pipeline.getDepthStencilState()),
      BlendState.from(colorTargetState.blendFunction().orElse(null)),
      colorTargetState.writeMask(),
      uvTransform
    );
  }

  public static int defaultAlphaCutoutThreshold(RendererAssets.AlphaMode alphaMode) {
    return switch (alphaMode) {
      case OPAQUE -> 0;
      case CUTOUT -> 128;
      case TRANSLUCENT -> 3;
    };
  }

  public static boolean defaultDepthWrite(RendererAssets.AlphaMode alphaMode) {
    return alphaMode != RendererAssets.AlphaMode.TRANSLUCENT;
  }

  public static BlendState defaultBlendState(RendererAssets.AlphaMode alphaMode) {
    return alphaMode == RendererAssets.AlphaMode.TRANSLUCENT ? BlendState.from(BlendFunction.TRANSLUCENT) : BlendState.REPLACE;
  }

  private static DepthTest depthTest(@Nullable DepthStencilState depthStencilState) {
    return depthStencilState == null ? DepthTest.ALWAYS_PASS : DepthTest.fromCompareOp(depthStencilState.depthTest());
  }

  private static boolean depthWrite(@Nullable DepthStencilState depthStencilState) {
    return depthStencilState != null && depthStencilState.writeDepth();
  }

  private static float depthBiasScaleFactor(@Nullable DepthStencilState depthStencilState) {
    if (depthStencilState == null) {
      return 0.0F;
    }

    return depthStencilState.depthBiasScaleFactor();
  }

  private static float depthBiasConstant(@Nullable DepthStencilState depthStencilState) {
    if (depthStencilState == null) {
      return 0.0F;
    }

    return depthStencilState.depthBiasConstant();
  }

  public enum DepthTest {
    ALWAYS_PASS {
      @Override
      public boolean passes(float incoming, float stored) {
        return true;
      }
    },
    LESS_THAN {
      @Override
      public boolean passes(float incoming, float stored) {
        return incoming < stored;
      }
    },
    LESS_THAN_OR_EQUAL {
      @Override
      public boolean passes(float incoming, float stored) {
        return incoming <= stored;
      }
    },
    EQUAL {
      @Override
      public boolean passes(float incoming, float stored) {
        return Math.abs(incoming - stored) <= 1.0E-5F;
      }
    },
    NOT_EQUAL {
      @Override
      public boolean passes(float incoming, float stored) {
        return Math.abs(incoming - stored) > 1.0E-5F;
      }
    },
    GREATER_THAN_OR_EQUAL {
      @Override
      public boolean passes(float incoming, float stored) {
        return incoming >= stored;
      }
    },
    GREATER_THAN {
      @Override
      public boolean passes(float incoming, float stored) {
        return incoming > stored;
      }
    },
    NEVER_PASS {
      @Override
      public boolean passes(float incoming, float stored) {
        return false;
      }
    };

    public abstract boolean passes(float incoming, float stored);

    private static DepthTest fromCompareOp(CompareOp compareOp) {
      return switch (compareOp) {
        case ALWAYS_PASS -> ALWAYS_PASS;
        case LESS_THAN -> LESS_THAN;
        case LESS_THAN_OR_EQUAL -> LESS_THAN_OR_EQUAL;
        case EQUAL -> EQUAL;
        case NOT_EQUAL -> NOT_EQUAL;
        case GREATER_THAN_OR_EQUAL -> GREATER_THAN_OR_EQUAL;
        case GREATER_THAN -> GREATER_THAN;
        case NEVER_PASS -> NEVER_PASS;
      };
    }
  }

  public record BlendState(SourceFactor sourceColor, DestFactor destColor, SourceFactor sourceAlpha, DestFactor destAlpha) {
    public static final BlendState REPLACE = new BlendState(SourceFactor.ONE, DestFactor.ZERO, SourceFactor.ONE, DestFactor.ZERO);

    public static BlendState from(@Nullable BlendFunction blendFunction) {
      return blendFunction == null
        ? REPLACE
        : new BlendState(blendFunction.sourceColor(), blendFunction.destColor(), blendFunction.sourceAlpha(), blendFunction.destAlpha());
    }

    public boolean blends() {
      return sourceColor != SourceFactor.ONE
        || destColor != DestFactor.ZERO
        || sourceAlpha != SourceFactor.ONE
        || destAlpha != DestFactor.ZERO;
    }
  }

  public record UvTransform(
    float uFromU,
    float uFromV,
    float vFromU,
    float vFromV,
    float uOffsetScale,
    float vOffsetScale,
    long uPeriodTicks,
    long vPeriodTicks
  ) {
    public static final UvTransform IDENTITY = new UvTransform(1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0L, 0L);
    private static final long GLINT_U_PERIOD_TICKS = 2_200L;
    private static final long GLINT_V_PERIOD_TICKS = 600L;

    public static UvTransform glint(float scale) {
      var angle = (float) (Math.PI / 18.0);
      var sin = (float) Math.sin(angle);
      var cos = (float) Math.cos(angle);
      return new UvTransform(
        cos * scale,
        -sin * scale,
        sin * scale,
        cos * scale,
        -1.0F,
        1.0F,
        GLINT_U_PERIOD_TICKS,
        GLINT_V_PERIOD_TICKS
      );
    }

    public float u(float u, float v, long animationTick) {
      return u * uFromU + v * uFromV + uOffsetScale * offset(animationTick, uPeriodTicks);
    }

    public float v(float u, float v, long animationTick) {
      return u * vFromU + v * vFromV + vOffsetScale * offset(animationTick, vPeriodTicks);
    }

    private static float offset(long animationTick, long periodTicks) {
      if (periodTicks <= 0L) {
        return 0.0F;
      }

      return Math.floorMod(animationTick, periodTicks) / (float) periodTicks;
    }
  }
}
