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

import com.soulfiremc.server.script.nodes.NodeRegistry;
import lombok.Getter;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Represents the graph structure of a visual script.
/// Contains nodes and edges that define the execution flow and data connections.
@Getter
public final class ScriptGraph {
  private final String scriptId;
  private final String scriptName;
  private final Map<String, GraphNode> nodes;
  private final List<GraphEdge> edges;
  private final Map<String, List<GraphEdge>> outgoingEdgesByHandle;
  private final Map<String, List<GraphEdge>> incomingEdgesByHandle;
  private final Map<String, List<GraphEdge>> incomingDataEdgesByNode;
  private final Set<String> nodesWithIncomingExecution;
  private final List<ValidationDiagnostic> warnings;

  /// Severity levels for validation diagnostics.
  public enum Severity { ERROR, WARNING }

  /// A structured validation diagnostic with node/edge context and severity.
  ///
  /// @param nodeId   the node related to this diagnostic, or null for graph-level issues
  /// @param edgeId   a composite edge key related to this diagnostic, or null
  /// @param message  human-readable description
  /// @param severity whether this is an error (blocks build) or warning (informational)
  public record ValidationDiagnostic(
    @Nullable String nodeId,
    @Nullable String edgeId,
    String message,
    Severity severity
  ) {}

  private ScriptGraph(
    String scriptId,
    String scriptName,
    Map<String, GraphNode> nodes,
    List<GraphEdge> edges,
    List<ValidationDiagnostic> warnings
  ) {
    this.scriptId = scriptId;
    this.scriptName = scriptName;
    this.nodes = Collections.unmodifiableMap(nodes);
    this.edges = Collections.unmodifiableList(edges);
    this.warnings = List.copyOf(warnings);

    // Pre-compute edge lookups for efficient traversal
    var edgeOrderComparator = Comparator
      .comparingInt(GraphEdge::order)
      .thenComparing(GraphEdge::targetNodeId)
      .thenComparing(GraphEdge::targetHandle)
      .thenComparing(GraphEdge::sourceNodeId)
      .thenComparing(GraphEdge::sourceHandle);
    var outgoing = new HashMap<String, List<GraphEdge>>();
    var incomingByHandle = new HashMap<String, List<GraphEdge>>();
    var incomingDataByNode = new HashMap<String, List<GraphEdge>>();

    for (var edge : edges) {
      var sourceKey = edge.sourceNodeId + ":" + edge.sourceHandle;
      outgoing.computeIfAbsent(sourceKey, _ -> new ArrayList<>()).add(edge);

      var targetKey = edge.targetNodeId + ":" + edge.targetHandle;
      incomingByHandle.computeIfAbsent(targetKey, _ -> new ArrayList<>()).add(edge);

      if (edge.edgeType == EdgeType.DATA) {
        incomingDataByNode.computeIfAbsent(edge.targetNodeId, _ -> new ArrayList<>()).add(edge);
      }
    }

    // Sort edge lists for deterministic traversal and stable multi-input materialization.
    for (var edgeList : outgoing.values()) {
      edgeList.sort(edgeOrderComparator);
    }
    for (var edgeList : incomingByHandle.values()) {
      edgeList.sort(edgeOrderComparator);
    }
    for (var edgeList : incomingDataByNode.values()) {
      edgeList.sort(edgeOrderComparator);
    }

    this.outgoingEdgesByHandle = Collections.unmodifiableMap(outgoing);
    this.incomingEdgesByHandle = Collections.unmodifiableMap(incomingByHandle);
    this.incomingDataEdgesByNode = Collections.unmodifiableMap(incomingDataByNode);

    var nodesWithIncomingExec = new HashSet<String>();
    for (var edge : edges) {
      if (edge.edgeType == EdgeType.EXECUTION) {
        nodesWithIncomingExec.add(edge.targetNodeId);
      }
    }
    this.nodesWithIncomingExecution = Collections.unmodifiableSet(nodesWithIncomingExec);
  }

  /// Creates a new builder for constructing a ScriptGraph.
  public static Builder builder(String scriptId, String scriptName) {
    return new Builder(scriptId, scriptName);
  }

