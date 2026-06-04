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
package com.soulfiremc.server.account.service;

import com.soulfiremc.grpc.generated.MinecraftAccountProto;

/// Account data for Java Edition accounts authenticated through TheAltening.
/// The account token is the imported token used to obtain a current Yggdrasil access token.
public record TheAlteningJavaData(String accountToken, String accessToken) implements AccountData {
  public static TheAlteningJavaData fromProto(MinecraftAccountProto.TheAlteningJavaData data) {
    return new TheAlteningJavaData(data.getAccountToken(), data.getAccessToken());
  }

  public MinecraftAccountProto.TheAlteningJavaData toProto() {
    return MinecraftAccountProto.TheAlteningJavaData.newBuilder()
      .setAccountToken(accountToken)
      .setAccessToken(accessToken)
      .build();
  }
}
