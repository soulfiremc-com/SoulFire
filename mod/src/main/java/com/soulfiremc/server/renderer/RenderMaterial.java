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

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

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
  AlphaCutoutSource alphaCutoutSource,
  DepthTest depthTest,
  boolean depthWrite,
  BlendState blendState,
  int colorWriteMask,
  UvTransform uvTransform,
  TextureSampleMode textureSampleMode,
  FogMode fogMode,
  boolean sortOnUpload,
  int sortGroup,
  float viewScale,
  @Nullable RendererAssets.TextureImage dissolveMaskTexture,
  @Nullable RendererAssets.TextureImage secondaryTexture,
  int portalLayers
) {
  private static final float PERSPECTIVE_LAYERING_UNIT = 1.0F / 4096.0F;
  private static final int DEFAULT_END_PORTAL_LAYERS = 15;
  static final int ONE_TENTH_ALPHA_CUTOUT_THRESHOLD = Math.clamp((int) Math.ceil(0.1F * 255.0F), 0, 255);

  public RenderMaterial(
    RendererAssets.TextureImage texture,
    RendererAssets.AlphaMode alphaMode,
    int color,
    boolean doubleSided,
    float depthBias,
    float polygonOffsetFactor,
    float polygonOffsetUnits,
    int alphaCutoutThreshold,
    AlphaCutoutSource alphaCutoutSource,
    DepthTest depthTest,
    boolean depthWrite,
    BlendState blendState,
    int colorWriteMask,
    UvTransform uvTransform,
    TextureSampleMode textureSampleMode,
    FogMode fogMode,
    boolean sortOnUpload,
    int sortGroup,
    float viewScale,
    @Nullable RendererAssets.TextureImage dissolveMaskTexture
  ) {
    this(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      polygonOffsetFactor,
      polygonOffsetUnits,
      alphaCutoutThreshold,
      alphaCutoutSource,
      depthTest,
      depthWrite,
      blendState,
      colorWriteMask,
      uvTransform,
      textureSampleMode,
      fogMode,
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      null,
      0
    );
  }

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
      AlphaCutoutSource.FINAL_COLOR,
      DepthTest.LESS_THAN_OR_EQUAL,
      defaultDepthWrite(alphaMode),
      defaultBlendState(alphaMode),
      ColorTargetState.WRITE_ALL,
      UvTransform.IDENTITY,
      TextureSampleMode.COLOR,
      FogMode.COLOR_MIX,
      defaultSortOnUpload(alphaMode),
      0,
      1.0F,
      null
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
      alphaCutoutSource,
      depthTest(depthStencilState),
      depthWrite(depthStencilState),
      blendState,
      colorWriteMask,
      uvTransform,
      textureSampleMode,
      fogMode,
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      secondaryTexture,
      portalLayers
    );
  }

  public RenderMaterial withDoubleSided(boolean doubleSided) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      polygonOffsetFactor,
      polygonOffsetUnits,
      alphaCutoutThreshold,
      alphaCutoutSource,
      depthTest,
      depthWrite,
      blendState,
      colorWriteMask,
      uvTransform,
      textureSampleMode,
      fogMode,
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      secondaryTexture,
      portalLayers
    );
  }

  public RenderMaterial withRenderType(RenderType renderType) {
    return withRenderType(renderType, System.identityHashCode(renderType));
  }

  public RenderMaterial withRenderType(RenderType renderType, int sortGroup) {
    var pipeline = renderType.pipeline();
    var fragmentShader = pipeline.getFragmentShader().getPath();
    var colorTargetState = pipeline.getColorTargetState();
    var pipelineAlphaMode = alphaMode(colorTargetState, alphaMode);
    return new RenderMaterial(
      textureWithRenderTypeAddressMode(texture, renderType, fragmentShader),
      pipelineAlphaMode,
      color,
      doubleSided || !pipeline.isCull(),
      depthBias,
      polygonOffsetFactor + depthBiasScaleFactor(pipeline.getDepthStencilState()),
      polygonOffsetUnits + depthBiasConstant(pipeline.getDepthStencilState()),
      shaderAlphaCutoutThreshold(pipeline, pipelineAlphaMode),
      alphaCutoutSource(renderType),
      depthTest(pipeline.getDepthStencilState()),
      depthWrite(pipeline.getDepthStencilState()),
      BlendState.from(colorTargetState.blendFunction().orElse(null)),
      colorTargetState.writeMask(),
      UvTransform.fromMatrix(renderType.state.textureTransform.createMatrix()),
      textureSampleMode(pipeline),
      fogMode(fragmentShader),
      renderType.sortOnUpload() && renderType.primitiveTopology() == PrimitiveTopology.QUADS,
      sortGroup,
      viewScale(renderType),
      dissolveMaskTexture,
      secondaryTexture,
      portalLayerCount(pipeline)
    );
  }

  public RenderMaterial withPipelineState(RenderPipeline pipeline) {
    var fragmentShader = pipeline.getFragmentShader().getPath();
    var colorTargetState = pipeline.getColorTargetState();
    var pipelineAlphaMode = alphaMode(colorTargetState, alphaMode);
    return new RenderMaterial(
      textureWithShaderAddressMode(texture, fragmentShader),
      pipelineAlphaMode,
      color,
      doubleSided || !pipeline.isCull(),
      depthBias,
      polygonOffsetFactor + depthBiasScaleFactor(pipeline.getDepthStencilState()),
      polygonOffsetUnits + depthBiasConstant(pipeline.getDepthStencilState()),
      shaderAlphaCutoutThreshold(pipeline, pipelineAlphaMode),
      alphaCutoutSource(fragmentShader),
      depthTest(pipeline.getDepthStencilState()),
      depthWrite(pipeline.getDepthStencilState()),
      BlendState.from(colorTargetState.blendFunction().orElse(null)),
      colorTargetState.writeMask(),
      uvTransform,
      textureSampleMode(pipeline),
      fogMode(fragmentShader),
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      secondaryTexture,
      portalLayerCount(pipeline)
    );
  }

  public RenderMaterial withDissolveMaskTexture(@Nullable RendererAssets.TextureImage dissolveMaskTexture) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      polygonOffsetFactor,
      polygonOffsetUnits,
      alphaCutoutThreshold,
      alphaCutoutSource,
      depthTest,
      depthWrite,
      blendState,
      colorWriteMask,
      uvTransform,
      textureSampleMode,
      fogMode,
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      secondaryTexture,
      portalLayers
    );
  }

  public RenderMaterial withSecondaryTexture(@Nullable RendererAssets.TextureImage secondaryTexture) {
    return new RenderMaterial(
      texture,
      alphaMode,
      color,
      doubleSided,
      depthBias,
      polygonOffsetFactor,
      polygonOffsetUnits,
      alphaCutoutThreshold,
      alphaCutoutSource,
      depthTest,
      depthWrite,
      blendState,
      colorWriteMask,
      uvTransform,
      textureSampleMode,
      fogMode,
      sortOnUpload,
      sortGroup,
      viewScale,
      dissolveMaskTexture,
      secondaryTexture,
      portalLayers
    );
  }

  public static int defaultAlphaCutoutThreshold(RendererAssets.AlphaMode alphaMode) {
    return switch (alphaMode) {
      case OPAQUE -> 0;
      case CUTOUT -> 128;
      case TRANSLUCENT -> 3;
    };
  }

  public static int shaderAlphaCutoutThreshold(RenderType renderType, RendererAssets.AlphaMode alphaMode) {
    return shaderAlphaCutoutThreshold(renderType.pipeline(), alphaMode);
  }

  public static int shaderAlphaCutoutThreshold(RenderPipeline pipeline, RendererAssets.AlphaMode alphaMode) {
    var alphaCutout = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
    if (alphaCutout != null) {
      try {
        return Math.clamp((int) Math.ceil(Float.parseFloat(alphaCutout) * 255.0F), 0, 255);
      } catch (NumberFormatException _) {
        return defaultAlphaCutoutThreshold(alphaMode);
      }
    }

    return switch (pipeline.getFragmentShader().getPath()) {
      case "core/glint",
           "core/particle",
           "core/text",
           "core/rendertype_crumbling",
           "core/rendertype_text",
           "core/rendertype_text_background",
           "core/rendertype_text_background_see_through",
           "core/rendertype_text_grayscale",
           "core/rendertype_text_grayscale_see_through",
           "core/rendertype_text_intensity",
           "core/rendertype_text_intensity_see_through",
           "core/rendertype_text_see_through" -> ONE_TENTH_ALPHA_CUTOUT_THRESHOLD;
      case "core/position_color" -> 1;
      default -> 0;
    };
  }

  private static AlphaCutoutSource alphaCutoutSource(RenderType renderType) {
    return alphaCutoutSource(renderType.pipeline().getFragmentShader().getPath());
  }

  private static AlphaCutoutSource alphaCutoutSource(String fragmentShader) {
    return switch (fragmentShader) {
      case "core/entity", "core/item" -> AlphaCutoutSource.TEXTURE;
      default -> AlphaCutoutSource.FINAL_COLOR;
    };
  }

  private static TextureSampleMode textureSampleMode(RenderType renderType) {
    return textureSampleMode(renderType.pipeline());
  }

  private static TextureSampleMode textureSampleMode(RenderPipeline pipeline) {
    return textureSampleMode(pipeline.getLocation().getPath(), pipeline.getFragmentShader().getPath());
  }

  private static TextureSampleMode textureSampleMode(String pipelinePath, String fragmentShader) {
    if (pipelinePath.endsWith("text_grayscale")
      || pipelinePath.endsWith("gui_text_grayscale")
      || pipelinePath.endsWith("text_grayscale_polygon_offset")
      || pipelinePath.endsWith("text_grayscale_see_through")) {
      return TextureSampleMode.INTENSITY;
    }

    return switch (fragmentShader) {
      case "core/rendertype_text_grayscale",
           "core/rendertype_text_grayscale_see_through",
           "core/rendertype_text_intensity",
           "core/rendertype_text_intensity_see_through" -> TextureSampleMode.INTENSITY;
      case "core/rendertype_end_portal" -> TextureSampleMode.END_PORTAL;
      default -> TextureSampleMode.COLOR;
    };
  }

  private static RendererAssets.TextureImage textureWithShaderAddressMode(RendererAssets.TextureImage texture, String fragmentShader) {
    return switch (fragmentShader) {
      case "core/rendertype_entity_shadow" -> texture.withAddressMode(RendererAssets.TextureAddressMode.CLAMP_TO_EDGE);
      default -> texture;
    };
  }

  private static RendererAssets.TextureImage textureWithRenderTypeAddressMode(
    RendererAssets.TextureImage texture,
    RenderType renderType,
    String fragmentShader
  ) {
    var sampler = RendererAssets.sampler(samplerSupplier(renderType, "Sampler0"));
    return sampler != null ? RendererAssets.withSamplerAddressMode(texture, sampler) : textureWithShaderAddressMode(texture, fragmentShader);
  }

  @Nullable
  private static Supplier<GpuSampler> samplerSupplier(RenderType renderType, String samplerName) {
    var state = renderType.state;
    if (state == null || state.textures == null) {
      return null;
    }

    var binding = state.textures.get(samplerName);
    if (binding == null || binding.sampler() == null) {
      return null;
    }
    return binding.sampler();
  }

  private static FogMode fogMode(String fragmentShader) {
    return switch (fragmentShader) {
      case "core/glint" -> FogMode.RGB_FADE;
      case "core/rendertype_lightning" -> FogMode.ALPHA_FADE;
      case "core/rendertype_beacon_beam" -> FogMode.DEPTH_COLOR_MIX;
      case "core/block",
           "core/entity",
           "core/item",
           "core/particle",
           "core/position",
           "core/rendertype_crumbling",
           "core/rendertype_end_portal",
           "core/rendertype_entity_shadow",
           "core/rendertype_leash",
           "core/rendertype_lines",
           "core/rendertype_text",
           "core/rendertype_text_background",
           "core/rendertype_text_grayscale",
           "core/rendertype_text_intensity",
           "core/sky",
           "core/terrain" -> FogMode.COLOR_MIX;
      default -> FogMode.NONE;
    };
  }

  public static boolean defaultDepthWrite(RendererAssets.AlphaMode alphaMode) {
    return alphaMode != RendererAssets.AlphaMode.TRANSLUCENT;
  }

  public static BlendState defaultBlendState(RendererAssets.AlphaMode alphaMode) {
    return alphaMode == RendererAssets.AlphaMode.TRANSLUCENT ? BlendState.from(BlendFunction.TRANSLUCENT) : BlendState.REPLACE;
  }

  public static boolean defaultSortOnUpload(RendererAssets.AlphaMode alphaMode) {
    return alphaMode == RendererAssets.AlphaMode.TRANSLUCENT;
  }

  private static RendererAssets.AlphaMode alphaMode(ColorTargetState colorTargetState, RendererAssets.AlphaMode fallback) {
    return colorTargetState.blendFunction().isPresent() ? RendererAssets.AlphaMode.TRANSLUCENT : fallback;
  }

  private static float viewScale(RenderType renderType) {
    var layering = renderType.state.layeringTransform;
    if (layering == LayeringTransform.VIEW_OFFSET_Z_LAYERING) {
      return 1.0F - PERSPECTIVE_LAYERING_UNIT;
    }
    if (layering == LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD) {
      return 1.0F + PERSPECTIVE_LAYERING_UNIT;
    }
    return 1.0F;
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

  private static int portalLayerCount(RenderPipeline pipeline) {
    if (!pipeline.getFragmentShader().getPath().equals("core/rendertype_end_portal")) {
      return 0;
    }

    var layerCount = pipeline.getShaderDefines().values().get("PORTAL_LAYERS");
    if (layerCount == null) {
      return DEFAULT_END_PORTAL_LAYERS;
    }

    try {
      return Math.clamp(Integer.parseInt(layerCount), 0, DEFAULT_END_PORTAL_LAYERS + 1);
    } catch (NumberFormatException _) {
      return DEFAULT_END_PORTAL_LAYERS;
    }
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
        return incoming == stored;
      }
    },
    NOT_EQUAL {
      @Override
      public boolean passes(float incoming, float stored) {
        return incoming != stored;
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
        case LESS_THAN -> GREATER_THAN;
        case LESS_THAN_OR_EQUAL -> GREATER_THAN_OR_EQUAL;
        case EQUAL -> EQUAL;
        case NOT_EQUAL -> NOT_EQUAL;
        case GREATER_THAN_OR_EQUAL -> LESS_THAN_OR_EQUAL;
        case GREATER_THAN -> LESS_THAN;
        case NEVER_PASS -> NEVER_PASS;
      };
    }
  }

  public enum AlphaCutoutSource {
    TEXTURE,
    FINAL_COLOR
  }

  public enum TextureSampleMode {
    COLOR,
    INTENSITY,
    END_PORTAL
  }

  public enum FogMode {
    NONE,
    COLOR_MIX,
    DEPTH_COLOR_MIX,
    ALPHA_FADE,
    RGB_FADE
  }

  public record BlendState(BlendFactor sourceColor, BlendFactor destColor, BlendFactor sourceAlpha, BlendFactor destAlpha) {
    public static final BlendState REPLACE = new BlendState(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO);

    public static BlendState from(@Nullable BlendFunction blendFunction) {
      return blendFunction == null
        ? REPLACE
        : new BlendState(
          blendFunction.color().sourceFactor(),
          blendFunction.color().destFactor(),
          blendFunction.alpha().sourceFactor(),
          blendFunction.alpha().destFactor()
        );
    }

    public boolean blends() {
      return sourceColor != BlendFactor.ONE
        || destColor != BlendFactor.ZERO
        || sourceAlpha != BlendFactor.ONE
        || destAlpha != BlendFactor.ZERO;
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
    private static final long GLINT_U_PERIOD_TICKS = 275L;
    private static final long GLINT_V_PERIOD_TICKS = 75L;

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

    public static UvTransform fromMatrix(Matrix4fc matrix) {
      return new UvTransform(
        matrix.m00(),
        matrix.m10(),
        matrix.m01(),
        matrix.m11(),
        matrix.m30(),
        matrix.m31(),
        0L,
        0L
      );
    }

    public float u(float u, float v, long animationTick) {
      return u * uFromU + v * uFromV + animatedOffset(uOffsetScale, animationTick, uPeriodTicks);
    }

    public float v(float u, float v, long animationTick) {
      return u * vFromU + v * vFromV + animatedOffset(vOffsetScale, animationTick, vPeriodTicks);
    }

    private static float animatedOffset(float offsetScale, long animationTick, long periodTicks) {
      if (periodTicks <= 0L) {
        return offsetScale;
      }

      return offsetScale * Math.floorMod(animationTick, periodTicks) / (float) periodTicks;
    }
  }
}