  /// Returns all DATA edges in the graph for diagnostic purposes.
  public List<GraphEdge> dataEdges() {
    return edges.stream().filter(e -> e.edgeType == EdgeType.DATA).toList();
  }

  /// Gets a node by its ID.
  ///
  /// @param nodeId the node identifier
  /// @return the node, or null if not found
  @Nullable
  public GraphNode getNode(String nodeId) {
    return nodes.get(nodeId);
  }

  /// Finds all trigger nodes (entry points) in the graph.
  /// Trigger nodes are nodes with no incoming execution edges whose type
  /// is a registered trigger type (starts with "trigger.").
  ///
  /// @return list of trigger node IDs
  public List<String> findTriggerNodes() {
    var triggers = new ArrayList<String>();
    for (var node : nodes.values()) {
      if (nodesWithIncomingExecution.contains(node.id)) {
        continue;
      }
      if (NodeRegistry.isRegistered(node.type)) {
        if (NodeRegistry.getMetadata(node.type).isTrigger()) {
          triggers.add(node.id);
        }
      } else if (node.type.startsWith("trigger.")) {
        // Fallback for unregistered types
        triggers.add(node.id);
      }
    }
    return triggers;
  }

  /// Returns whether a node has any incoming execution edges.
  /// Nodes without incoming execution edges that are not triggers will not
  /// be executed by the engine unless explicitly triggered.
  ///
  /// @param nodeId the node identifier
  /// @return true if the node has at least one incoming execution edge
  public boolean hasIncomingExecutionEdges(String nodeId) {
    return nodesWithIncomingExecution.contains(nodeId);
  }

  /// Gets the next execution nodes from a given node's output handle.
  ///
  /// @param nodeId       the source node ID
  /// @param outputHandle the execution output handle name
  /// @return list of target node IDs to execute next
  public List<String> getNextExecutionNodes(String nodeId, String outputHandle) {
    var key = nodeId + ":" + outputHandle;
    var edges = outgoingEdgesByHandle.get(key);
    if (edges == null) {
      return List.of();
    }

    return edges.stream()
      .filter(e -> e.edgeType == EdgeType.EXECUTION)
      .map(GraphEdge::targetNodeId)
      .toList();
  }

  /// Gets all incoming DATA edges for a node.
  /// Uses pre-computed map for O(1) lookup instead of scanning all edges.
  ///
  /// @param nodeId the target node ID
  /// @return list of DATA edges targeting this node
  public List<GraphEdge> getIncomingDataEdges(String nodeId) {
    return incomingDataEdgesByNode.getOrDefault(nodeId, List.of());
  }

  /// Performs topological sort on the graph nodes.
  /// Returns nodes in execution order, respecting dependencies.
  ///
  /// @return list of node IDs in topological order
  /// @throws IllegalStateException if the graph contains cycles
  public List<String> topologicalSort() {
    var inDegree = new HashMap<String, Integer>();
    var adjList = new HashMap<String, List<String>>();

    // Initialize
    for (var nodeId : nodes.keySet()) {
      inDegree.put(nodeId, 0);
      adjList.put(nodeId, new ArrayList<>());
    }

    // Build adjacency list and count in-degrees (include both edge types for cycle detection)
    for (var edge : edges) {
      adjList.get(edge.sourceNodeId).add(edge.targetNodeId);
      inDegree.merge(edge.targetNodeId, 1, Integer::sum);
    }

    // Kahn's algorithm with sorted initial queue for deterministic ordering
    var queue = new PriorityQueue<String>();
    for (var entry : inDegree.entrySet()) {
      if (entry.getValue() == 0) {
        queue.add(entry.getKey());
      }
    }

    var result = new ArrayList<String>();
    while (!queue.isEmpty()) {
      var node = queue.poll();
      result.add(node);

      for (var neighbor : adjList.get(node)) {
        var newDegree = inDegree.get(neighbor) - 1;
        inDegree.put(neighbor, newDegree);
        if (newDegree == 0) {
          queue.add(neighbor);
        }
      }
    }

    if (result.size() != nodes.size()) {
      throw new IllegalStateException("Script graph contains cycles - cannot determine execution order");
    }

    return result;
  }

