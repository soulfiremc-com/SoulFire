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

import com.google.gson.JsonElement;
import com.soulfiremc.grpc.generated.MinecraftAccountProto;
import com.soulfiremc.server.account.service.*;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.util.SFHelpers;
import lombok.With;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/// Represents an authenticated MC account. This can be a premium, offline or bedrock account. Beware
 /// that the profileId is not a valid online UUID for offline and bedrock accounts.
 ///
 /// @param authType           The type of authentication
 /// @param profileId          Identifier that uniquely identifies the account
 /// @param lastKnownName      The last known name of the account
 /// @param accountData        The data of the account (values depend on the authType)
 /// @param settings           Per-bot settings
 /// @param persistentMetadata Persistent metadata stored with the account
@With
public record MinecraftAccount(
  AuthType authType,
  UUID profileId,
  String lastKnownName,
  AccountData accountData,
  @Nullable Map<String, Map<String, JsonElement>> settings,
  @Nullable Map<String, Map<String, JsonElement>> persistentMetadata) implements SettingsSource.Stem<SettingsSource.Bot> {
  public MinecraftAccount {
    settings = SFHelpers.nullToEmptyMap(settings);
    persistentMetadata = SFHelpers.nullToEmptyMap(persistentMetadata);
  }

  public static MinecraftAccount fromProto(MinecraftAccountProto account) {
    return new MinecraftAccount(
      AuthType.valueOf(account.getType().name()),
      UUID.fromString(account.getProfileId()),
      account.getLastKnownName(),
      switch (account.getAccountDataCase()) {
        case ONLINE_CHAIN_JAVA_DATA -> OnlineChainJavaData.fromProto(account.getOnlineChainJavaData());
        case ONLINE_SIMPLE_JAVA_DATA -> OnlineSimpleJavaData.fromProto(account.getOnlineSimpleJavaData());
        case OFFLINE_JAVA_DATA -> OfflineJavaData.fromProto(account.getOfflineJavaData());
        case BEDROCK_DATA -> BedrockData.fromProto(account.getBedrockData());
        case ACCOUNTDATA_NOT_SET -> throw new IllegalArgumentException("AccountData not set");
      },
      SettingsSource.Stem.settingsFromProto(account.getConfigList()),
      SettingsSource.Stem.settingsFromProto(account.getPersistentMetadataList()));
  }

  @Override
  public String toString() {
    return "MinecraftAccount(authType=%s, profileId=%s, lastKnownName=%s)"
      .formatted(authType, profileId, lastKnownName);
  }

  public MinecraftAccountProto toProto() {
    var builder =
      MinecraftAccountProto.newBuilder()
        .setType(MinecraftAccountProto.AccountTypeProto.valueOf(authType.name()))
        .setProfileId(profileId.toString())
        .setLastKnownName(lastKnownName);

    SFHelpers.mustSupply(() -> switch (accountData) {
      case BedrockData bedrockData -> () -> builder.setBedrockData(bedrockData.toProto());
      case OfflineJavaData offlineJavaData -> () -> builder.setOfflineJavaData(offlineJavaData.toProto());
      case OnlineChainJavaData onlineChainJavaData -> () -> builder.setOnlineChainJavaData(onlineChainJavaData.toProto());
      case OnlineSimpleJavaData onlineSimpleJavaData -> () -> builder.setOnlineSimpleJavaData(onlineSimpleJavaData.toProto());
    });

    // Preserve per-account bot settings when instance config is serialized back to protobuf.
    builder.addAllConfig(SettingsSource.Stem.mapToSettingsNamespaceProto(settings));

    // Add persistent metadata
    builder.addAllPersistentMetadata(SettingsSource.Stem.mapToSettingsNamespaceProto(persistentMetadata));

    return builder.build();
  }
}
