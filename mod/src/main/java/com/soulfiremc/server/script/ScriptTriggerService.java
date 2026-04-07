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
package com.soulfiremc.server.script;

import com.soulfiremc.server.adventure.SoulFireAdventure;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.api.event.SoulFireEvent;
import com.soulfiremc.server.api.event.bot.*;
import com.soulfiremc.server.script.nodes.NodeRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/// Service that manages event subscriptions for trigger nodes in scripts.
/// When a script is started, this service registers appropriate event listeners
/// for each trigger node and executes downstream nodes when events fire.
///
/// Uses Reactor Sinks with backpressure to prevent overwhelming the system
/// when high-frequency events (like ticks) fire faster than they can be processed.
@Slf4j
public final class ScriptTriggerService {
  /// Buffer size for backpressure (events beyond this are dropped).
  private static final int TRIGGER_BUFFER_SIZE = 64;

  /// TTL for idempotency tokens to prevent duplicate event processing.
  private static final long IDEMPOTENCY_TTL_MS = 5_000;

  /// Max entries in the idempotency cache.
  private static final int IDEMPOTENCY_MAX_ENTRIES = 1_000;

  private record ScriptRegistration(List<Object> listeners, Set<String> sinkKeys) {}
  private final Map<UUID, ScriptRegistration> scriptRegistrations = new ConcurrentHashMap<>();
  private final Map<String, Sinks.Many<Map<String, NodeValue>>> triggerSinks = new ConcurrentHashMap<>();
  private final Map<String, Disposable> triggerSubscriptions = new ConcurrentHashMap<>();

  /// Per-script idempotency token cache: token -> expiry timestamp
  private final Map<UUID, Map<String, Long>> idempotencyTokens = new ConcurrentHashMap<>();

