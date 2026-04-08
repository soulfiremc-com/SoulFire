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

import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.script.nodes.NodeRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/// Reactive execution engine for visual scripts.
/// Uses Project Reactor for proper fan-in synchronization and parallel branch execution.
///
/// Key features:
/// - Fan-in: Waits for all upstream DATA edges before executing a node
/// - Parallelism: Independent branches execute concurrently via Flux.flatMap
/// - Dynamic routing: Follows exec output ports whose IDs appear in node outputs
/// - Self-driving loops: Loop nodes call back into the engine via NodeRuntime.executeDownstream
/// - Error routing: Nodes with exec_error ports route errors; others stop the branch
/// - Muted bypass: Muted nodes pass execution context through unchanged
/// - Per-invocation isolation: Each trigger creates its own ExecutionRun
/// - Check result: Condition-based loops use ResultNode + setCheckResult/getAndResetCheckResult
///   to communicate boolean results from check chains back to the loop node
/// - Stack-safe: Uses Reactor's expand() operator for iterative chain execution,
///   preventing StackOverflowError from deeply nested flatMap operator chains
@Slf4j
public final class ReactiveScriptEngine {
  /// Maximum number of parallel branches to execute simultaneously.
  static final int MAX_PARALLEL_BRANCHES = 8;

  /// Result of executing a single node: its outputs and the updated execution context.
  /// Used by expand() to iteratively determine the next nodes to execute.
  private record NodeExecResult(
    String nodeId, NodeMetadata metadata,
    Map<String, NodeValue> outputs, ExecutionContext execContext
  ) {}

  /// A pending execution task: a node ID and the execution context to use.
  private record ExecTask(String nodeId, ExecutionContext execCtx) {}

  /// A resolved value associated with the edge that produced it.
  private record ResolvedEdgeValue(ScriptGraph.GraphEdge edge, NodeValue value) {}

  /// Formats a human-readable node descriptor for log/error messages.
  private static String describeNode(String nodeId, ScriptGraph graph) {
    var graphNode = graph.getNode(nodeId);
    if (graphNode == null) {
      return nodeId;
    }
    var metadata = NodeRegistry.isRegistered(graphNode.type()) ? NodeRegistry.getMetadata(graphNode.type()) : null;
    var displayName = metadata != null ? metadata.displayName() : graphNode.type();
    return displayName + " (" + graphNode.type() + ") [" + nodeId + "]";
  }

  private static Comparator<ScriptGraph.GraphEdge> inputEdgeComparator() {
    return Comparator
      .comparing(ScriptGraph.GraphEdge::targetHandle)
      .thenComparingInt(ScriptGraph.GraphEdge::order)
      .thenComparing(ScriptGraph.GraphEdge::sourceNodeId)
      .thenComparing(ScriptGraph.GraphEdge::sourceHandle);
  }

  private static Map<String, PortDefinition> inputDefinitions(NodeMetadata metadata) {
    return metadata.inputs().stream()
      .collect(Collectors.toMap(PortDefinition::id, port -> port));
  }

  private static void materializeEdgeValue(
    Map<String, PortDefinition> inputDefs,
    Map<String, NodeValue> singleInputs,
    Map<String, List<NodeValue>> multiInputs,
    ScriptGraph.GraphEdge edge,
    NodeValue value
  ) {
    var port = inputDefs.get(edge.targetHandle());
    if (port != null && port.multiInput()) {
      multiInputs.computeIfAbsent(edge.targetHandle(), _ -> new ArrayList<>()).add(value);
    } else {
      singleInputs.put(edge.targetHandle(), value);
    }
  }

  private static Map<String, NodeValue> mergeResolvedInputs(
    Map<String, NodeValue> baseInputs,
    Map<String, NodeValue> singleInputs,
    Map<String, List<NodeValue>> multiInputs
  ) {
    var merged = new HashMap<>(baseInputs);
    merged.putAll(singleInputs);
    for (var entry : multiInputs.entrySet()) {
      merged.put(entry.getKey(), NodeValue.ofList(entry.getValue()));
    }
    return merged;
  }

  public ReactiveScriptEngine() {
    log.info("ReactiveScriptEngine initialized with {} registered node types", NodeRegistry.getRegisteredCount());
  }

