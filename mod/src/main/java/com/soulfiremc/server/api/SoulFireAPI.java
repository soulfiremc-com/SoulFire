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
package com.soulfiremc.server.api;

import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.event.EventExceptionHandler;
import com.soulfiremc.server.api.event.SoulFireEvent;
import net.lenni0451.lambdaevents.LambdaManager;
import net.lenni0451.lambdaevents.generator.ASMGenerator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// Holds all instances of plugins running in this JVM.
/// The Soulfire server accesses this class on startup and advertises itself to all plugins.
/// It also holds the event manager for all events in this JVM.
public final class SoulFireAPI {
  private static final List<Plugin> SERVER_EXTENSIONS = new CopyOnWriteArrayList<>();
  private static final List<Plugin> ENABLED_EXTENSIONS = new CopyOnWriteArrayList<>();
  private static final PluginApiRegistry PLUGIN_APIS = new PluginApiRegistry();
  private static final LambdaManager EVENT_BUS =
    LambdaManager.threadSafe(new ASMGenerator())
      .setAlwaysCallParents(true)
      .setExceptionHandler(EventExceptionHandler.INSTANCE)
      .setEventFilter(
        (c, _) -> {
          if (SoulFireEvent.class.isAssignableFrom(c)) {
            return true;
          } else {
            throw new IllegalStateException("This event handler only accepts global events");
          }
        });

  private SoulFireAPI() {}

  public static void loadPlugin(Plugin plugin) {
    if (!plugin.isAvailable()) {
      return;
    }

    var context = PLUGIN_APIS.createContext(plugin.pluginInfo());
    try {
      plugin.onLoad(context);
      registerListenersOfClass(plugin.getClass());
      registerListenersOfObject(plugin);
      SERVER_EXTENSIONS.add(plugin);
    } catch (RuntimeException | Error t) {
      PLUGIN_APIS.discard(plugin.pluginInfo().id());
      throw t;
    }
  }

  public static void enablePlugins(SoulFireServer server) {
    var context = new ServerContext(server);
    for (var plugin : SERVER_EXTENSIONS) {
      plugin.onEnable(context);
      ENABLED_EXTENSIONS.add(plugin);
    }
  }

  public static void disablePlugins() {
    for (var index = ENABLED_EXTENSIONS.size() - 1; index >= 0; index--) {
      var plugin = ENABLED_EXTENSIONS.get(index);
      try {
        plugin.onDisable();
      } finally {
        unregisterListenersOfObject(plugin);
        unregisterListenersOfClass(plugin.getClass());
      }
    }
    ENABLED_EXTENSIONS.clear();
  }

  public static List<Plugin> getServerExtensions() {
    return List.copyOf(SERVER_EXTENSIONS);
  }

  public static PluginApiRegistry pluginApis() {
    return PLUGIN_APIS;
  }

  public static <E extends SoulFireEvent> void registerListener(Class<E> clazz, Consumer<E> consumer) {
    EVENT_BUS.registerConsumer(consumer, clazz);
  }

  public static <E extends SoulFireEvent> void unregisterListener(Class<E> clazz, Consumer<E> consumer) {
    EVENT_BUS.unregisterConsumer(consumer, clazz);
  }

  public static void registerListenersOfClass(Class<?> clazz) {
    EVENT_BUS.register(clazz);
  }

  public static void unregisterListenersOfClass(Class<?> clazz) {
    EVENT_BUS.unregister(clazz);
  }

  public static void registerListenersOfObject(Object object) {
    EVENT_BUS.register(object);
  }

  public static void unregisterListenersOfObject(Object object) {
    EVENT_BUS.unregister(object);
  }

  public static void postEvent(SoulFireEvent event) {
    EVENT_BUS.call(event);
  }

  public static LambdaManager getEventManager() {
    return EVENT_BUS;
  }
}