  /// Registers an event-based trigger with sink, subscription, and handler.
  private <E extends SoulFireEvent> void registerEventTrigger(
    UUID scriptId,
    String nodeId,
    ScriptGraph graph,
    ReactiveScriptContext context,
    ReactiveScriptEngine engine,
    Class<E> eventClass,
    Function<E, Map<String, NodeValue>> inputMapper,
    List<Object> listeners,
    Set<String> sinkKeys,
    String triggerName
  ) {
    var sinkKey = scriptId + ":" + nodeId;
    sinkKeys.add(sinkKey);

    var sink = Sinks.many().multicast().<Map<String, NodeValue>>onBackpressureBuffer(TRIGGER_BUFFER_SIZE);
    triggerSinks.put(sinkKey, sink);

    var maxConcurrent = context.quotas().maxConcurrentTriggers();
    var subscription = sink.asFlux()
      .flatMap(inputs -> engine.executeFromTrigger(graph, nodeId, context, inputs)
          .onErrorResume(e -> {
            log.error("Error in {} trigger execution", triggerName, e);
            return Mono.empty();
          }), maxConcurrent)
      .subscribe();
    triggerSubscriptions.put(sinkKey, subscription);

    Consumer<E> handler = event -> {
      if (context.isCancelled()) {
        return;
      }
      var inputs = inputMapper.apply(event);
      if (inputs != null) {
        // Item 32: Idempotency token check
        if (isDuplicateEvent(scriptId, nodeId, inputs)) {
          return;
        }
        var result = sink.tryEmitNext(inputs);
        if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
          context.eventListener().onLog("warn",
            "Trigger buffer overflow: event dropped (" + TRIGGER_BUFFER_SIZE + "-event limit)");
        }
      }
    };
    SoulFireAPI.registerListener(eventClass, handler);
    listeners.add(new EventListenerHolder<>(eventClass, handler));
    log.debug("Registered {} trigger for script {} node {}", triggerName, scriptId, nodeId);
  }

  /// Registers a synchronous tick-based trigger that executes on the tick thread.
  /// Instead of using a sink/subscription pattern, the script is executed directly
  /// on the event handler thread using .block(), ensuring action nodes can modify
  /// game state immediately without control-task deferral.
  private <E extends SoulFireEvent> void registerSyncTickTrigger(
    UUID scriptId,
    String nodeId,
    ScriptGraph graph,
    ReactiveScriptContext context,
    ReactiveScriptEngine engine,
    Class<E> eventClass,
    Function<E, Map<String, NodeValue>> inputMapper,
    List<Object> listeners,
    Set<String> sinkKeys,
    String triggerName
  ) {
    Consumer<E> handler = event -> {
      if (context.isCancelled()) {
        return;
      }
      var inputs = inputMapper.apply(event);
      if (inputs != null) {
        try {
          engine.executeFromTriggerSync(graph, nodeId, context, inputs).block();
        } catch (Exception e) {
          log.error("Error in {} sync trigger execution", triggerName, e);
        }
      }
    };
    SoulFireAPI.registerListener(eventClass, handler);
    listeners.add(new EventListenerHolder<>(eventClass, handler));
    log.debug("Registered sync {} trigger for script {} node {}", triggerName, scriptId, nodeId);
  }

  /// Registers event listeners for all trigger nodes in the script.
  /// Uses reactive sinks with backpressure for high-frequency triggers.
  ///
  /// @param scriptId the script ID
  /// @param graph    the script graph
  /// @param context  the reactive execution context
  /// @param engine   the reactive script engine
  public void registerTriggers(
    UUID scriptId,
    ScriptGraph graph,
    ReactiveScriptContext context,
    ReactiveScriptEngine engine
  ) {
    if (scriptRegistrations.containsKey(scriptId)) {
      log.warn("Script {} already has registered triggers, unregistering first", scriptId);
      unregisterTriggers(scriptId);
    }

    var listeners = new ArrayList<>();
    var sinkKeys = new HashSet<String>();

    for (var node : graph.nodes().values()) {
      switch (node.type()) {
        case "trigger.on_pre_entity_tick" -> {
          var tickCount = new AtomicLong(0);
          registerSyncTickTrigger(scriptId, node.id(), graph, context, engine,
            BotPreEntityTickEvent.class, event -> {
              var inputs = new HashMap<String, NodeValue>();
              inputs.put("bot", NodeValue.ofBot(event.connection()));
              inputs.put("tickCount", NodeValue.ofNumber(tickCount.getAndIncrement()));
              return inputs;
            }, listeners, sinkKeys, "OnPreEntityTick");
        }

        case "trigger.on_post_entity_tick" -> {
          var tickCount = new AtomicLong(0);
          registerSyncTickTrigger(scriptId, node.id(), graph, context, engine,
            BotPostEntityTickEvent.class, event -> {
              var inputs = new HashMap<String, NodeValue>();
              inputs.put("bot", NodeValue.ofBot(event.connection()));
              inputs.put("tickCount", NodeValue.ofNumber(tickCount.getAndIncrement()));
              return inputs;
            }, listeners, sinkKeys, "OnPostEntityTick");
        }

        case "trigger.on_chat" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          ChatMessageReceiveEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            var legacyMessage = SoulFireAdventure.LEGACY_SECTION_MESSAGE_SERIALIZER.serialize(event.message());
            var plainText = event.parseToPlainText();
            log.debug("OnChat trigger inputs: legacyMessage='{}', plainText='{}', componentClass={}",
              legacyMessage, plainText, event.message().getClass().getSimpleName());
            inputs.put("message", NodeValue.ofString(legacyMessage));
            inputs.put("messagePlainText", NodeValue.ofString(plainText));
            inputs.put("timestamp", NodeValue.ofNumber(event.timestamp()));
            return inputs;
          }, listeners, sinkKeys, "OnChat");

        case "trigger.on_death" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          BotShouldRespawnEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            inputs.put("shouldRespawn", NodeValue.ofBoolean(event.shouldRespawn()));
            return inputs;
          }, listeners, sinkKeys, "OnDeath");

        case "trigger.on_bot_init" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          BotConnectionInitEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            return inputs;
          }, listeners, sinkKeys, "OnBotInit");

        case "trigger.on_join" -> {
          var triggeredBots = ConcurrentHashMap.<String>newKeySet();

          // Listen for disconnect events to clear the bot from triggeredBots,
          // allowing the trigger to fire again on the next session/reconnect.
          Consumer<BotDisconnectedEvent> disconnectHandler = event ->
            triggeredBots.remove(event.connection().accountName());
          SoulFireAPI.registerListener(BotDisconnectedEvent.class, disconnectHandler);
          listeners.add(new EventListenerHolder<>(BotDisconnectedEvent.class, disconnectHandler));

          // Use BotPostEntityTickEvent instead of BotPreTickEvent so the player's
          // position data has been processed by the time the trigger fires.
          registerEventTrigger(scriptId, node.id(), graph, context, engine,
            BotPostEntityTickEvent.class, event -> {
              var connection = event.connection();
              var botId = connection.accountName();
              if (connection.minecraft().player != null && triggeredBots.add(botId)) {
                var inputs = new HashMap<String, NodeValue>();
                inputs.put("bot", NodeValue.ofBot(connection));
                return inputs;
              }
              return null;
            }, listeners, sinkKeys, "OnJoin");
        }

        case "trigger.on_damage" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          BotDamageEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            inputs.put("amount", NodeValue.ofNumber(event.damageAmount()));
            inputs.put("previousHealth", NodeValue.ofNumber(event.previousHealth()));
            inputs.put("newHealth", NodeValue.ofNumber(event.newHealth()));
            return inputs;
          }, listeners, sinkKeys, "OnDamage");

        case "trigger.on_container_open" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          BotOpenContainerEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            inputs.put("containerId", NodeValue.ofNumber(event.containerId()));
            inputs.put("containerName", NodeValue.ofString(event.containerName()));
            inputs.put("containerType", NodeValue.ofString(event.containerType()));
            return inputs;
          }, listeners, sinkKeys, "OnContainerOpen");

        case "trigger.on_interval" -> {
          var resolvedInputs = new HashMap<>(NodeRegistry.computeDefaultInputs(NodeRegistry.getMetadata(node.type())));
          if (node.defaultInputs() != null) {
            for (var entry : node.defaultInputs().entrySet()) {
              resolvedInputs.put(entry.getKey(), NodeValue.of(entry.getValue()));
            }
          }
          var intervalValue = resolvedInputs.get("intervalMs");
          var intervalMs = intervalValue != null ? intervalValue.asLong(1000L) : 1000L;

          var executionCount = new AtomicLong(0);
          var finalIntervalMs = intervalMs;
          var cancelled = new AtomicBoolean(false);
          var sinkKey = scriptId + ":" + node.id();
          sinkKeys.add(sinkKey);

          var sink = Sinks.many().multicast().<Map<String, NodeValue>>onBackpressureBuffer(TRIGGER_BUFFER_SIZE);
          triggerSinks.put(sinkKey, sink);

          var subscription = sink.asFlux()
            .flatMap(inputs -> engine.executeFromTrigger(graph, node.id(), context, inputs)
              .onErrorResume(e -> {
                log.error("Error in OnInterval trigger execution", e);
                return Mono.empty();
              }), context.quotas().maxConcurrentTriggers())
            .subscribe();
          triggerSubscriptions.put(sinkKey, subscription);

          Runnable task = new Runnable() {
            @Override
            public void run() {
              if (context.isCancelled() || cancelled.get()) {
                return;
              }

              var inputs = new HashMap<String, NodeValue>();
              inputs.put("executionCount", NodeValue.ofNumber(executionCount.getAndIncrement()));

              sink.tryEmitNext(inputs);

              if (!context.isCancelled() && !cancelled.get()) {
                context.scheduler().schedule(this, finalIntervalMs, TimeUnit.MILLISECONDS);
              }
            }
          };

          context.scheduler().schedule(task, intervalMs, TimeUnit.MILLISECONDS);
          listeners.add(new CancellableTask(cancelled));
          log.debug("Registered OnInterval trigger for script {} node {} with interval {}ms",
            scriptId, node.id(), intervalMs);
        }

        case "trigger.on_script_init" -> {
          // Fire immediately when script starts
          // Execute asynchronously to not block registration
          var initSubscription = engine.executeFromTrigger(graph, node.id(), context, Map.of())
            .onErrorResume(e -> {
              log.error("Error in OnScriptInit trigger execution", e);
              return Mono.empty();
            })
            .subscribe();
          listeners.add(new DisposableHolder(initSubscription));
          log.debug("Registered and fired OnScriptInit trigger for script {} node {}", scriptId, node.id());
        }

        case "trigger.on_disconnect" -> registerEventTrigger(scriptId, node.id(), graph, context, engine,
          BotDisconnectedEvent.class, event -> {
            var inputs = new HashMap<String, NodeValue>();
            inputs.put("bot", NodeValue.ofBot(event.connection()));
            inputs.put("reason", NodeValue.ofString(
              SoulFireAdventure.LEGACY_SECTION_MESSAGE_SERIALIZER.serialize(event.message())));
            return inputs;
          }, listeners, sinkKeys, "OnDisconnect");

        case "trigger.on_script_end" -> {
          // Store for later execution when script stops
          var endTrigger = new ScriptEndTrigger(graph, node.id(), context, engine);
          listeners.add(endTrigger);
          log.debug("Registered OnScriptEnd trigger for script {} node {}", scriptId, node.id());
        }

        default -> {
          if (node.type().startsWith("trigger.")) {
            log.warn("Unsupported trigger node type '{}' for script {} node {}", node.type(), scriptId, node.id());
          }
        }
      }
    }

    if (!listeners.isEmpty()) {
      scriptRegistrations.put(scriptId, new ScriptRegistration(listeners, sinkKeys));
      log.info("Registered {} trigger(s) for script {}", listeners.size(), scriptId);
    }
  }

  /// Unregisters all event listeners for a script.
  ///
  /// @param scriptId the script ID
  public void unregisterTriggers(UUID scriptId) {
    var registration = scriptRegistrations.remove(scriptId);
    if (registration == null) {
      return;
    }

    var listeners = registration.listeners();

    // 1. Cancel interval tasks, unregister event listeners, dispose subscriptions
    for (var listener : listeners) {
      if (listener instanceof CancellableTask task) {
        task.cancel();
      } else if (listener instanceof EventListenerHolder<?> holder) {
        holder.unregister();
      } else if (listener instanceof DisposableHolder holder) {
        holder.dispose();
      }
    }

    // 2. Clean up sinks and subscriptions using stored keys (no scan needed)
    for (var key : registration.sinkKeys()) {
      var subscription = triggerSubscriptions.remove(key);
      if (subscription != null) {
        subscription.dispose();
      }
      var sink = triggerSinks.remove(key);
      if (sink != null) {
        sink.tryEmitComplete();
      }
    }

    // 3. Fire OnScriptEnd triggers last, after all other triggers are cleaned up
    for (var listener : listeners) {
      if (listener instanceof ScriptEndTrigger endTrigger) {
        endTrigger.fire();
      }
    }

    // 4. Clean up idempotency token cache
    idempotencyTokens.remove(scriptId);

    log.info("Unregistered {} trigger(s) for script {}", listeners.size(), scriptId);
  }

  /// Checks if a script has active triggers.
  ///
  /// @param scriptId the script ID
  /// @return true if the script has registered triggers
  public boolean hasActiveTriggers(UUID scriptId) {
    return scriptRegistrations.containsKey(scriptId);
  }

  /// Checks whether an event is a duplicate using idempotency tokens.
  /// Computes a token from trigger node ID + hash of input values + time window.
  private boolean isDuplicateEvent(UUID scriptId, String nodeId, Map<String, NodeValue> inputs) {
    var tokenCache = idempotencyTokens.computeIfAbsent(scriptId, _ -> new ConcurrentHashMap<>());
    var now = System.currentTimeMillis();

    // Evict expired entries if cache is large
    if (tokenCache.size() > IDEMPOTENCY_MAX_ENTRIES) {
      tokenCache.entrySet().removeIf(e -> e.getValue() < now);
    }

    // Compute token: nodeId + hash of input values + time window (quantized to TTL)
    var timeWindow = now / IDEMPOTENCY_TTL_MS;
    var token = nodeId + ":" + inputs.hashCode() + ":" + timeWindow;

    var existing = tokenCache.putIfAbsent(token, now + IDEMPOTENCY_TTL_MS);
    return existing != null && existing > now;
  }

  /// Holder for event listeners that enables proper unregistration.
  private record EventListenerHolder<E extends SoulFireEvent>(Class<E> eventClass, Consumer<E> consumer) {
    void unregister() {
      SoulFireAPI.unregisterListener(eventClass, consumer);
    }
  }

  /// Holder for cancellable interval tasks.
  private record CancellableTask(AtomicBoolean cancelled) {
    void cancel() {
      cancelled.set(true);
    }
  }

  /// Holder for disposable subscriptions (e.g., on_script_init).
  private record DisposableHolder(Disposable disposable) {
    void dispose() {
      if (!disposable.isDisposed()) {
        disposable.dispose();
      }
    }
  }

  /// Holder for script end triggers that fire when the script is stopped.
  private record ScriptEndTrigger(
    ScriptGraph graph,
    String nodeId,
    ReactiveScriptContext context,
    ReactiveScriptEngine engine
  ) {
    void fire() {
      var latch = new CountDownLatch(1);
      try {
        engine.executeFromTrigger(graph, nodeId, context, Map.of())
          .onErrorResume(e -> {
            log.error("Error in OnScriptEnd trigger execution", e);
            return Mono.empty();
          })
          .doFinally(_ -> latch.countDown())
          .subscribe();
        if (!latch.await(5, TimeUnit.SECONDS)) {
          log.warn("OnScriptEnd trigger timed out after 5 seconds for node {}", nodeId);
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        log.warn("OnScriptEnd trigger interrupted for node {}", nodeId);
      } catch (Exception e) {
        log.error("Error firing OnScriptEnd trigger", e);
      }
    }
  }
}
