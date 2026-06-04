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
package com.soulfiremc.server.account;

import com.soulfiremc.grpc.generated.AccountTypeCredentials;
import com.soulfiremc.grpc.generated.MinecraftAccountProto;
import com.soulfiremc.server.account.service.TheAlteningJavaData;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class TheAlteningAuthServiceTest {
  @Test
  void rejectsBlankAccountTokens() {
    assertThrows(
      IllegalArgumentException.class,
      () -> TheAlteningAuthService.INSTANCE.createData("  ")
    );
  }

  @Test
  void mapsTheAlteningAuthTypesToService() {
    assertSame(
      TheAlteningAuthService.INSTANCE,
      MCAuthService.convertService(AccountTypeCredentials.THE_ALTENING)
    );
    assertSame(
      TheAlteningAuthService.INSTANCE,
      MCAuthService.convertService(MinecraftAccountProto.AccountTypeProto.THE_ALTENING)
    );
    assertSame(
      TheAlteningAuthService.INSTANCE,
      MCAuthService.convertService(AuthType.THE_ALTENING)
    );
  }

  @Test
  void serializesTheAlteningAccountDataThroughProto() {
    var profileId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    var account = new MinecraftAccount(
      AuthType.THE_ALTENING,
      profileId,
      "AltUser",
      new TheAlteningJavaData("account-token", "access-token"),
      Map.of(),
      Map.of());

    var parsed = MinecraftAccount.fromProto(account.toProto());
    var accountData = assertInstanceOf(TheAlteningJavaData.class, parsed.accountData());

    assertEquals(AuthType.THE_ALTENING, parsed.authType());
    assertEquals(profileId, parsed.profileId());
    assertEquals("AltUser", parsed.lastKnownName());
    assertEquals("account-token", accountData.accountToken());
    assertEquals("access-token", accountData.accessToken());
  }
}