  /// Executes a script starting from a specific trigger node.
  /// Used by ScriptTriggerService when an event fires.
  ///
  /// @param graph         the script graph
  /// @param triggerNodeId the trigger node to start from
  /// @param context       the reactive execution context
  /// @param eventInputs   inputs from the triggering event
  /// @return a Mono that completes when execution finishes
  public Mono<Void> executeFromTrigger(
    ScriptGraph graph,
    String triggerNodeId,
    ReactiveScriptContext context,
    Map<String, NodeValue> eventInputs
  ) {
    return executeFromTriggerInternal(graph, triggerNodeId, context, eventInputs, false);
  }

  /// Executes a script starting from a specific trigger node, synchronously on the calling thread.
  /// Used by ScriptTriggerService for tick-based triggers that need to execute
  /// on the tick thread without scheduler switching.
  ///
  /// @param graph         the script graph
  /// @param triggerNodeId the trigger node to start from
  /// @param context       the reactive execution context
  /// @param eventInputs   inputs from the triggering event
  /// @return a Mono that completes when execution finishes (no scheduler switching)
  public Mono<Void> executeFromTriggerSync(
    ScriptGraph graph,
    String triggerNodeId,
    ReactiveScriptContext context,
    Map<String, NodeValue> eventInputs
  ) {
    return executeFromTriggerInternal(graph, triggerNodeId, context, eventInputs, true);
  }