  /// Type of edge connection.
  public enum EdgeType {
    /// Execution flow edge - determines order of node execution.
    EXECUTION,
    /// Data flow edge - passes values between node ports.
    DATA
  }

  /// Represents a node in the graph.
  ///
  /// @param id              unique node identifier
  /// @param type            node type identifier (e.g., "action.pathfind")
  /// @param defaultInputs   default values for input ports
  /// @param muted           whether the node is muted (bypassed during execution)
  public record GraphNode(
    String id,
    String type,
    @Nullable Map<String, Object> defaultInputs,
    boolean muted
  ) {
    /// Creates a graph node with defaults for new fields.
    public GraphNode(String id, String type, @Nullable Map<String, Object> defaultInputs) {
      this(id, type, defaultInputs, false);
    }
  }

  /// Represents an edge connecting two nodes.
  ///
  /// @param sourceNodeId   the source node ID
  /// @param sourceHandle   the output handle on the source node
  /// @param targetNodeId   the target node ID
  /// @param targetHandle   the input handle on the target node
  /// @param edgeType       whether this is an execution or data edge
  public record GraphEdge(
    String sourceNodeId,
    String sourceHandle,
    String targetNodeId,
    String targetHandle,
    EdgeType edgeType,
    int order
  ) {
    public GraphEdge(String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle, EdgeType edgeType) {
      this(sourceNodeId, sourceHandle, targetNodeId, targetHandle, edgeType, 0);
    }
  }

  /// Builder for constructing ScriptGraph instances.
  public static final class Builder {
    private final String scriptId;
    private final String scriptName;
    private final Map<String, GraphNode> nodes = new HashMap<>();
    private final List<GraphEdge> edges = new ArrayList<>();

    private Builder(String scriptId, String scriptName) {
      this.scriptId = scriptId;
      this.scriptName = scriptName;
    }

    /// Adds a node to the graph.
    public Builder addNode(GraphNode node) {
      nodes.put(node.id, node);
      return this;
    }

    /// Adds a node to the graph.
    public Builder addNode(String id, String type, @Nullable Map<String, Object> defaultInputs) {
      return addNode(new GraphNode(id, type, defaultInputs));
    }

    /// Adds an edge to the graph.
    public Builder addEdge(GraphEdge edge) {
      edges.add(edge);
      return this;
    }

    /// Adds an execution edge between nodes.
    public Builder addExecutionEdge(String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle) {
      return addEdge(new GraphEdge(sourceNodeId, sourceHandle, targetNodeId, targetHandle, EdgeType.EXECUTION));
    }

    /// Adds an execution edge between nodes with an explicit order.
    public Builder addExecutionEdge(String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle, int order) {
      return addEdge(new GraphEdge(sourceNodeId, sourceHandle, targetNodeId, targetHandle, EdgeType.EXECUTION, order));
    }

    /// Adds a data edge between nodes.
    public Builder addDataEdge(String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle) {
      return addEdge(new GraphEdge(sourceNodeId, sourceHandle, targetNodeId, targetHandle, EdgeType.DATA));
    }

    /// Adds a data edge between nodes with an explicit order.
    public Builder addDataEdge(String sourceNodeId, String sourceHandle, String targetNodeId, String targetHandle, int order) {
      return addEdge(new GraphEdge(sourceNodeId, sourceHandle, targetNodeId, targetHandle, EdgeType.DATA, order));
    }

