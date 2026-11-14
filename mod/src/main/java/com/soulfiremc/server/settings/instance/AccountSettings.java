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
package com.soulfiremc.server.settings.instance;

import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.NONE)
public final class AccountSettings implements SettingsObject {
  private static final String NAMESPACE = "account";
  public static final BooleanProperty<SettingsSource.Instance> SHUFFLE_ACCOUNTS =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("shuffle-accounts")
      .uiName("Shuffle accounts")
      .description("This setting randomizes account order before bots connect.")
      .defaultValue(false)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> USE_PROXIES_FOR_ACCOUNT_AUTH =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("use-proxies-for-account-auth")
      .uiName("Use proxies for account auth")
      .description("""
        Soulfire uses imported proxies for account authentication.
        Otherwise, the Soulfire server connects directly.""")
      .defaultValue(false)
      .build();
  public static final IntProperty<SettingsSource.Instance> ACCOUNT_IMPORT_CONCURRENCY =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("account-import-concurrency")
      .uiName("Account import concurrency")
      .description("Soulfire imports no more than this number of accounts at the same time when authentication requires credentials.")
      .defaultValue(3)
      .minValue(1)
      .maxValue(Integer.MAX_VALUE)
      .build();
}
