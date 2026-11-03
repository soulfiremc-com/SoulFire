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
package com.soulfiremc.server.script.nodes.flow;

import com.soulfiremc.server.script.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// Flow control node that rate-limits execution by enforcing a cooldown period.
/// Routes to exec_allowed or exec_denied based on whether the cooldown has elapsed.
/// State is scoped per-script via ScriptStateStore.
public final class DebounceNode extends AbstractScriptNode {
  public static final NodeMetadata METADATA = NodeMetadata.builder()
    .type("flow.debounce")
    .displayName("Cooldown")
    .keywords(List.of("Debounce"))
    .category(CategoryRegistry.FLOW)
    .addInputs(
      PortDefinition.execIn(),
      PortDefinition.inputWithDefault("cooldownMs", "Cooldown (ms)", PortType.NUMBER, "1000", "Milliseconds between allowed executions"),
      PortDefinition.inputWithDefault("key", "Key", PortType.STRING, "\"default\"", "Unique key for this debounce")
    )
    .addOutputs(
      PortDefinition.output(StandardPorts.EXEC_ALLOWED, "Allowed", PortType.EXEC, "Execution path if cooldown elapsed"),
      PortDefinition.output(StandardPorts.EXEC_DENIED, "Denied", PortType.EXEC, "Execution path if still in cooldown"),
      PortDefinition.output("allowed", "Allowed", PortType.BOOLEAN, "Whether execution was allowed"),
      PortDefinition.output("remainingMs", "Remaining (ms)", PortType.NUMBER, "Milliseconds until next allowed execution")
    )
    .description("Rate-limits execution by enforcing a cooldown period")
    .icon("timer")
    .color("#607D8B")
    .addKeywords("debounce", "rate limit", "cooldown", "throttle")
    .supportsMuting(false)
    .build();

  @Override
  public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
    var cooldownMs = getLongInput(inputs, "cooldownMs", 1000L);
    var key = getStringInput(inputs, "key", "default");

    var now = System.currentTimeMillis();
    @SuppressWarnings("unchecked")
    var lastExecutionTimes = runtime.stateStore()
      .<Map<String, AtomicLong>>getOrCreate("debounce_times", ConcurrentHashMap::new);
    var lastExecutionTime = lastExecutionTimes.computeIfAbsent(key, _ -> new AtomicLong(0));

    // Atomic check-and-set to avoid race conditions
    while (true) {
      var lastExecution = lastExecutionTime.get();
      var elapsed = now - lastExecution;

      if (elapsed >= cooldownMs) {
        // Try to update atomically
        if (lastExecutionTime.compareAndSet(lastExecution, now)) {
          var outputs = new HashMap<String, NodeValue>();
          outputs.put("allowed", NodeValue.ofBoolean(true));
          outputs.put("remainingMs", NodeValue.ofNumber(0L));
          outputs.put(StandardPorts.EXEC_ALLOWED, NodeValue.ofBoolean(true));
          return completedMono(outputs);
        }
        // Lost race, retry with new value
      } else {
        var outputs = new HashMap<String, NodeValue>();
        outputs.put("allowed", NodeValue.ofBoolean(false));
        outputs.put("remainingMs", NodeValue.ofNumber(cooldownMs - elapsed));
        outputs.put(StandardPorts.EXEC_DENIED, NodeValue.ofBoolean(true));
        return completedMono(outputs);
      }
    }
  }
}