    /// Validates the graph structure and returns a list of diagnostics.
    private List<ValidationDiagnostic> validate() {
      var diagnostics = new ArrayList<ValidationDiagnostic>();

      // 1. Validate all edges reference existing nodes
      for (var edge : edges) {
        var edgeKey = edge.sourceNodeId() + "->" + edge.targetNodeId();
        if (!nodes.containsKey(edge.sourceNodeId())) {
          diagnostics.add(new ValidationDiagnostic(null, edgeKey,
            "Edge references non-existent source node: " + edge.sourceNodeId(), Severity.ERROR));
        }
        if (!nodes.containsKey(edge.targetNodeId())) {
          diagnostics.add(new ValidationDiagnostic(null, edgeKey,
            "Edge references non-existent target node: " + edge.targetNodeId(), Severity.ERROR));
        }
      }

      // 2. Validate no self-loops
      for (var edge : edges) {
        if (edge.sourceNodeId().equals(edge.targetNodeId())) {
          diagnostics.add(new ValidationDiagnostic(edge.sourceNodeId(), null,
            "Self-loop detected on node: " + edge.sourceNodeId(), Severity.ERROR));
        }
      }

      // 3. Detect duplicate edges
      var edgeKeys = new HashSet<String>();
      for (var edge : edges) {
        var key = edge.sourceNodeId() + ":" + edge.sourceHandle() + "->"
          + edge.targetNodeId() + ":" + edge.targetHandle() + ":" + edge.edgeType();
        if (!edgeKeys.add(key)) {
          diagnostics.add(new ValidationDiagnostic(null, key,
            "Duplicate edge: " + key, Severity.ERROR));
        }
      }

      // 4. Detect cycles using Kahn's algorithm
      if (diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
        var inDegree = new HashMap<String, Integer>();
        var adjList = new HashMap<String, List<String>>();
        for (var nodeId : nodes.keySet()) {
          inDegree.put(nodeId, 0);
          adjList.put(nodeId, new ArrayList<>());
        }
        for (var edge : edges) {
          adjList.get(edge.sourceNodeId()).add(edge.targetNodeId());
          inDegree.merge(edge.targetNodeId(), 1, Integer::sum);
        }
        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
          if (entry.getValue() == 0) {
            queue.add(entry.getKey());
          }
        }
        var count = 0;
        while (!queue.isEmpty()) {
          var node = queue.poll();
          count++;
          for (var neighbor : adjList.get(node)) {
            var newDegree = inDegree.get(neighbor) - 1;
            inDegree.put(neighbor, newDegree);
            if (newDegree == 0) {
              queue.add(neighbor);
            }
          }
        }
        if (count != nodes.size()) {
          diagnostics.add(new ValidationDiagnostic(null, null,
            "Script graph contains cycles - cannot determine execution order", Severity.ERROR));
        }
      }

      // 5. Validate node types against NodeRegistry
      for (var node : nodes.values()) {
        if (!node.type().startsWith("layout.") && !NodeRegistry.isRegistered(node.type())) {
          diagnostics.add(new ValidationDiagnostic(node.id(), null,
            "Unknown node type '" + node.type() + "' for node " + node.id(), Severity.ERROR));
        }
      }

      // 6. Validate edge handles against port metadata
      for (var edge : edges) {
        var edgeKey = edge.sourceNodeId() + ":" + edge.sourceHandle() + "->"
          + edge.targetNodeId() + ":" + edge.targetHandle();
        var sourceNode = nodes.get(edge.sourceNodeId());
        if (sourceNode != null && NodeRegistry.isRegistered(sourceNode.type())) {
          var metadata = NodeRegistry.getMetadata(sourceNode.type());
          var validOutputIds = metadata.outputs().stream().map(PortDefinition::id).collect(Collectors.toSet());
          if (!validOutputIds.contains(edge.sourceHandle())) {
            diagnostics.add(new ValidationDiagnostic(edge.sourceNodeId(), edgeKey,
              "Edge from " + edge.sourceNodeId() + " references unknown output '" + edge.sourceHandle() + "'", Severity.ERROR));
          }
        }
        var targetNode = nodes.get(edge.targetNodeId());
        if (targetNode != null && NodeRegistry.isRegistered(targetNode.type())) {
          var metadata = NodeRegistry.getMetadata(targetNode.type());
          var validInputIds = metadata.inputs().stream().map(PortDefinition::id).collect(Collectors.toSet());
          if (!validInputIds.contains(edge.targetHandle())) {
            diagnostics.add(new ValidationDiagnostic(edge.targetNodeId(), edgeKey,
              "Edge to " + edge.targetNodeId() + " references unknown input '" + edge.targetHandle() + "'", Severity.ERROR));
          }
        }
      }

