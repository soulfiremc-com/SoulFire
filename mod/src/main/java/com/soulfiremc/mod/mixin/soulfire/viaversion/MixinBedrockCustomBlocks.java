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
package com.soulfiremc.mod.mixin.soulfire.viaversion;

import com.viaversion.viafabricplus.bedrock.block.BedrockCustomBlocks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/// ViaFabricPlus Bedrock caches one custom block snapshot per process, keyed by the connection.
/// Bots on different connections would rebuild it on almost every collision check, so each
/// thread keeps its own snapshot.
@Mixin(value = BedrockCustomBlocks.class, remap = false)
public class MixinBedrockCustomBlocks {
  @Unique
  private static final ThreadLocal<Object> soulfire$threadSnapshot = new ThreadLocal<>();

  @Coerce
  @Redirect(
    method = {"currentSnapshot", "createSnapshot"},
    at = @At(
      value = "FIELD",
      target = "Lcom/viaversion/viafabricplus/bedrock/block/BedrockCustomBlocks;snapshot:Lcom/viaversion/viafabricplus/bedrock/block/BedrockCustomBlocks$Snapshot;",
      opcode = Opcodes.GETSTATIC))
  private static Object readThreadSnapshot() {
    var snapshot = soulfire$threadSnapshot.get();
    // The process-wide snapshot is empty until this thread builds its own.
    return snapshot != null ? snapshot : soulfire$sharedSnapshot();
  }

  @Redirect(
    method = "createSnapshot",
    at = @At(
      value = "FIELD",
      target = "Lcom/viaversion/viafabricplus/bedrock/block/BedrockCustomBlocks;snapshot:Lcom/viaversion/viafabricplus/bedrock/block/BedrockCustomBlocks$Snapshot;",
      opcode = Opcodes.PUTSTATIC))
  private static void writeThreadSnapshot(@Coerce Object snapshot) {
    soulfire$threadSnapshot.set(snapshot);
  }

  @Unique
  private static Object soulfire$sharedSnapshot() {
    try {
      var field = BedrockCustomBlocks.class.getDeclaredField("snapshot");
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Cannot read the ViaFabricPlus Bedrock block snapshot", e);
    }
  }
}
