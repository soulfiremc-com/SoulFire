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
package com.soulfiremc.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.soulfiremc.server.account.AuthType;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.account.service.OfflineJavaData;
import com.soulfiremc.server.proxy.ProxyType;
import com.soulfiremc.server.proxy.SFProxy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class StickyProxyAllocatorTest {
  @Test
  void assignPrioritizesStickyProxiesBeforeFallbackLoadBalancing() {
    var stickyProxy = proxy("127.0.0.1", 25566);
    var fallbackProxy = proxy("127.0.0.1", 25567);
    var stickyAccount = account("sticky", Map.of(
      StickyProxyAllocator.STICKY_PROXY_NAMESPACE,
      Map.of(StickyProxyAllocator.STICKY_PROXY_KEY, new JsonPrimitive(StickyProxyAllocator.proxyFingerprint(stickyProxy)))));
    var plainAccount = account("plain", Map.of());

    var assignments = StickyProxyAllocator.assign(
      List.of(plainAccount, stickyAccount),
      List.of(
        new StickyProxyAllocator.ProxyAllocation(stickyProxy, 1),
        new StickyProxyAllocator.ProxyAllocation(fallbackProxy, 1)),
      true);

    assertEquals(fallbackProxy, assignments.getFirst().proxy(), "Fallback account should not steal the sticky proxy");
    assertEquals(stickyProxy, assignments.get(1).proxy(), "Sticky account should retain its saved proxy");
  }

  @Test
  void assignFallsBackWhenSavedProxyIsUnavailable() {
    var fullProxy = proxy("127.0.0.1", 25566);
    var availableProxy = proxy("127.0.0.1", 25567);
    var fullAllocation = new StickyProxyAllocator.ProxyAllocation(fullProxy, 1);
    fullAllocation.reserve();
    var account = account("sticky", Map.of(
      StickyProxyAllocator.STICKY_PROXY_NAMESPACE,
      Map.of(StickyProxyAllocator.STICKY_PROXY_KEY, new JsonPrimitive(StickyProxyAllocator.proxyFingerprint(fullProxy)))));

    var assignments = StickyProxyAllocator.assign(
      List.of(account),
      List.of(fullAllocation, new StickyProxyAllocator.ProxyAllocation(availableProxy, 1)),
      true);

    assertEquals(availableProxy, assignments.getFirst().proxy(), "Allocator should fall back when the saved proxy is already full");
  }

  @Test
  void withSelectedProxyStoresFingerprintInPersistentMetadata() {
    var proxy = new SFProxy(ProxyType.SOCKS5, new InetSocketAddress("127.0.0.1", 25566), "user", "pass");
    var metadata = StickyProxyAllocator.withSelectedProxy(Map.of(), proxy);

    assertEquals(
      StickyProxyAllocator.proxyFingerprint(proxy),
      StickyProxyAllocator.stickyProxyFingerprint(metadata).orElseThrow(),
      "Stored fingerprint should match the currently selected proxy");
  }

  private static MinecraftAccount account(String name, Map<String, Map<String, JsonElement>> persistentMetadata) {
    return new MinecraftAccount(
      AuthType.OFFLINE,
      UUID.nameUUIDFromBytes(name.getBytes()),
      name,
      new OfflineJavaData(),
      Map.of(),
      persistentMetadata
    );
  }

  private static SFProxy proxy(String host, int port) {
    return new SFProxy(ProxyType.SOCKS5, new InetSocketAddress(host, port), null, null);
  }
}
