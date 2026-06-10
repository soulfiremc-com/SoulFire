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

import net.minecraft.util.Crypt;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftAuthUserApiServiceTest {
  @Test
  void convertsMinecraftAuthCertificatesToAuthlibKeyPairResponse() throws Exception {
    var keyPair = Crypt.generateKeyPair();
    var signature = new byte[]{1, 2, 3, 4};
    var expiresAt = Instant.now().plusSeconds(86_400).toEpochMilli();
    var certificates = new MinecraftPlayerCertificates(expiresAt, keyPair, signature, null);

    var response = MinecraftAuthUserApiService.toKeyPairResponse(certificates);

    assertArrayEquals(
      keyPair.getPrivate().getEncoded(),
      Crypt.stringToPemRsaPrivateKey(response.keyPair().privateKey()).getEncoded());
    assertArrayEquals(
      keyPair.getPublic().getEncoded(),
      Crypt.stringToRsaPublicKey(response.keyPair().publicKey()).getEncoded());
    assertArrayEquals(signature, response.publicKeySignature().array());
    assertEquals(Instant.ofEpochMilli(expiresAt), Instant.parse(response.expiresAt()));
  }
}