  /// Internal implementation for trigger execution.
  ///
  /// @param graph             the script graph
  /// @param triggerNodeId     the trigger node to start from
  /// @param context           the reactive execution context
  /// @param eventInputs       inputs from the triggering event
  /// @param tickSynchronous   if true, executes on the calling thread (tick thread) without switching schedulers
  /// @return a Mono that completes when execution finishes
  private Mono<Void> executeFromTriggerInternal(
    ScriptGraph graph,
    String triggerNodeId,
    ReactiveScriptContext context,
    Map<String, NodeValue> eventInputs,
    boolean tickSynchronous
  ) {
    // Build per-invocation reactor scheduler with bot context if available.
    // This ensures bot thread-locals are set on all async threads,
    // including after Mono.delay continuations.
    var baseScheduler = context.getReactorScheduler();
    var botValue = eventInputs.get(StandardPorts.BOT_IN);
    var botConnection = botValue != null ? botValue.asBot() : null;
    Scheduler invocationScheduler;
    if (botConnection != null && baseScheduler instanceof SoulFireReactorScheduler sfScheduler) {
      invocationScheduler = sfScheduler.withAdditionalWrapper(botConnection.runnableWrapper());
    } else {
      invocationScheduler = baseScheduler;
    }

    var runHolder = new AtomicReference<ExecutionRun>();
    var mono = Mono.<Void>defer(() -> {
      if (context.isCancelled()) {
        return Mono.empty();
      }

      var quotas = context.quotas();

      var run = new ExecutionRun(tickSynchronous, quotas.dataEdgeTimeout(),
        quotas.maxExecutionCount(), context.eventListener(), invocationScheduler);
      runHolder.set(run);

      var graphNode = graph.getNode(triggerNodeId);
      if (graphNode == null) {
        log.warn("Trigger node {} not found in graph", triggerNodeId);
        return Mono.empty();
      }

      var nodeDesc = describeNode(triggerNodeId, graph);

      if (!NodeRegistry.isRegistered(graphNode.type())) {
        log.warn("No implementation found for trigger node: {}", nodeDesc);
        context.eventListener().onNodeError(triggerNodeId, nodeDesc + ": Unknown node type: " + graphNode.type());
        return Mono.empty();
      }

      var nodeImpl = NodeRegistry.create(graphNode.type());
      var metadata = NodeRegistry.getMetadata(graphNode.type());

      // Build inputs: defaults + graph defaults + event inputs
      var inputs = new HashMap<>(NodeRegistry.computeDefaultInputs(metadata));
      if (graphNode.defaultInputs() != null) {
        for (var entry : graphNode.defaultInputs().entrySet()) {
          inputs.put(entry.getKey(), NodeValue.of(entry.getValue()));
        }
      }
      inputs.putAll(eventInputs);
      if (log.isDebugEnabled() && !eventInputs.isEmpty()) {
        log.debug("Trigger {} eventInputs: {}", nodeDesc, eventInputs.entrySet().stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toDebugString())));
      }

      context.eventListener().onNodeStarted(triggerNodeId);

      var nodeRuntime = createNodeRuntime(graph, triggerNodeId, context, run, ExecutionContext.empty());

      // Mark the trigger as already triggered so that downstream DATA edges
      // pointing back at this trigger won't re-execute it as a "data-only" node
      // (trigger nodes have no incoming execution edges, which would otherwise
      // cause them to be misidentified as data-only nodes).
      run.markDataNodeTriggered(triggerNodeId);

      return Mono.defer(() -> nodeImpl.executeReactive(nodeRuntime, inputs))
        .doOnNext(outputs -> {
          if (log.isDebugEnabled()) {
            log.debug("Trigger {} outputs: {}", nodeDesc, outputs.entrySet().stream()
              .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toDebugString())));
          }
          run.publishNodeOutputs(triggerNodeId, outputs);
          context.eventListener().onNodeCompleted(triggerNodeId, outputs);
        })
        .doOnError(e -> {
          var message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
          log.error("Error executing trigger node {}: {}", nodeDesc, message, e);
          context.eventListener().onNodeError(triggerNodeId, nodeDesc + ": " + message);
        })
        .onErrorResume(_ -> Mono.empty())
        .flatMap(outputs -> {
          var execPortIds = metadata.execOutputPortIds();
          var contextOutputs = new HashMap<>(outputs);
          execPortIds.forEach(contextOutputs::remove);
          var execContext = ExecutionContext.from(contextOutputs);
          return followExecOutputs(graph, triggerNodeId, metadata, outputs, context, run, execContext);
        });
    });

    var monoWithStats = mono
      .doFinally(_ -> {
        var run = runHolder.get();
        if (run != null) {
          context.eventListener().onExecutionStats(
            run.getExecutionCount(), run.getMaxExecutionCount());
        }
      });

    // For synchronous tick execution, don't switch schedulers - run on the calling thread
    return tickSynchronous ? monoWithStats : monoWithStats.subscribeOn(invocationScheduler);
  }

  /// Determines which exec output ports to follow based on node outputs,
  /// then executes downstream for each active port.
  /// Only used for the trigger node — downstream chain execution uses expand() via executeDownstream.
  ///
  /// Convention: if any exec output port IDs appear as keys in the outputs map,
  /// follow those. Otherwise fall back to "out".
  private Mono<Void> followExecOutputs(
    ScriptGraph graph,
    String nodeId,
    NodeMetadata metadata,
    Map<String, NodeValue> outputs,
    ReactiveScriptContext context,
    ExecutionRun run,
    ExecutionContext execContext
  ) {
    var active = resolveActiveExecPorts(metadata, outputs);
    if (active.isEmpty()) {
      return Mono.empty();
    }

    return Flux.fromIterable(active)
      .flatMap(handle -> executeDownstream(graph, nodeId, handle, context, run, execContext), MAX_PARALLEL_BRANCHES)
      .then();
  }

  /// Determines which execution output ports are active based on the node's outputs.
  /// Convention: if any exec output port IDs appear as keys in the outputs map,
  /// follow those. Otherwise fall back to "out" if it's the only exec port.
  private static List<String> resolveActiveExecPorts(NodeMetadata metadata, Map<String, NodeValue> outputs) {
    var execPorts = metadata.execOutputPortIds();
    if (execPorts.isEmpty()) {
      return List.of();
    }

    var active = execPorts.stream()
      .filter(outputs::containsKey)
      .toList();

    if (active.isEmpty()) {
      if (execPorts.size() == 1 && execPorts.contains(StandardPorts.EXEC_OUT)) {
        return List.of(StandardPorts.EXEC_OUT);
      }
      return List.of();
    }

    return active;
  }

  /// Executes all nodes connected to an execution output handle.
  /// Uses Reactor's expand() operator for iterative (stack-safe) chain execution:
  /// each node is executed, its exec outputs are resolved into next tasks, and
  /// expand() processes them from an internal queue without growing the call stack.
  ///
  /// @param graph        the script graph
  /// @param fromNodeId   the source node ID
  /// @param outputHandle the execution output handle to follow
  /// @param context      the script execution context
  /// @param run          the per-invocation execution run
  /// @param execContext  the accumulated execution context flowing along execution edges
  /// @return a Mono that completes when downstream execution finishes
  private Mono<Void> executeDownstream(
    ScriptGraph graph,
    String fromNodeId,
    String outputHandle,
    ReactiveScriptContext context,
    ExecutionRun run,
    ExecutionContext execContext
  ) {
    var nextNodes = graph.getNextExecutionNodes(fromNodeId, outputHandle);
    if (nextNodes.isEmpty()) {
      return Mono.empty();
    }

    return Flux.fromIterable(nextNodes)
      .map(nodeId -> new ExecTask(nodeId, execContext))
      .expand(task ->
        executeNodeWithDependencies(graph, task.nodeId(), context, run, task.execCtx())
          .flatMapMany(result -> getNextExecTasks(graph, result))
      )
      .then();
  }

  /// Determines the next ExecTasks to process based on a node's execution result.
  /// Uses resolveActiveExecPorts for routing, returns tasks for expand().
  private Flux<ExecTask> getNextExecTasks(ScriptGraph graph, NodeExecResult result) {
    var active = resolveActiveExecPorts(result.metadata(), result.outputs());
    if (active.isEmpty()) {
      return Flux.empty();
    }

    return Flux.fromIterable(active)
      .flatMap(handle -> {
        var nextNodes = graph.getNextExecutionNodes(result.nodeId(), handle);
        return Flux.fromIterable(nextNodes)
          .map(nextId -> new ExecTask(nextId, result.execContext()));
      });
  }

  /// Executes a node after waiting for all its DATA dependencies.
  /// Returns a NodeExecResult containing the node's outputs and updated execution context,
  /// which expand() uses to determine the next nodes to execute.
  ///
  /// Input resolution priority (highest to lowest):
  /// 1. Explicit DATA wire values
  /// 2. Execution context values (upstream outputs flowing along execution edges)
  /// 3. Graph-level default inputs
  /// 4. Node metadata default inputs
  private Mono<NodeExecResult> executeNodeWithDependencies(
    ScriptGraph graph,
    String nodeId,
    ReactiveScriptContext context,
    ExecutionRun run,
    ExecutionContext execContext
  ) {
    if (context.isCancelled()) {
      return Mono.empty();
    }

    var nodeDesc = describeNode(nodeId, graph);

    if (!run.incrementAndCheckLimit()) {
      log.error("Execution limit exceeded for node {}", nodeDesc);
      context.eventListener().onNodeError(nodeId, nodeDesc + ": Execution limit exceeded");
      return Mono.empty();
    }

    var graphNode = graph.getNode(nodeId);
    if (graphNode == null) {
      return Mono.empty();
    }

    if (!NodeRegistry.isRegistered(graphNode.type())) {
      log.warn("No implementation found for node: {}", nodeDesc);
      context.eventListener().onNodeError(nodeId, nodeDesc + ": Unknown node type: " + graphNode.type());
      return Mono.empty();
    }

    var nodeImpl = NodeRegistry.create(graphNode.type());
    var metadata = NodeRegistry.getMetadata(graphNode.type());

    // Muted node bypass — propagate execution context as outputs so DATA edges pass through
    if (graphNode.muted()) {
      if (!metadata.supportsMuting()) {
        log.warn("Node {} is muted but does not support muting - executing normally", describeNode(nodeId, graph));
        // Fall through to normal execution instead of silently dropping branches
      } else {
        run.publishNodeOutputs(nodeId, execContext.values());
        context.eventListener().onNodeCompleted(nodeId, Map.of());
        return Mono.just(new NodeExecResult(nodeId, metadata, Map.of(), execContext));
      }
    }

    // Collect all DATA edges targeting this node
    var incomingDataEdges = graph.getIncomingDataEdges(nodeId);

    // Build the base inputs: node defaults < execution context < graph defaults < DATA edges
    // Graph defaults (user-set values) take priority over execution context to prevent
    // upstream output names from silently overriding explicitly configured node inputs.
    var baseInputs = new HashMap<>(NodeRegistry.computeDefaultInputs(metadata));
    baseInputs.putAll(execContext.values());
    if (graphNode.defaultInputs() != null) {
      for (var entry : graphNode.defaultInputs().entrySet()) {
        baseInputs.put(entry.getKey(), NodeValue.of(entry.getValue()));
      }
    }

    if (incomingDataEdges.isEmpty()) {
      return executeNode(nodeImpl, metadata, nodeId, baseInputs, graph, context, run, execContext);
    }

    // Item 18+21: Only eagerly execute data-only sources for declared ports, sorted for deterministic ordering
    var declaredInputIds = metadata.inputs().stream()
      .map(PortDefinition::id)
      .collect(Collectors.toSet());
    var dataOnlySourceIds = incomingDataEdges.stream()
      .filter(edge -> declaredInputIds.contains(edge.targetHandle()))
      .map(ScriptGraph.GraphEdge::sourceNodeId)
      .distinct()
      .sorted() // Item 18: deterministic ordering for reproducible execution
      .filter(sourceId -> !graph.hasIncomingExecutionEdges(sourceId))
      .map(sourceId -> executeDataNode(graph, sourceId, context, run))
      .toList();

    var ensureDataNodes = dataOnlySourceIds.isEmpty()
      ? Mono.<Void>empty()
      : Flux.merge(dataOnlySourceIds).then();

    return ensureDataNodes.then(Mono.defer(() -> {
      // Split DATA edges into on-path (source has incoming exec edges) and off-path (data-only)
      var inputDefs = inputDefinitions(metadata);
      var onPathResolved = new HashMap<String, NodeValue>();
      var onPathMultiResolved = new HashMap<String, List<NodeValue>>();
      var offPathMonos = new ArrayList<Mono<ResolvedEdgeValue>>();

      for (var edge : incomingDataEdges.stream().sorted(inputEdgeComparator()).toList()) {
        var sourceKey = edge.sourceHandle();
        var targetKey = edge.targetHandle();

        if (graph.hasIncomingExecutionEdges(edge.sourceNodeId())) {
          // On-path: resolve synchronously from published outputs or exec context
          var published = run.getPublishedOutputs(edge.sourceNodeId());
          NodeValue value = null;
          if (published != null) {
            value = published.get(sourceKey);
          }
          if (value == null) {
            // Fall back to execution context values
            value = execContext.values().get(sourceKey);
          }
          if (value != null) {
            if (log.isDebugEnabled()) {
              log.debug("DATA edge {}.{} -> {}.{} (on-path): {}", edge.sourceNodeId(), sourceKey, nodeId, targetKey, value.toDebugString());
            }
            materializeEdgeValue(inputDefs, onPathResolved, onPathMultiResolved, edge, value);
          } else {
            var msg = "DATA edge " + edge.sourceNodeId() + "." + sourceKey
              + " -> " + nodeId + "." + targetKey + ": on-path source value not available";
            log.warn("{} (published: {}, execContext: {})", msg,
              published != null ? published.keySet() : "null", execContext.values().keySet());
            context.eventListener().onNodeError(nodeId, msg);
          }
        } else {
          // Off-path (data-only): use existing awaitNodeOutputs with timeout
          offPathMonos.add(run.awaitNodeOutputs(edge.sourceNodeId(), describeNode(edge.sourceNodeId(), graph))
            .handle((outputs, sink) -> {
              var value = outputs.get(sourceKey);
              if (value != null) {
                if (log.isDebugEnabled()) {
                  log.debug("DATA edge {}.{} -> {}.{} (off-path): {}", edge.sourceNodeId(), sourceKey, nodeId, targetKey, value.toDebugString());
                }
                sink.next(new ResolvedEdgeValue(edge, value));
              } else {
                log.warn("DATA edge {}.{} -> {}.{}: source key NOT FOUND in outputs (available: {})",
                  edge.sourceNodeId(), sourceKey, nodeId, targetKey, outputs.keySet());
              }
            }));
        }
      }

      if (offPathMonos.isEmpty()) {
        // All DATA edges resolved synchronously
        return executeNode(nodeImpl, metadata, nodeId,
          mergeResolvedInputs(baseInputs, onPathResolved, onPathMultiResolved),
          graph, context, run, execContext);
      }

      return Flux.merge(offPathMonos)
        .sort(Comparator.comparing(ResolvedEdgeValue::edge, inputEdgeComparator()))
        .collectList()
        .map(offPathInputs -> {
          var offPathResolved = new HashMap<String, NodeValue>();
          var offPathMultiResolved = new HashMap<String, List<NodeValue>>();
          for (var resolved : offPathInputs) {
            materializeEdgeValue(inputDefs, offPathResolved, offPathMultiResolved, resolved.edge(), resolved.value());
          }
          var singleResolved = new HashMap<>(onPathResolved);
          singleResolved.putAll(offPathResolved);
          var multiResolved = new HashMap<>(onPathMultiResolved);
          offPathMultiResolved.forEach((key, values) ->
            multiResolved.merge(key, values, (left, right) -> {
              var mergedValues = new ArrayList<NodeValue>(left.size() + right.size());
              mergedValues.addAll(left);
              mergedValues.addAll(right);
              return mergedValues;
            }));
          return mergeResolvedInputs(baseInputs, singleResolved, multiResolved);
        })
        .flatMap(inputs -> executeNode(nodeImpl, metadata, nodeId, inputs, graph, context, run, execContext));
    }));
  }

  /// Executes a single node with resolved inputs and returns the result.
  /// Does NOT follow exec outputs — that is handled iteratively by expand() in executeDownstream.
  /// Error handling: nodes with exec_error ports route errors; others stop the branch.
  private Mono<NodeExecResult> executeNode(
    ScriptNode nodeImpl,
    NodeMetadata metadata,
    String nodeId,
    Map<String, NodeValue> inputs,
    ScriptGraph graph,
    ReactiveScriptContext context,
    ExecutionRun run,
    ExecutionContext execContext
  ) {
    context.eventListener().onNodeStarted(nodeId);

    var nodeDesc = describeNode(nodeId, graph);
    var nodeRuntime = createNodeRuntime(graph, nodeId, context, run, execContext);

    // Item 9: Input contract validation
    for (var port : metadata.inputs()) {
      if (port.required() && port.type() != PortType.EXEC && !inputs.containsKey(port.id())) {
        context.eventListener().onNodeError(nodeId, nodeDesc + ": Missing required input: " + port.id());
        return Mono.empty();
      }
    }

    // Item 11: Runtime type assertions on DATA edge inputs
    for (var edge : graph.getIncomingDataEdges(nodeId)) {
      var value = inputs.get(edge.targetHandle());
      if (value == null) {
        continue;
      }
      var targetNode = graph.getNode(nodeId);
      if (targetNode == null || !NodeRegistry.isRegistered(targetNode.type())) {
        continue;
      }
      var portMeta = NodeRegistry.getMetadata(targetNode.type());
      var portDef = portMeta.inputs().stream()
        .filter(p -> p.id().equals(edge.targetHandle())).findFirst();
      if (portDef.isPresent()) {
        var conversion = NodeValueConversion.convert(value, portDef.get().type());
        if (!conversion.success()) {
          context.eventListener().onLog("warn", "Type mismatch at runtime: port '" + edge.targetHandle()
            + "' expected " + portDef.get().type() + ", got " + NodeValueTypeChecker.describeActualType(value)
            + " (" + conversion.failureReason() + ")");
        }
      }
    }

    var startNanos = System.nanoTime();
    var nodeMono = Mono.defer(() -> nodeImpl.executeReactive(nodeRuntime, inputs));
    // Add per-node timeout for async execution (skip for tick-synchronous to avoid interrupting tick thread)
    // Duration.ZERO means timeouts are disabled
    var nodeTimeout = context.quotas().nodeExecutionTimeout();
    if (!run.isTickSynchronous() && !nodeTimeout.isZero()) {
      nodeMono = nodeMono.timeout(nodeTimeout);
    }
    return nodeMono
      .doOnNext(outputs -> {
        var executionTimeNanos = System.nanoTime() - startNanos;
        // Item 10: Output contract validation
        for (var port : metadata.outputs()) {
          if (port.type() != PortType.EXEC) {
            var hasOutgoingEdge = graph.edges().stream().anyMatch(e ->
              e.edgeType() == ScriptGraph.EdgeType.DATA
                && e.sourceNodeId().equals(nodeId)
                && e.sourceHandle().equals(port.id()));
            if (hasOutgoingEdge && !outputs.containsKey(port.id())) {
              context.eventListener().onLog("warn",
                "Node did not produce expected output: " + port.id());
            }
          }
        }
        run.publishNodeOutputs(nodeId, outputs);
        context.eventListener().onNodeCompleted(nodeId, outputs, executionTimeNanos);
      })
      .doOnError(e -> {
        var message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
        log.error("Error executing node {}: {}", nodeDesc, message, e);
        context.eventListener().onNodeError(nodeId, nodeDesc + ": " + message);
      })
      .onErrorResume(e -> {
        if (metadata.hasExecErrorPort()) {
          var msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
          return Mono.just(Map.of(
            StandardPorts.EXEC_ERROR, NodeValue.ofBoolean(true),
            "success", NodeValue.ofBoolean(false),
            "errorMessage", NodeValue.ofString(msg != null ? msg : e.getClass().getSimpleName())
          ));
        }
        // No error port — stop this branch
        return Mono.empty();
      })
      .map(outputs -> {
        var contextOutputs = new HashMap<>(outputs);
        metadata.execOutputPortIds().forEach(contextOutputs::remove);
        var newExecContext = execContext.mergeWith(contextOutputs);
        return new NodeExecResult(nodeId, metadata, outputs, newExecContext);
      });
  }

  /// Eagerly executes a data-only node that is not on any execution path.
  /// Called when a downstream node needs this node's outputs via a DATA edge,
  /// but no EXECUTION edge will ever trigger it.
  /// Uses ExecutionRun.markDataNodeTriggered to ensure each node runs at most once per invocation.
  /// Recursively resolves upstream data-only dependencies.
  private Mono<Void> executeDataNode(
    ScriptGraph graph,
    String nodeId,
    ReactiveScriptContext context,
    ExecutionRun run
  ) {
    if (!run.markDataNodeTriggered(nodeId)) {
      return Mono.empty();
    }

    if (context.isCancelled()) {
      return Mono.empty();
    }

    var graphNode = graph.getNode(nodeId);
    if (graphNode == null) {
      return Mono.empty();
    }

    var nodeDesc = describeNode(nodeId, graph);

    if (!run.incrementAndCheckLimit()) {
      log.error("Execution limit exceeded for data node {}", nodeDesc);
      context.eventListener().onNodeError(nodeId, nodeDesc + ": Execution limit exceeded");
      return Mono.empty();
    }

    if (!NodeRegistry.isRegistered(graphNode.type())) {
      log.warn("No implementation found for data node: {}", nodeDesc);
      context.eventListener().onNodeError(nodeId, nodeDesc + ": Unknown node type: " + graphNode.type());
      return Mono.empty();
    }

    var nodeImpl = NodeRegistry.create(graphNode.type());
    var metadata = NodeRegistry.getMetadata(graphNode.type());

    var baseInputs = new HashMap<>(NodeRegistry.computeDefaultInputs(metadata));
    if (graphNode.defaultInputs() != null) {
      for (var entry : graphNode.defaultInputs().entrySet()) {
        baseInputs.put(entry.getKey(), NodeValue.of(entry.getValue()));
      }
    }

    var incomingDataEdges = graph.getIncomingDataEdges(nodeId);

    // Recursively ensure upstream data-only nodes are executed first
    var upstreamDataNodeExecutions = incomingDataEdges.stream()
      .map(ScriptGraph.GraphEdge::sourceNodeId)
      .distinct()
      .filter(sourceId -> !graph.hasIncomingExecutionEdges(sourceId))
      .map(sourceId -> executeDataNode(graph, sourceId, context, run))
      .toList();

    var ensureUpstream = upstreamDataNodeExecutions.isEmpty()
      ? Mono.<Void>empty()
      : Flux.merge(upstreamDataNodeExecutions).then();

    return ensureUpstream.then(Mono.defer(() -> {
      var inputDefs = inputDefinitions(metadata);
      var upstreamMonos = incomingDataEdges.stream()
        .sorted(inputEdgeComparator())
        .map(edge -> run.awaitNodeOutputs(edge.sourceNodeId(), describeNode(edge.sourceNodeId(), graph))
          .<ResolvedEdgeValue>handle((outputs, sink) -> {
            var value = outputs.get(edge.sourceHandle());
            if (value != null) {
              sink.next(new ResolvedEdgeValue(edge, value));
            }
          }))
        .toList();

      return Flux.merge(upstreamMonos)
        .sort(Comparator.comparing(ResolvedEdgeValue::edge, inputEdgeComparator()))
        .collectList()
        .map(resolvedInputs -> {
          var singleInputs = new HashMap<String, NodeValue>();
          var multiInputs = new HashMap<String, List<NodeValue>>();
          for (var resolved : resolvedInputs) {
            materializeEdgeValue(inputDefs, singleInputs, multiInputs, resolved.edge(), resolved.value());
          }
          return mergeResolvedInputs(baseInputs, singleInputs, multiInputs);
        })
        .flatMap(inputs -> {
          context.eventListener().onNodeStarted(nodeId);
          var nodeRuntime = createNodeRuntime(graph, nodeId, context, run, ExecutionContext.empty());
          return nodeImpl.executeReactive(nodeRuntime, inputs)
            .doOnNext(outputs -> {
              run.publishNodeOutputs(nodeId, outputs);
              context.eventListener().onNodeCompleted(nodeId, outputs);
            })
            .doOnError(e -> {
              var message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
              log.error("Error executing data node {}: {}", nodeDesc, message, e);
              context.eventListener().onNodeError(nodeId, nodeDesc + ": " + message);
              run.publishNodeFailure(nodeId);
            })
            .onErrorResume(_ -> Mono.empty());
        })
        .then();
    }));
  }

  /// Creates a NodeRuntime wrapper that captures the current execution state.
  /// Self-driving nodes (loops, sequences) use runtime.executeDownstream() to
  /// call back into the engine. Condition-based loops use ResultNode +
  /// setCheckResult/getAndResetCheckResult to read boolean results from check chains.
  ///
  /// executeDownstream publishes the caller's outputs to the run before delegating,
  /// enabling DATA edges from self-driving nodes to downstream chain nodes.
  private NodeRuntime createNodeRuntime(
    ScriptGraph graph,
    String nodeId,
    ReactiveScriptContext context,
    ExecutionRun run,
    ExecutionContext execContext
  ) {
    return new NodeRuntime() {
      @Override
      public ScriptStateStore stateStore() {
        return context.stateStore();
      }

      @Override
      public InstanceManager instance() {
        return context.instance();
      }

      @Override
      public SoulFireScheduler scheduler() {
        return context.scheduler();
      }

      @Override
      public Scheduler reactorScheduler() {
        return run.reactorScheduler();
      }

      @Override
      public boolean isTickSynchronous() {
        return run.isTickSynchronous();
      }

      @Override
      public void log(String level, String message) {
        context.log(level, message);
      }

      @Override
      public Mono<Void> executeDownstream(String handle, Map<String, NodeValue> outputs) {
        run.publishNodeOutputs(nodeId, outputs);
        var newCtx = execContext.mergeWith(outputs);
        return ReactiveScriptEngine.this.executeDownstream(graph, nodeId, handle, context, run, newCtx);
      }

      @Override
      public void setCheckResult(boolean value) {
        run.setCheckResult(value);
      }

      @Override
      public boolean wasCheckResultSet() {
        return run.wasCheckResultSet();
      }

      @Override
      public boolean getAndResetCheckResult() {
        return run.getAndResetCheckResult();
      }

      @Override
      public void resetDataNodeTriggers() {
        run.resetDataNodeTriggers();
      }

      @Override
      public void pushCheckContext() {
        run.pushCheckContext();
      }

      @Override
      public void popCheckContext() {
        run.popCheckContext();
      }
    };
  }

  /// Executes the full graph (for testing/one-shot execution).
  ///
  /// @param graph         the script graph to execute
  /// @param instance      the SoulFire instance
  /// @param eventListener listener for execution events
  /// @return a Mono that completes when the script finishes
  public Mono<Void> execute(
    ScriptGraph graph,
    InstanceManager instance,
    ScriptEventListener eventListener
  ) {
    var context = new ReactiveScriptContext(instance, eventListener, ScriptQuotasConfig.DEFAULTS);

    var triggers = graph.findTriggerNodes();
    if (triggers.isEmpty()) {
      log.warn("Script {} has no trigger nodes", graph.scriptName());
      eventListener.onScriptCompleted(false);
      return Mono.empty();
    }

    // Cycle detection is now done at build time in ScriptGraph.Builder.build()

    // Execute all triggers in parallel
    return Flux.fromIterable(triggers)
      .flatMap(triggerId -> executeFromTrigger(graph, triggerId, context, Map.of()))
      .then()
      .doOnSuccess(_ -> eventListener.onScriptCompleted(true))
      .doOnError(_ -> eventListener.onScriptCompleted(false));
  }

  /// Gets a registered node by type.
  ///
  /// @param type the node type identifier
  /// @return the node implementation, or null if not found
  public ScriptNode getNode(String type) {
    return NodeRegistry.isRegistered(type) ? NodeRegistry.create(type) : null;
  }

  /// Returns all registered node types.
  ///
  /// @return list of registered node type identifiers
  public List<String> getRegisteredNodeTypes() {
    return List.copyOf(NodeRegistry.getRegisteredTypes());
  }
}
