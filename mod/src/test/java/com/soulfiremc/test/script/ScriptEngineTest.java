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
package com.soulfiremc.test.script;

import com.soulfiremc.server.script.NodeValue;
import com.soulfiremc.server.script.ReactiveScriptContext;
import com.soulfiremc.server.script.ReactiveScriptEngine;
import com.soulfiremc.server.script.ScriptGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.soulfiremc.test.script.ScriptTestHelper.*;
import static org.junit.jupiter.api.Assertions.*;

/// Integration tests for the reactive script engine: data edges, data-only nodes, trigger data edges.
final class ScriptEngineTest {

  @Test
  void dataEdgeDeliversValueAcrossNodes() {
    var graph = ScriptGraph.builder("test-data-edge", "Data Edge Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const_a", "constant.number", Map.of("value", 42))
      .addNode("const_b", "constant.number", Map.of("value", 8))
      .addNode("add", "math.add", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("const_a", "value", "add", "a")
      .addDataEdge("const_b", "value", "add", "b")
      .addDataEdge("add", "result", "print", "message")
      .build();

    var dataEdges = graph.getIncomingDataEdges("add");
    assertEquals(2, dataEdges.size(), "AddNode should have 2 incoming data edges");
  }

  @Test
  void dataOnlyConstantNodeExecutedViaDataEdge() {
    var graph = ScriptGraph.builder("test-data-only", "Data Only Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const_a", "constant.number", Map.of("value", 42))
      .addNode("const_b", "constant.number", Map.of("value", 8))
      .addNode("add", "math.add", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("const_a", "value", "add", "a")
      .addDataEdge("const_b", "value", "add", "b")
      .addDataEdge("add", "result", "print", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("const_a"),
      "Data-only constant node A should be eagerly executed");
    assertTrue(listener.completedNodes.contains("const_b"),
      "Data-only constant node B should be eagerly executed");
    assertTrue(listener.completedNodes.contains("add"),
      "Math add node should complete");

    var addOutputs = listener.nodeOutputs.get("add");
    assertNotNull(addOutputs, "Add node should have outputs");
    assertEquals(50.0, addOutputs.get("result").asDouble(0.0), 0.001);
  }

  @Test
  void dataOnlyStringConstantExecutedViaDataEdge() {
    var graph = ScriptGraph.builder("test-string-const", "String Const Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const", "constant.string", Map.of("value", "hello"))
      .addNode("len", "string.length", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("const", "value", "len", "text")
      .addDataEdge("len", "length", "print", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("const"),
      "Data-only string constant should be eagerly executed");
    assertTrue(listener.completedNodes.contains("len"),
      "String length node should complete");

    var lenOutputs = listener.nodeOutputs.get("len");
    assertNotNull(lenOutputs, "String length node should have outputs");
    assertEquals(5, lenOutputs.get("length").asInt(0), "Length of 'hello' should be 5");
  }

  @Test
  void multiInputEdgesRespectExplicitOrder() {
    var graph = ScriptGraph.builder("test-multi-input-order", "Multi Input Order Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const_a", "constant.string", Map.of("value", "a"))
      .addNode("const_b", "constant.string", Map.of("value", "b"))
      .addNode("const_c", "constant.string", Map.of("value", "c"))
      .addNode("list", "list.create", null)
      .addNode("join", "list.join", Map.of("separator", "-"))
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("const_c", "value", "list", "items", 2)
      .addDataEdge("const_a", "value", "list", "items", 0)
      .addDataEdge("const_b", "value", "list", "items", 1)
      .addDataEdge("list", "list", "join", "list")
      .addDataEdge("join", "result", "print", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    var listOutputs = listener.nodeOutputs.get("list");
    assertNotNull(listOutputs);
    assertEquals(List.of("a", "b", "c"),
      listOutputs.get("list").asList().stream().map(v -> v.asString("")).toList());
  }

  @Test
  void chainedDataOnlyNodesExecutedRecursively() {
    var graph = ScriptGraph.builder("test-chained-data", "Chained Data Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const", "constant.string", Map.of("value", "hello,world,test"))
      .addNode("split", "string.split", Map.of("delimiter", ","))
      .addNode("first", "list.first", null)
      .addNode("len", "string.length", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("const", "value", "split", "text")
      .addDataEdge("split", "result", "first", "list")
      .addDataEdge("first", "item", "len", "text")
      .addDataEdge("len", "length", "print", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("const"),
      "Data-only constant should be eagerly executed");
    assertTrue(listener.completedNodes.contains("split"),
      "Data-only split should be eagerly executed");
    assertTrue(listener.completedNodes.contains("first"),
      "Data-only list.first should be eagerly executed");
    assertTrue(listener.completedNodes.contains("len"),
      "String length node should complete");

    var lenOutputs = listener.nodeOutputs.get("len");
    assertNotNull(lenOutputs, "String length node should have outputs");
    assertEquals(5, lenOutputs.get("length").asInt(0), "Length of 'hello' should be 5");
  }

  @Test
  void dataOnlyNodeSharedByMultipleConsumers() {
    var graph = ScriptGraph.builder("test-shared-data", "Shared Data Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const", "constant.string", Map.of("value", "shared"))
      .addNode("print1", "action.print", null)
      .addNode("print2", "action.print", null)
      .addExecutionEdge("trigger", "out", "print1", "in")
      .addExecutionEdge("print1", "out", "print2", "in")
      .addDataEdge("const", "value", "print1", "message")
      .addDataEdge("const", "value", "print2", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("const"),
      "Data-only constant should be eagerly executed");
    assertTrue(listener.completedNodes.contains("print1"),
      "First print node should complete");
    assertTrue(listener.completedNodes.contains("print2"),
      "Second print node should complete (same constant consumed twice)");
  }

  @Test
  void dataOnlyNodeExecutedExactlyOnceWithMultipleConsumers() {
    // Item 20: Verify result caching - a data-only node consumed by multiple downstream
    // nodes should execute exactly once per trigger invocation.
    var graph = ScriptGraph.builder("test-data-cache", "Data Cache Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("const", "constant.number", Map.of("value", 42))
      .addNode("add1", "math.add", Map.of("a", 0))
      .addNode("add2", "math.add", Map.of("a", 0))
      .addNode("print1", "action.print", null)
      .addNode("print2", "action.print", null)
      .addExecutionEdge("trigger", "out", "print1", "in")
      .addExecutionEdge("print1", "out", "print2", "in")
      .addDataEdge("const", "value", "add1", "b")
      .addDataEdge("const", "value", "add2", "b")
      .addDataEdge("add1", "result", "print1", "message")
      .addDataEdge("add2", "result", "print2", "message")
      .build();

    var listener = runGraph(graph, "trigger");

    assertNoErrors(listener);
    assertEquals(1, countNodeExecutions(listener, "const"),
      "Data-only constant node should execute exactly once, not once per consumer");
    assertTrue(listener.completedNodes.contains("add1"),
      "First add node should complete");
    assertTrue(listener.completedNodes.contains("add2"),
      "Second add node should complete");
    assertTrue(listener.completedNodes.contains("print1"),
      "First print node should complete");
    assertTrue(listener.completedNodes.contains("print2"),
      "Second print node should complete");
  }

  @Test
  void triggerDataEdgeNotReExecuted() {
    var graph = ScriptGraph.builder("test-trigger-data-edge", "Trigger Data Edge Test")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("len", "string.length", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("trigger", "messagePlainText", "len", "text")
      .addDataEdge("len", "length", "print", "message")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("hello world"));
    eventInputs.put("message", NodeValue.ofString("hello world"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = runGraph(graph, "trigger", eventInputs);

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("trigger"),
      "Trigger node should complete");
    assertTrue(listener.completedNodes.contains("len"),
      "StringLength node should complete");
    assertTrue(listener.completedNodes.contains("print"),
      "Print node should complete");

    var lenOutputs = listener.nodeOutputs.get("len");
    assertNotNull(lenOutputs, "StringLength node should have outputs");
    assertEquals(11, lenOutputs.get("length").asInt(0),
      "StringLength should see 'hello world' (length 11) from event inputs, not empty string from re-execution");

    var triggerStartCount = countNodeExecutions(listener, "trigger");
    assertEquals(1, triggerStartCount,
      "Trigger should be executed exactly once, not re-executed as a data-only node");
  }

  @Test
  void onChatPlainTextToPrintViaDataEdge() {
    // Exact scenario: OnChat → Print with messagePlainText DATA edge
    var graph = ScriptGraph.builder("test-onchat-print-plain", "OnChat Print Plain Text")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("trigger", "messagePlainText", "print", "message")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("Hello from chat"));
    eventInputs.put("message", NodeValue.ofString("§aHello from chat"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("trigger"),
      "Trigger node should complete");
    assertTrue(listener.completedNodes.contains("print"),
      "Print node should complete");

    // Verify the trigger outputs contain the event data
    var triggerOutputs = listener.nodeOutputs.get("trigger");
    assertNotNull(triggerOutputs, "Trigger should have outputs");
    assertEquals("Hello from chat", triggerOutputs.get("messagePlainText").asString("MISSING"),
      "Trigger should output messagePlainText from event inputs");
    assertEquals("§aHello from chat", triggerOutputs.get("message").asString("MISSING"),
      "Trigger should output message from event inputs");

    // Verify the print node logged the correct message
    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("Hello from chat", listener.logMessages.getFirst(),
      "Print should output the plain text message via DATA edge");
  }

  @Test
  void onChatMessageToPrintViaDataEdge() {
    // Exact scenario: OnChat → Print with message (legacy format) DATA edge
    var graph = ScriptGraph.builder("test-onchat-print-message", "OnChat Print Message")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("trigger", "message", "print", "message")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("Hello from chat"));
    eventInputs.put("message", NodeValue.ofString("§aHello from chat"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("print"),
      "Print node should complete");

    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("§aHello from chat", listener.logMessages.getFirst(),
      "Print should output the legacy message via DATA edge");
  }

  @Test
  void onChatPrintReceivesExecContextWithoutDataEdge() {
    // When there's no DATA edge, Print should still receive trigger outputs via exec context
    var graph = ScriptGraph.builder("test-onchat-print-context", "OnChat Print Exec Context")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("Hello via context"));
    eventInputs.put("message", NodeValue.ofString("§aHello via context"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("print"),
      "Print node should complete");

    // Without a DATA edge, Print gets "message" from exec context (the trigger's output)
    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("§aHello via context", listener.logMessages.getFirst(),
      "Print should receive message from execution context when no DATA edge exists");
  }

  @Test
  void longLinearChainDoesNotOverflowStack() {
    // A long chain of sequential nodes causes deeply nested flatMap operators.
    // Each node adds ~3-4 Reactor operators; 1000 nodes = ~3000-4000 nested operators.
    var builder = ScriptGraph.builder("test-long-chain", "Long Chain Test")
      .addNode("trigger", "trigger.on_script_init", null);

    var prevNode = "trigger";
    for (var i = 0; i < 1000; i++) {
      var nodeId = "print_" + i;
      builder.addNode(nodeId, "action.print", Map.of("message", "node " + i));
      builder.addExecutionEdge(prevNode, "out", nodeId, "in");
      prevNode = nodeId;
    }

    var listener = runGraph(builder.build(), "trigger");

    assertNoErrors(listener);
    assertTrue(countNodeExecutions(listener, "print_999") > 0,
      "All 1000 nodes should execute without StackOverflowError");
  }

  @Test
  @Timeout(5)
  void loopNodeDataEdgeResolvesWithoutTimeout() {
    // Loop(count=3) → Print with DATA edge Loop.index → Print.message
    // This tests that on-path DATA edges from self-driving loop nodes resolve without timeout
    var graph = ScriptGraph.builder("test-loop-data-edge", "Loop Data Edge Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("loop", "flow.loop", Map.of("count", 3))
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "loop", "in")
      .addExecutionEdge("loop", "exec_loop", "print", "in")
      .addDataEdge("loop", "index", "print", "message")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertEquals(3, listener.logMessages.size(),
      "Loop should produce 3 print messages, got: " + listener.logMessages);
  }

  @Test
  @Timeout(5)
  void loopNodeDataEdgeToIndirectDownstream() {
    // Loop(count=3) → Print1 → Print2 with DATA edge Loop.index → Print2.message
    // Tests DATA edge from loop node to a non-immediately-downstream node in the loop body
    var graph = ScriptGraph.builder("test-loop-indirect-data", "Loop Indirect Data Edge Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("loop", "flow.loop", Map.of("count", 3))
      .addNode("print1", "action.print", Map.of("message", "step"))
      .addNode("print2", "action.print", null)
      .addExecutionEdge("trigger", "out", "loop", "in")
      .addExecutionEdge("loop", "exec_loop", "print1", "in")
      .addExecutionEdge("print1", "out", "print2", "in")
      .addDataEdge("loop", "index", "print2", "message")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertEquals(6, listener.logMessages.size(),
      "Should have 6 log messages (3 from print1 + 3 from print2), got: " + listener.logMessages);
  }

  @Test
  @Timeout(5)
  void forEachDataEdgeResolvesWithoutTimeout() {
    // Trigger → ForEach(items from data-only Range) → Print with DATA edge ForEach.index → Print.message
    var graph = ScriptGraph.builder("test-foreach-data-edge", "ForEach Data Edge Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("range", "list.range", Map.of("start", 0, "end", 3, "step", 1))
      .addNode("foreach", "flow.foreach", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "foreach", "in")
      .addDataEdge("range", "list", "foreach", "items")
      .addExecutionEdge("foreach", "exec_loop", "print", "in")
      .addDataEdge("foreach", "index", "print", "message")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertEquals(3, listener.logMessages.size(),
      "ForEach should produce 3 print messages, got: " + listener.logMessages);
  }

  @Test
  @Timeout(5)
  void waitNodeCompletesWithinTimeout() {
    var graph = ScriptGraph.builder("test-wait-node", "Wait Node Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("wait", "action.wait", Map.of("baseMs", 10, "jitterMs", 0))
      .addNode("print", "action.print", Map.of("message", "after wait"))
      .addExecutionEdge("trigger", "out", "wait", "in")
      .addExecutionEdge("wait", "out", "print", "in")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTrigger(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("wait"),
      "Wait node should complete");
    assertTrue(listener.completedNodes.contains("print"),
      "Print node after wait should complete");
    assertEquals(1, listener.logMessages.size(),
      "Print should log exactly one message");
    assertEquals("after wait", listener.logMessages.getFirst(),
      "Print should output 'after wait'");
  }

  @Test
  @Timeout(5)
  void waitNodeCompletesWithSyncExecution() {
    var graph = ScriptGraph.builder("test-wait-sync", "Wait Sync Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("wait", "action.wait", Map.of("baseMs", 10, "jitterMs", 0))
      .addNode("print", "action.print", Map.of("message", "after sync wait"))
      .addExecutionEdge("trigger", "out", "wait", "in")
      .addExecutionEdge("wait", "out", "print", "in")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("wait"),
      "Wait node should complete in sync mode");
    assertTrue(listener.completedNodes.contains("print"),
      "Print node after wait should complete in sync mode");
    assertEquals(1, listener.logMessages.size(),
      "Print should log exactly one message");
    assertEquals("after sync wait", listener.logMessages.getFirst(),
      "Print should output 'after sync wait'");
  }

  @Test
  @Timeout(5)
  void cancelDuringWaitStopsExecution() {
    // Graph: trigger → wait(1s) → print
    // Start execution, cancel after 100ms
    // Verify print never runs
    var graph = ScriptGraph.builder("test-cancel-wait", "Cancel During Wait")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("wait", "action.wait", Map.of("baseMs", 2000, "jitterMs", 0))
      .addNode("print", "action.print", Map.of("message", "should not run"))
      .addExecutionEdge("trigger", "out", "wait", "in")
      .addExecutionEdge("wait", "out", "print", "in")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();

    // Start async execution and cancel after 100ms
    var disposable = engine.executeFromTrigger(graph, "trigger", context, Map.of())
      .subscribe();

    try {
      Thread.sleep(100);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
    context.cancel();
    disposable.dispose();

    assertFalse(listener.completedNodes.contains("print"),
      "Print node should not execute after cancel during wait");
  }

  @Test
  void printNodeWithMessage() {
    var graph = ScriptGraph.builder("test-print-msg", "Print Message Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("print", "action.print", Map.of("message", "hello world"))
      .addExecutionEdge("trigger", "out", "print", "in")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertEquals(1, listener.logMessages.size(), "Should have one log message");
    assertEquals("hello world", listener.logMessages.getFirst());
  }

  @Test
  void printNodeWithNullMessage() {
    // Print with no message connection should report missing required input (Item 9)
    var graph = ScriptGraph.builder("test-print-null", "Print Null Test")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    // Input contract validation rejects the node because 'message' is a required input
    assertFalse(listener.errorNodes.isEmpty(),
      "Should report error for missing required input");
    assertTrue(listener.errorNodes.values().stream()
        .anyMatch(msg -> msg.contains("Missing required input")),
      "Error should mention missing required input");
  }

  // ==================== Execution Context vs Graph Default Priority Tests ====================

  @Test
  void graphDefaultNotOverriddenByExecContext() {
    // Regression test: trigger outputs "message" via exec context, but downstream node
    // has "message" explicitly set as graph default. The graph default must win.
    // This was the root cause of bots being kicked for "Invalid characters in chat" when
    // on_chat trigger's "message" (containing section symbols) silently overwrote send_chat's
    // configured "/afk" input.
    var graph = ScriptGraph.builder("test-graph-default-priority", "Graph Default Priority")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", Map.of("message", "/afk"))
      .addExecutionEdge("trigger", "out", "print", "in")
      // No DATA edge: "message" only flows via execution context
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("hello"));
    eventInputs.put("message", NodeValue.ofString("§aHello with formatting"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("/afk", listener.logMessages.getFirst(),
      "Graph default '/afk' must NOT be overridden by trigger's 'message' in execution context");
  }

  @Test
  void dataEdgeStillOverridesGraphDefault() {
    // When a DATA edge explicitly wires trigger.message → print.message,
    // the wired value should take priority over the graph default.
    var graph = ScriptGraph.builder("test-data-edge-priority", "Data Edge Priority")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", Map.of("message", "/afk"))
      .addExecutionEdge("trigger", "out", "print", "in")
      .addDataEdge("trigger", "message", "print", "message")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("hello"));
    eventInputs.put("message", NodeValue.ofString("§aWired value"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("§aWired value", listener.logMessages.getFirst(),
      "DATA edge value must override graph default when explicitly wired");
  }

  @Test
  void execContextStillFillsUnsetInputs() {
    // When a downstream node has NO graph default for an input, execution context
    // should still flow through and fill it (backwards compatibility).
    var graph = ScriptGraph.builder("test-exec-context-fills", "Exec Context Fills Unset")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print", "action.print", null)
      .addExecutionEdge("trigger", "out", "print", "in")
      // No graph default for "message", no DATA edge
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("hello"));
    eventInputs.put("message", NodeValue.ofString("§aFrom context"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertEquals(1, listener.logMessages.size(), "Print should log exactly one message");
    assertEquals("§aFrom context", listener.logMessages.getFirst(),
      "Execution context should fill inputs that have no graph default set");
  }

  @Test
  void graphDefaultPreservedAcrossChainedNodes() {
    // Trigger → print1 (no graph default) → print2 (has graph default "custom")
    // print1 should receive trigger's "message" via exec context.
    // print2's graph default should NOT be overridden by exec context.
    var graph = ScriptGraph.builder("test-chain-default", "Chained Graph Default")
      .addNode("trigger", "trigger.on_chat", null)
      .addNode("print1", "action.print", null)
      .addNode("print2", "action.print", Map.of("message", "custom"))
      .addExecutionEdge("trigger", "out", "print1", "in")
      .addExecutionEdge("print1", "out", "print2", "in")
      .build();

    var eventInputs = new HashMap<String, NodeValue>();
    eventInputs.put("messagePlainText", NodeValue.ofString("hello"));
    eventInputs.put("message", NodeValue.ofString("§aFrom trigger"));
    eventInputs.put("timestamp", NodeValue.ofNumber(System.currentTimeMillis()));

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, eventInputs).block();

    assertNoErrors(listener);
    assertEquals(2, listener.logMessages.size(), "Should have 2 log messages");
    assertEquals("§aFrom trigger", listener.logMessages.getFirst(),
      "print1 (no graph default) should receive message from exec context");
    assertEquals("custom", listener.logMessages.get(1),
      "print2 (has graph default) must keep its graph default, not exec context");
  }

  @Test
  @Timeout(5)
  void offPathConstantOverridesGraphDefaultInChain() {
    // Reproduces bug report: trigger → wait → print → send_chat (graph default message=""),
    // where a shared constant feeds both print and send_chat via DATA edges.
    // The graph default ("") must be overridden by the off-path DATA edge value ("shards").
    // Uses print nodes as stand-ins since send_chat requires a live bot.
    var graph = ScriptGraph.builder("test-offpath-chain-override", "Off-Path Chain Override")
      .addNode("trigger", "trigger.on_script_init", null)
      .addNode("wait", "action.wait", Map.of("baseMs", 10, "jitterMs", 0))
      .addNode("const", "constant.string", Map.of("value", "shards"))
      .addNode("print1", "action.print", null)
      .addNode("print2", "action.print", Map.of("message", ""))
      .addExecutionEdge("trigger", "out", "wait", "in")
      .addExecutionEdge("wait", "out", "print1", "in")
      .addExecutionEdge("print1", "out", "print2", "in")
      .addDataEdge("const", "value", "print1", "message")
      .addDataEdge("const", "value", "print2", "message")
      .build();

    var listener = new LogRecordingEventListener();
    var context = new ReactiveScriptContext(listener);
    var engine = new ReactiveScriptEngine();
    engine.executeFromTriggerSync(graph, "trigger", context, Map.of()).block();

    assertNoErrors(listener);
    assertTrue(listener.completedNodes.contains("const"),
      "Data-only constant should be eagerly executed");
    assertTrue(listener.completedNodes.contains("print1"),
      "First print should complete");
    assertTrue(listener.completedNodes.contains("print2"),
      "Second print (with graph default) should complete");

    assertEquals(2, listener.logMessages.size(),
      "Both prints should log, got: " + listener.logMessages);
    assertEquals("shards", listener.logMessages.getFirst(),
      "print1 should receive 'shards' from constant via off-path DATA edge");
    assertEquals("shards", listener.logMessages.get(1),
      "print2 should receive 'shards' from constant, overriding graph default ''");
  }

  /// Extended RecordingEventListener that also captures log messages from Print nodes.
  private static class LogRecordingEventListener extends RecordingEventListener {
    final List<String> logMessages = new ArrayList<>();

    @Override
    public void onLog(String level, String message) {
      logMessages.add(message);
    }
  }
}
