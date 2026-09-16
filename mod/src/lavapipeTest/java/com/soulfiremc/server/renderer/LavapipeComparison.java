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

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/// Runs only in the separate manual native client, without SoulFire's headless lifecycle.
public final class LavapipeComparison {
  private static final boolean HEADLESS = Boolean.getBoolean("sf.lavapipe.headless");
  private static final long START = System.nanoTime();
  private static final Path OUTPUT = Path.of(System.getProperty("sf.lavapipe.output"));
  private static final String SCENE = System.getProperty("sf.lavapipe.scene", "items");
  private static final ComparisonScenario SCENARIO = ComparisonScenario.named(SCENE);
  private static FixtureWorld fixture;
  private static final boolean STRESS = SCENE.startsWith("stress-");
  private static final boolean INVENTORY = SCENE.equals("inventory");
  private static long particleSeed;
  private static long entitySeed;
  private static boolean prepared;
  private static int frames;
  private static boolean capturing;
  private static boolean finished;

  private LavapipeComparison() {}

  public static void beforeExtract(Minecraft minecraft) {
    if (STRESS && prepared && !finished) {
      if (fixture != null) fixture.freeze();
      else StressComparisonScene.freeze(minecraft);
    } else if (INVENTORY) {
      InventoryComparisonScene.freezeEnvironment(minecraft);
    }
  }

  public static void resetEntitySeed(long seed) {
    entitySeed = seed;
  }

  public static RandomSource entityRandom() {
    return RandomSource.create(entitySeed++);
  }

  public static void resetParticleSeed(long seed) {
    particleSeed = seed;
  }

  public static RandomSource particleRandom() {
    return RandomSource.create(particleSeed++);
  }

  public static float partialTick() {
    return SCENARIO.partialTick();
  }

  public static boolean isStressScene() {
    return STRESS;
  }

  public static boolean freezeSimulation() {
    return (STRESS || INVENTORY) && prepared && !finished;
  }

