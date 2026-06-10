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

import com.mojang.authlib.minecraft.TelemetrySession;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.minecraft.report.AbuseReportLimits;
import com.mojang.authlib.yggdrasil.request.AbuseReportRequest;
import com.mojang.authlib.yggdrasil.response.KeyPairResponse;
import net.minecraft.util.Crypt;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.java.model.MinecraftPlayerCertificates;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/// Bridges minecraftauth's stored Java authentication chain into Minecraft's profile key API.
public final class MinecraftAuthUserApiService implements UserApiService {
  private static final Duration PROFILE_KEY_REFRESH_MARGIN = Duration.ofHours(1);

  private final JavaAuthManager authManager;

  public MinecraftAuthUserApiService(JavaAuthManager authManager) {
    this.authManager = authManager;
  }

  @Override
  public UserProperties fetchProperties() {
    return UserApiService.OFFLINE_PROPERTIES;
  }

  @Override
  public boolean isBlockedPlayer(UUID playerID) {
    return false;
  }

  @Override
  public void refreshBlockList() {
  }

  @Override
  public TelemetrySession newTelemetrySession(Executor executor) {
    return TelemetrySession.DISABLED;
  }

  @Override
  public @Nullable KeyPairResponse getKeyPair() {
    var holder = authManager.getMinecraftPlayerCertificates();
    var certificates = holder.getCached();
    if (certificates == null || shouldRefresh(certificates)) {
      certificates = holder.refreshUnchecked();
    }

    return toKeyPairResponse(certificates);
  }

  @Override
  public void reportAbuse(AbuseReportRequest request) {
  }

  @Override
  public boolean canSendReports() {
    return false;
  }

  @Override
  public AbuseReportLimits getAbuseReportLimits() {
    return AbuseReportLimits.DEFAULTS;
  }

  private static boolean shouldRefresh(MinecraftPlayerCertificates certificates) {
    return refreshedAfter(certificates).isBefore(Instant.now());
  }

  static KeyPairResponse toKeyPairResponse(MinecraftPlayerCertificates certificates) {
    var keyPair = certificates.getKeyPair();
    return new KeyPairResponse(
      new KeyPairResponse.KeyPair(
        Crypt.pemRsaPrivateKeyToString(keyPair.getPrivate()),
        Crypt.rsaPublicKeyToString(keyPair.getPublic())),
      ByteBuffer.wrap(certificates.getPublicKeySignature()),
      expiresAt(certificates).toString(),
      refreshedAfter(certificates).toString());
  }

  private static Instant expiresAt(MinecraftPlayerCertificates certificates) {
    return Instant.ofEpochMilli(certificates.getExpireTimeMs());
  }

  private static Instant refreshedAfter(MinecraftPlayerCertificates certificates) {
    return expiresAt(certificates).minus(PROFILE_KEY_REFRESH_MARGIN);
  }
}
