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

import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;

/// Raster state shared by all triangles emitted from one source primitive.
public record RenderMaterial(
  RendererAssets.TextureImage texture,
  RendererAssets.AlphaMode alphaMode,
  int color,
  boolean doubleSided,
  float depthBias,
  int alphaCutoutThreshold,
  DepthTest depthTest,
  boolean depthWrite
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
      alphaCutoutThreshold,
      DepthTest.LESS_THAN_OR_EQUAL,
      defaultDepthWrite(alphaMode)
    );
  }

  public RenderMaterial withDepthState(DepthStencilState depthStencilState) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias + depthBias(depthStencilState),
      alphaCutoutThreshold,
      depthTest(depthStencilState),
      depthWrite(depthStencilState)
    );
  }

  public RenderMaterial withDepthTest(DepthTest depthTest, boolean depthWrite) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      alphaCutoutThreshold,
      depthTest,
      depthWrite
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

  private static DepthTest depthTest(DepthStencilState depthStencilState) {
    return depthStencilState == null ? DepthTest.ALWAYS_PASS : DepthTest.fromCompareOp(depthStencilState.depthTest());
  }

  private static boolean depthWrite(DepthStencilState depthStencilState) {
    return depthStencilState != null && depthStencilState.writeDepth();
  }

  private static float depthBias(DepthStencilState depthStencilState) {
    if (depthStencilState == null) {
      return 0.0F;
    }

    return (depthStencilState.depthBiasScaleFactor() + depthStencilState.depthBiasConstant()) * 0.01F;
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
}