      // 7. Port type compatibility checking
      for (var edge : edges) {
        if (edge.edgeType() != EdgeType.DATA) {
          continue;
        }
        var sourceNode = nodes.get(edge.sourceNodeId());
        var targetNode = nodes.get(edge.targetNodeId());
        if (sourceNode == null || targetNode == null) {
          continue;
        }
        if (!NodeRegistry.isRegistered(sourceNode.type()) || !NodeRegistry.isRegistered(targetNode.type())) {
          continue;
        }
        var sourceMetadata = NodeRegistry.getMetadata(sourceNode.type());
        var targetMetadata = NodeRegistry.getMetadata(targetNode.type());
        var sourcePort = sourceMetadata.outputs().stream()
          .filter(p -> p.id().equals(edge.sourceHandle())).findFirst();
        var targetPort = targetMetadata.inputs().stream()
          .filter(p -> p.id().equals(edge.targetHandle())).findFirst();
        if (sourcePort.isPresent() && targetPort.isPresent()) {
          if (!isTypeCompatible(sourcePort.get().type(), targetPort.get().type())) {
            var edgeKey = edge.sourceNodeId() + ":" + edge.sourceHandle() + "->"
              + edge.targetNodeId() + ":" + edge.targetHandle();
            diagnostics.add(new ValidationDiagnostic(edge.targetNodeId(), edgeKey,
              "Type mismatch: " + edge.sourceNodeId() + "." + edge.sourceHandle()
                + " (" + sourcePort.get().type() + ") -> " + edge.targetNodeId() + "." + edge.targetHandle()
                + " (" + targetPort.get().type() + ")", Severity.ERROR));
          }
        }
      }

      // 8. Fan-in ambiguity: multiple DATA edges to a non-multi-input port
      var dataEdgeCountByTarget = new HashMap<String, Integer>();
      for (var edge : edges) {
        if (edge.edgeType() == EdgeType.DATA) {
          var key = edge.targetNodeId() + ":" + edge.targetHandle();
          dataEdgeCountByTarget.merge(key, 1, Integer::sum);
        }
      }
      for (var entry : dataEdgeCountByTarget.entrySet()) {
        if (entry.getValue() > 1) {
          var parts = entry.getKey().split(":", 2);
          var targetNodeId = parts[0];
          var targetHandle = parts[1];
          var targetNode = nodes.get(targetNodeId);
          if (targetNode != null && NodeRegistry.isRegistered(targetNode.type())) {
            var metadata = NodeRegistry.getMetadata(targetNode.type());
            var port = metadata.inputs().stream()
              .filter(p -> p.id().equals(targetHandle)).findFirst();
            if (port.isPresent() && !port.get().multiInput()) {
              diagnostics.add(new ValidationDiagnostic(targetNodeId, entry.getKey(),
                "Multiple data edges to non-multi-input port '" + targetHandle + "'", Severity.ERROR));
            }
          }
        }
      }

      // 9. Unreachable required input detection (warning)
      for (var node : nodes.values()) {
        if (!NodeRegistry.isRegistered(node.type())) {
          continue;
        }
        var metadata = NodeRegistry.getMetadata(node.type());
        for (var input : metadata.inputs()) {
          if (!input.required() || input.type() == PortType.EXEC) {
            continue;
          }
          var inKey = node.id() + ":" + input.id();
          var hasDataEdge = edges.stream().anyMatch(e ->
            e.edgeType() == EdgeType.DATA
              && e.targetNodeId().equals(node.id())
              && e.targetHandle().equals(input.id()));
          var hasDefault = input.defaultValue() != null
            || (node.defaultInputs() != null && node.defaultInputs().containsKey(input.id()));
          if (!hasDataEdge && !hasDefault) {
            diagnostics.add(new ValidationDiagnostic(node.id(), null,
              "Required input '" + input.id() + "' on node " + node.id() + " has no connection or default", Severity.WARNING));
          }
        }
      }