  public static void afterFrame(Minecraft minecraft) {
    if (finished) {
      minecraft.stop();
      return;
    }
    if (System.nanoTime() - START > 120_000_000_000L) {
      throw new IllegalStateException("Lavapipe test timed out; current screen: " + minecraft.gui.screen());
    }
    if (minecraft.gui.overlay() != null || minecraft.level == null || minecraft.player == null) {
      return;
    }
    if (!prepared) {
      if (STRESS && !SCENARIO.isolatedWorld() && !StressComparisonScene.ready(minecraft)) return;
      var info = RenderSystem.getDevice().getDeviceInfo();
      if (!info.backendName().toLowerCase(Locale.ROOT).contains("vulkan")) {
        throw new IllegalStateException("Expected Vulkan, got " + info);
      }
      minecraft.options.guiScale().set(2);
      minecraft.options.pauseOnLostFocus = false;
      if (SCENARIO.isolatedWorld()) {
        fixture = EnvironmentComparisonScenes.prepare(minecraft, SCENARIO);
      } else if (STRESS) {
        StressComparisonScene.prepare(minecraft, SCENE);
      } else if (INVENTORY) {
        InventoryComparisonScene.prepare(minecraft);
      } else {
        minecraft.gui.setScreen(new ComparisonScreen());
      }
      prepared = true;
      beforeExtract(minecraft);
      return;
    }
    if (HEADLESS) {
      beforeExtract(minecraft);
      // Advance the same two seconds of client animation as the 30 FPS reference warm-up.
      java.util.concurrent.locks.LockSupport.parkNanos(33_333_333L);
    }
    minecraft.gui.toastManager().clear();
    if (++frames < (INVENTORY || STRESS ? 60 : 8) || capturing || !HEADLESS && STRESS && !minecraft.levelRenderer.hasRenderedAllSections()) {
      return;
    }
    capturing = true;
    try {
      Files.createDirectories(OUTPUT);
      Files.writeString(OUTPUT.resolve("device.txt"), RenderSystem.getDevice().getDeviceInfo().toString());
      var target = minecraft.gameRenderer.mainRenderTarget();
      if (HEADLESS) {
        if (minecraft.windowSurface() != null) throw new IllegalStateException("Headless capture created a presentation surface");
        beforeExtract(minecraft);
        minecraft.gameRenderer.update(minecraft.getDeltaTracker());
        if (Boolean.getBoolean("sf.lavapipe.benchmark")) {
          HeadlessRendererBenchmark.run(minecraft, OUTPUT, SCENE, SCENARIO.isolatedWorld());
          finished = true;
          return;
        }
        var actual = renderOffscreen(minecraft, 854, 480);
        ImageIO.write(actual, "PNG", OUTPUT.resolve("headless.png").toFile());
        var referencePath = OUTPUT.resolve("lavapipe.png");
        if (Files.isRegularFile(referencePath)) compare(ImageIO.read(referencePath.toFile()), actual);
        else Files.writeString(OUTPUT.resolve("metrics.json"), "{\"headlessCapture\":true}");
        finished = true;
      } else {
        Screenshot.takeScreenshot(target, nativeImage -> {
          try (nativeImage) {
            nativeImage.writeToFile(OUTPUT.resolve("lavapipe.png"));
            Files.writeString(OUTPUT.resolve("metrics.json"), "{\"referenceCapture\":true}");
            finished = true;
          } catch (IOException e) { throw new UncheckedIOException(e); }
        });
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage renderOffscreen(Minecraft minecraft, int width, int height) throws IOException {
    if (fixture != null) return fixture.renderOffscreen(width, height, OUTPUT);
    if (STRESS) return StressComparisonScene.renderOffscreen(minecraft, width, height, OUTPUT, SCENE);
    if (INVENTORY) {
      return InventoryComparisonScene.renderOffscreen(minecraft, width, height, OUTPUT);
    }
    return VulkanRenderer.renderGui(minecraft, width, height, 2, ComparisonScreen::draw);
  }

  private static void compare(BufferedImage reference, BufferedImage headless) throws IOException {
    var width = reference.getWidth();
    var height = reference.getHeight();
    if (headless.getWidth() != width || headless.getHeight() != height) {
      throw new IllegalStateException("Framebuffer size changed during the comparison");
    }
    var colors = new HashSet<Integer>();
    var diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    var comparison = new BufferedImage(width * 3, height, BufferedImage.TYPE_INT_RGB);
    long absoluteError = 0;
    var changed = 0;
    var significant = 0;
    var maximum = 0;
    var inventoryErrors = new DifferenceStats();
    var outsideErrors = new DifferenceStats();
    var scale = Minecraft.getInstance().getWindow().getGuiScale();
    var inventoryX = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - 176) / 2 * scale;
    var inventoryY = (Minecraft.getInstance().getWindow().getGuiScaledHeight() - 166) / 2 * scale;
    for (var y = 0; y < height; y++) {
      for (var x = 0; x < width; x++) {
        var a = reference.getRGB(x, y);
        colors.add(a);
        var b = headless.getRGB(x, y);
        var largest = 0;
        var color = 0;
        for (var shift : new int[]{24, 16, 8, 0}) {
          var delta = Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
          absoluteError += delta;
          largest = Math.max(largest, delta);
          if (shift != 24) {
            var alphaDelta = Math.abs((a >>> 24) - (b >>> 24));
            color |= Math.min(255, Math.max(delta, alphaDelta) * 8) << shift;
          }
        }
        if (largest > 0) {
          changed++;
        }
        if (largest > 2) {
          significant++;
        }
        maximum = Math.max(maximum, largest);
        if (INVENTORY) {
          var inside = x >= inventoryX && x < inventoryX + 176 * scale
            && y >= inventoryY && y < inventoryY + 166 * scale;
          (inside ? inventoryErrors : outsideErrors).add(a, b);
        }
        diff.setRGB(x, y, color);
        comparison.setRGB(x, y, a);
        comparison.setRGB(x + width, y, b);
        comparison.setRGB(x + width * 2, y, color);
      }
    }
    if (colors.size() < 32) {
      throw new IllegalStateException("Lavapipe produced an empty or incomplete fixture image");
    }
    ImageIO.write(diff, "PNG", OUTPUT.resolve("diff.png").toFile());
    ImageIO.write(comparison, "PNG", OUTPUT.resolve("comparison.png").toFile());
    var report = String.format(Locale.ROOT, """
      {"width": %d, "height": %d, "changedPixels": %d, "pixelsAboveTwo": %d,
       "maxChannelError": %d, "meanAbsoluteChannelError": %.6f, "diffAmplification": 8, "channels": "RGBA"}
      """, width, height, changed, significant, maximum, absoluteError / (width * (double) height * 4));
    if (INVENTORY) {
      var json = JsonParser.parseString(report).getAsJsonObject();
      var gson = new GsonBuilder().setPrettyPrinting().create();
      json.add("inventory", gson.toJsonTree(inventoryErrors));
      json.add("outsideInventory", gson.toJsonTree(outsideErrors));
      report = gson.toJson(json);
    }
    Files.writeString(OUTPUT.resolve("metrics.json"), report);
    System.out.println("Lavapipe comparison written to " + OUTPUT + "\n" + report);
    if (changed != 0) {
      throw new IllegalStateException("Renderer parity failed: " + changed + " pixels differ; maximum channel error " + maximum);
    }
  }

  private static final class DifferenceStats {
    private int pixels;
    private int changedPixels;
    private int pixelsAboveTwo;
    private int maxChannelError;
    private long absoluteChannelError;

    private void add(int reference, int software) {
      pixels++;
      var maximum = 0;
      for (var shift : new int[]{24, 16, 8, 0}) {
        var error = Math.abs(((reference >>> shift) & 255) - ((software >>> shift) & 255));
        absoluteChannelError += error;
        maximum = Math.max(maximum, error);
      }
      if (maximum > 0) {
        changedPixels++;
      }
      if (maximum > 2) {
        pixelsAboveTwo++;
      }
      maxChannelError = Math.max(maxChannelError, maximum);
    }
  }

  private static final class ComparisonScreen extends Screen {
    private static final List<Item> ITEMS = List.of(
      Items.COMPASS, Items.DIAMOND, Items.DIAMOND_SWORD, Items.APPLE, Items.POTION, Items.TIPPED_ARROW,
      Items.STONE, Items.OAK_LOG, Items.CHEST, Items.CRAFTING_TABLE, Items.LANTERN, Items.CAMPFIRE,
      Items.SHIELD, Items.TRIDENT, Items.SPYGLASS, Items.DECORATED_POT, Items.DRAGON_HEAD, Items.PLAYER_HEAD,
      Items.BANNER.pick(DyeColor.WHITE), Items.CONDUIT, Items.SHULKER_BOX, Items.MINECART, Items.OAK_BOAT, Items.BELL
    );

    private ComparisonScreen() {
      super(Component.literal("Lavapipe comparison"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
      draw(graphics);
    }

    private static void draw(GuiGraphicsExtractor graphics) {
      Minecraft.getInstance().gui.toastManager().clear();
      graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF202020);
      var font = Minecraft.getInstance().font;
      graphics.text(font, "Vanilla items / 3D models / clipped icons", 12, 10, 0xFFFFFFFF);
      for (var index = 0; index < ITEMS.size(); index++) {
        var x = 14 + (index % 12) * 32;
        var y = 32 + (index / 12) * 36;
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF808080);
        graphics.fill(x, y, x + 16, y + 16, 0xFF373737);
        graphics.fakeItem(new ItemStack(ITEMS.get(index)), x, y);
      }
      graphics.text(font, "Scissor: exactly half of each icon", 12, 111, 0xFFFFFFFF);
      for (var index = 0; index < 12; index++) {
        var x = 14 + index * 32;
        graphics.fill(x, 130, x + 16, 146, 0xFF808080);
        graphics.enableScissor(x, 130, x + 8, 146);
        graphics.fakeItem(new ItemStack(ITEMS.get(index)), x, 130);
        graphics.disableScissor();
      }
    }
  }
}
