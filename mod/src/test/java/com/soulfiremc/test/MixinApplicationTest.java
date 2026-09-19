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
package com.soulfiremc.test;

import com.google.gson.JsonParser;
import com.soulfiremc.test.utils.TestBootstrap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class MixinApplicationTest {
  @Test
  void applyMinecraftMixins() throws Exception {
    var loader = TestBootstrap.testClassLoader();
    var targets = new TreeSet<String>();
    try (var reader = new InputStreamReader(loader.getResourceAsStream("soulfire.mixins.json"), StandardCharsets.UTF_8)) {
      var config = JsonParser.parseReader(reader).getAsJsonObject();
      var mixins = config.getAsJsonArray("mixins");
      mixins.addAll(config.getAsJsonArray("client"));
      for (var mixin : mixins) {
        var path = (config.get("package").getAsString() + "." + mixin.getAsString()).replace('.', '/') + ".class";
        var node = new ClassNode();
        try (var input = loader.getResourceAsStream(path)) {
          new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        for (var annotation : node.invisibleAnnotations) {
          if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
          for (var index = 0; index < annotation.values.size(); index += 2) {
            var name = annotation.values.get(index);
            if (!name.equals("value") && !name.equals("targets")) continue;
            for (var target : (List<?>) annotation.values.get(index + 1)) {
              var className = target instanceof Type type ? type.getClassName() : target.toString();
              if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) targets.add(className);
            }
          }
        }
      }
    }
    assertAll(targets.stream().map(target ->
      () -> assertDoesNotThrow(() -> Class.forName(target, false, loader), target)));
  }
}