      // 10. Unused output detection (warning): nodes with no connected outputs at all
      for (var node : nodes.values()) {
        if (!NodeRegistry.isRegistered(node.type())) {
          continue;
        }
        var metadata = NodeRegistry.getMetadata(node.type());
        if (metadata.isTrigger()) {
          continue;
        }
        var hasAnyOutgoing = edges.stream().anyMatch(e -> e.sourceNodeId().equals(node.id()));
        if (!hasAnyOutgoing && !metadata.outputs().isEmpty()) {
          diagnostics.add(new ValidationDiagnostic(node.id(), null,
            "Node has no connected outputs", Severity.WARNING));
        }
      }

      // 11. Parallel branch overflow detection (warning)
      var execOutCountByNode = new HashMap<String, Integer>();
      for (var edge : edges) {
        if (edge.edgeType() == EdgeType.EXECUTION) {
          execOutCountByNode.merge(edge.sourceNodeId(), 1, Integer::sum);
        }
      }
      for (var entry : execOutCountByNode.entrySet()) {
        if (entry.getValue() > ReactiveScriptEngine.MAX_PARALLEL_BRANCHES) {
          diagnostics.add(new ValidationDiagnostic(entry.getKey(), null,
            "Node has " + entry.getValue() + " parallel branches, exceeding limit of "
              + ReactiveScriptEngine.MAX_PARALLEL_BRANCHES + ". Excess branches will execute sequentially.",
            Severity.WARNING));
        }
      }

      // 12. Dead node detection: find nodes not reachable from any trigger
      if (diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR)) {
        var nodesWithIncomingExec = new HashSet<String>();
        for (var edge : edges) {
          if (edge.edgeType() == EdgeType.EXECUTION) {
            nodesWithIncomingExec.add(edge.targetNodeId());
          }
        }

        // Find trigger nodes
        var triggerNodes = new ArrayList<String>();
        for (var node : nodes.values()) {
          if (nodesWithIncomingExec.contains(node.id())) {
            continue;
          }
          if (NodeRegistry.isRegistered(node.type()) && NodeRegistry.getMetadata(node.type()).isTrigger()) {
            triggerNodes.add(node.id());
          }
        }

        // BFS from all triggers following all edges (both directions for data edges)
        var reachable = new HashSet<String>();
        var bfsQueue = new ArrayDeque<>(triggerNodes);
        reachable.addAll(triggerNodes);

        // Build adjacency list for all edge types
        var adjAll = new HashMap<String, Set<String>>();
        for (var node : nodes.values()) {
          adjAll.put(node.id(), new HashSet<>());
        }
        for (var edge : edges) {
          adjAll.computeIfAbsent(edge.sourceNodeId(), _ -> new HashSet<>()).add(edge.targetNodeId());
          // For DATA edges, also traverse backward (a data source feeding a reachable node is itself reachable)
          if (edge.edgeType() == EdgeType.DATA) {
            adjAll.computeIfAbsent(edge.targetNodeId(), _ -> new HashSet<>()).add(edge.sourceNodeId());
          }
        }

        while (!bfsQueue.isEmpty()) {
          var current = bfsQueue.poll();
          for (var neighbor : adjAll.getOrDefault(current, Set.of())) {
            if (reachable.add(neighbor)) {
              bfsQueue.add(neighbor);
            }
          }
        }

        for (var node : nodes.values()) {
          if (!reachable.contains(node.id())) {
            diagnostics.add(new ValidationDiagnostic(node.id(), null,
              "Node is not reachable from any trigger", Severity.WARNING));
          }
        }
      }

