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

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProxySettings implements SettingsObject {
  private static final String NAMESPACE = "proxy";
  public static final IntProperty<SettingsSource.Instance> BOTS_PER_PROXY =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("bots-per-proxy")
      .uiName("Bots per proxy")
      .description("Maximum number of bots that can use one proxy. Use -1 for no limit.")
      .defaultValue(-1)
      .minValue(-1)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> SHUFFLE_PROXIES =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("shuffle-proxies")
      .uiName("Shuffle proxies")
      .description("This setting randomizes proxy order before bots connect.")
      .defaultValue(false)
      .build();
  public static final BooleanProperty<SettingsSource.Instance> STICKY_PROXIES =
    ImmutableBooleanProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("sticky-proxies")
      .uiName("Sticky proxies")
      .description("Reuse the last successfully assigned proxy when each bot reconnects")
      .defaultValue(false)
      .build();
  public static final StringProperty<SettingsSource.Instance> PROXY_CHECK_ADDRESS =
    ImmutableStringProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("proxy-check-address")
      .uiName("Proxy check address")
      .description("What Minecraft server address to use to check if a proxy is working")
      .defaultValue("mc.hypixel.net")
      .build();
  public static final IntProperty<SettingsSource.Instance> PROXY_CHECK_CONCURRENCY =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("proxy-check-concurrency")
      .uiName("Proxy check concurrency")
      .description("Amount of proxies to check at the same time")
      .defaultValue(10)
      .minValue(1)
      .maxValue(Integer.MAX_VALUE)
      .build();
  public static final IntProperty<SettingsSource.Instance> PROXY_CHECK_TIMEOUT =
    ImmutableIntProperty.<SettingsSource.Instance>builder()
      .sourceType(SettingsSource.Instance.INSTANCE)
      .namespace(NAMESPACE)
      .key("proxy-check-timeout")
      .uiName("Proxy check timeout")
      .description("How long to wait for a proxy check response before considering it failed (seconds)")
      .defaultValue(30)
      .minValue(1)
      .maxValue(Integer.MAX_VALUE)
      .build();
}
