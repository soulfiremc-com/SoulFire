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
package com.soulfiremc.server.spark;

import com.soulfiremc.builddata.BuildData;
import com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslationImpl;
import me.lucko.spark.common.platform.PlatformInfo;

public final class SFSparkPlatformInfo implements PlatformInfo {
  @Override
  public Type getType() {
    return Type.CLIENT;
  }

  @Override
  public String getName() {
    return "SoulFire";
  }

  @Override
  public String getBrand() {
    return "SoulFire";
  }

  @Override
  public String getVersion() {
    return BuildData.VERSION;
  }

  @Override
  public String getMinecraftVersion() {
    return ProtocolTranslationImpl.NATIVE_VERSION.getName();
  }
}