      // 13. Infinite loop risk: loop nodes without a ResultNode in check chain
      for (var node : nodes.values()) {
        if (!NodeRegistry.isRegistered(node.type())) {
          continue;
        }
        var metadata = NodeRegistry.getMetadata(node.type());
        if (!metadata.execOutputPortIds().contains(StandardPorts.EXEC_CHECK)) {
          continue;
        }
        // BFS from EXEC_CHECK port following EXECUTION edges
        var checkReachable = new HashSet<String>();
        var checkQueue = new ArrayDeque<String>();
        var checkKey = node.id() + ":" + StandardPorts.EXEC_CHECK;
        for (var edge : edges) {
          if (edge.edgeType() == EdgeType.EXECUTION
            && edge.sourceNodeId().equals(node.id())
            && StandardPorts.EXEC_CHECK.equals(edge.sourceHandle())) {
            if (checkReachable.add(edge.targetNodeId())) {
              checkQueue.add(edge.targetNodeId());
            }
          }
        }
        while (!checkQueue.isEmpty()) {
          var current = checkQueue.poll();
          for (var edge : edges) {
            if (edge.edgeType() == EdgeType.EXECUTION && edge.sourceNodeId().equals(current)) {
              if (checkReachable.add(edge.targetNodeId())) {
                checkQueue.add(edge.targetNodeId());
              }
            }
          }
        }
        var hasResult = checkReachable.stream().anyMatch(id -> {
          var n = nodes.get(id);
          return n != null && "flow.result".equals(n.type());
        });
        if (!hasResult) {
          diagnostics.add(new ValidationDiagnostic(node.id(), null,
            "Loop has no ResultNode in check chain, may run forever", Severity.WARNING));
        }
      }

      // 14. Expensive subgraph in tick paths
      var tickTriggerTypes = Set.of("trigger.on_pre_entity_tick", "trigger.on_post_entity_tick");
      for (var node : nodes.values()) {
        if (!tickTriggerTypes.contains(node.type())) {
          continue;
        }
        // BFS from this tick trigger following EXECUTION edges
        var tickReachable = new HashSet<String>();
        var tickQueue = new ArrayDeque<String>();
        for (var edge : edges) {
          if (edge.edgeType() == EdgeType.EXECUTION && edge.sourceNodeId().equals(node.id())) {
            if (tickReachable.add(edge.targetNodeId())) {
              tickQueue.add(edge.targetNodeId());
            }
          }
        }
        while (!tickQueue.isEmpty()) {
          var current = tickQueue.poll();
          for (var edge : edges) {
            if (edge.edgeType() == EdgeType.EXECUTION && edge.sourceNodeId().equals(current)) {
              if (tickReachable.add(edge.targetNodeId())) {
                tickQueue.add(edge.targetNodeId());
              }
            }
          }
        }
        // Check for expensive/blocking nodes
        for (var reachableId : tickReachable) {
          var reachableNode = nodes.get(reachableId);
          if (reachableNode == null || !NodeRegistry.isRegistered(reachableNode.type())) {
            continue;
          }
          var reachableMeta = NodeRegistry.getMetadata(reachableNode.type());
          if (reachableMeta.blocksThread()) {
            diagnostics.add(new ValidationDiagnostic(reachableNode.id(), null,
              "Blocking node '" + reachableMeta.displayName()
                + "' in tick-synchronous path will block game thread",
              Severity.ERROR));
          } else if (reachableMeta.isExpensive()) {
            diagnostics.add(new ValidationDiagnostic(reachableNode.id(), null,
              "Expensive node '" + reachableMeta.displayName()
                + "' in tick-synchronous path may slow game thread",
              Severity.WARNING));
          }
        }
      }

      // 15. Type narrowing through ANY ports
      diagnostics.addAll(TypePropagation.analyze(nodes, edges));

      return diagnostics;
    }

    /// Checks whether a source port type is compatible with a target port type.
    /// Delegates to the shared TypeCompatibility class.
    static boolean isTypeCompatible(PortType source, PortType target) {
      return TypeCompatibility.isCompatible(source, target);
    }

    /// Builds the ScriptGraph after validation.
    /// Throws on ERRORs, but includes WARNINGs in the built graph.
    ///
    /// @throws ScriptGraphValidationException if the graph has ERROR-level diagnostics
    public ScriptGraph build() {
      var diagnostics = validate();
      var errors = diagnostics.stream()
        .filter(d -> d.severity() == Severity.ERROR)
        .toList();
      if (!errors.isEmpty()) {
        throw new ScriptGraphValidationException(diagnostics);
      }
      var warnings = diagnostics.stream()
        .filter(d -> d.severity() == Severity.WARNING)
        .toList();
      return new ScriptGraph(scriptId, scriptName, nodes, edges, warnings);
    }
  }
}
