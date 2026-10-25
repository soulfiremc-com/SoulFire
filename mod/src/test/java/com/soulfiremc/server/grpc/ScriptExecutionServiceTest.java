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
package com.soulfiremc.server.grpc;

import com.google.gson.JsonArray;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.linecorp.armeria.server.Server;
import com.soulfiremc.grpc.generated.ActivateScriptRequest;
import com.soulfiremc.grpc.generated.CreateScriptRequest;
import com.soulfiremc.grpc.generated.CreateScriptResponse;
import com.soulfiremc.grpc.generated.DeactivateScriptRequest;
import com.soulfiremc.grpc.generated.DeleteScriptRequest;
import com.soulfiremc.grpc.generated.DiagnosticSeverity;
import com.soulfiremc.grpc.generated.DryRunScriptRequest;
import com.soulfiremc.grpc.generated.EdgeType;
import com.soulfiremc.grpc.generated.GetScriptRequest;
import com.soulfiremc.grpc.generated.GetScriptResponse;
import com.soulfiremc.grpc.generated.GetScriptStatusRequest;
import com.soulfiremc.grpc.generated.GetScriptStatusResponse;
import com.soulfiremc.grpc.generated.LogLevel;
import com.soulfiremc.grpc.generated.PortType;
import com.soulfiremc.grpc.generated.Position;
import com.soulfiremc.grpc.generated.ScriptEdge;
import com.soulfiremc.grpc.generated.ScriptEvent;
import com.soulfiremc.grpc.generated.ScriptLogEntry;
import com.soulfiremc.grpc.generated.ScriptNode;
import com.soulfiremc.grpc.generated.ScriptQuotas;
import com.soulfiremc.grpc.generated.SubscribeScriptLogsRequest;
import com.soulfiremc.grpc.generated.UpdateScriptRequest;
import com.soulfiremc.grpc.generated.UpdateScriptResponse;
import com.soulfiremc.grpc.generated.ValidateScriptRequest;
import com.soulfiremc.grpc.generated.ValidateScriptResponse;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.script.AbstractScriptNode;
import com.soulfiremc.server.script.CategoryRegistry;
import com.soulfiremc.server.script.NodeMetadata;
import com.soulfiremc.server.script.NodeRuntime;
import com.soulfiremc.server.script.NodeValue;
import com.soulfiremc.server.script.PortDefinition;
import com.soulfiremc.server.script.nodes.NodeRegistry;
import com.soulfiremc.server.settings.lib.InstanceSettingsImpl;
import com.soulfiremc.server.user.AuthSystem;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.server.util.structs.GsonInstance;
import com.soulfiremc.shared.SFLogAppender;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import net.minecraft.world.phys.Vec3;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.TransactionalCallable;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ScriptExecutionServiceTest {
  private final SoulFireServer server = mock();
  private final ScriptServiceImpl service = new ScriptServiceImpl(server);
  private final Context rpcContext = Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, mock(SoulFireUser.class));
  private final String instanceId = UUID.randomUUID().toString();
  private final String triggerId = UUID.randomUUID().toString();
  private final String actionId = UUID.randomUUID().toString();
  private final ServerCallStreamObserver<ScriptEvent> observer = mock();
  private final List<ScriptEvent> events = new CopyOnWriteArrayList<>();
  private final AtomicReference<Runnable> cancel = new AtomicReference<>();
  private final AtomicReference<Throwable> error = new AtomicReference<>();
  private final CountDownLatch terminal = new CountDownLatch(1);

  @BeforeEach
  void setUp(@TempDir Path directory) {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + directory.resolve("scripts.sqlite"));
    Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    when(server.dsl()).thenReturn(DSL.using(source, SQLDialect.SQLITE));
    var ownerId = UUID.randomUUID().toString();
    server.dsl().execute("INSERT INTO users (id, username, email, role, min_issued_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
      ownerId, "owner", "owner@example.com", "ADMIN");
    server.dsl().insertInto(Tables.INSTANCES)
      .set(Tables.INSTANCES.ID, instanceId).set(Tables.INSTANCES.FRIENDLY_NAME, "scripts")
      .set(Tables.INSTANCES.OWNER_ID, ownerId).set(Tables.INSTANCES.ICON, "pickaxe")
      .set(Tables.INSTANCES.SETTINGS, GsonInstance.CONFIG_GSON.toJson(InstanceSettingsImpl.Stem.EMPTY.serializeToTree())).execute();
    doAnswer(call -> { cancel.set(call.getArgument(0)); return null; }).when(observer).setOnCancelHandler(any());
    doAnswer(call -> { events.add(call.getArgument(0)); return null; }).when(observer).onNext(any());
    doAnswer(_ -> { terminal.countDown(); return null; }).when(observer).onCompleted();
    doAnswer(call -> { error.set(call.getArgument(0)); terminal.countDown(); return null; }).when(observer).onError(any());
  }

  private String saveScript(String actionType) {
    return saveScript(actionType, "trigger.on_chat");
  }

  private String saveScript(String actionType, String triggerType) {
    var response = new AtomicReference<CreateScriptResponse>();
    StreamObserver<CreateScriptResponse> created = mock();
    doAnswer(call -> { response.set(call.getArgument(0)); return null; }).when(created).onNext(any());
    var request = CreateScriptRequest.newBuilder().setInstanceId(instanceId).setName("dry run").setPaused(true)
      .addNodes(ScriptNode.newBuilder().setId(triggerId).setType(triggerType))
      .addNodes(ScriptNode.newBuilder().setId(actionId).setType(actionType))
      .addEdges(ScriptEdge.newBuilder().setId(UUID.randomUUID().toString())
        .setSource(triggerId).setSourceHandle("out").setTarget(actionId).setTargetHandle("in"))
      .build();
    rpcContext.run(() -> service.createScript(request, created));
    return response.get().getScript().getId();
  }

  private DryRunScriptRequest.Builder request(String scriptId) {
    return DryRunScriptRequest.newBuilder().setInstanceId(instanceId).setScriptId(scriptId).setTriggerNodeId(triggerId);
  }

  @Test
  void emptyResultsCompleteTheIdentifiedInvocation() throws InterruptedException {
    run(request(saveScript(registerAction(Mono::empty))).build());
    awaitCompletion();
    var started = events.stream().filter(ScriptEvent::hasNodeStarted).map(ScriptEvent::getNodeStarted)
      .filter(event -> event.getNodeId().equals(actionId)).findFirst().orElseThrow();
    var completed = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(actionId)).findFirst().orElseThrow();
    assertTrue(completed.hasExecutionId());
    assertEquals(started.getExecutionId(), completed.getExecutionId());
    assertTrue(events.getLast().getScriptCompleted().getSuccess());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void streamedNodeEventsCorrelateEachInvocation(boolean failing) throws InterruptedException {
    var type = registerAction(() -> failing ? Mono.error(new IllegalStateException()) : Mono.just(Map.of()));
    run(request(saveScript(type)).build());
    awaitCompletion();
    var starts = events.stream().filter(ScriptEvent::hasNodeStarted).map(ScriptEvent::getNodeStarted).toList();
    assertEquals(2, starts.size());
    assertTrue(starts.stream().allMatch(event -> event.hasExecutionId() && event.getExecutionId() > 0));
    assertEquals(starts.size(), starts.stream().map(event -> event.getExecutionId()).distinct().count());
    for (var event : events) {
      if (event.hasNodeCompleted()) {
        var completed = event.getNodeCompleted();
        assertTrue(completed.hasExecutionId());
        assertTrue(starts.stream().anyMatch(start -> start.getNodeId().equals(completed.getNodeId())
          && start.getExecutionId() == completed.getExecutionId()));
      } else if (event.hasNodeError()) {
        var error = event.getNodeError();
        assertTrue(error.hasExecutionId());
        assertTrue(starts.stream().anyMatch(start -> start.getNodeId().equals(error.getNodeId())
          && start.getExecutionId() == error.getExecutionId()));
      }
    }
    assertEquals(failing, events.stream().anyMatch(ScriptEvent::hasNodeError));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void statusFollowsExecutingNodesWithoutActivatingAMonitor(boolean automatic) throws InterruptedException {
    var scheduler = new SoulFireScheduler(runnable -> runnable);
    var instance = mock(InstanceManager.class);
    when(instance.scheduler()).thenReturn(scheduler);
    when(server.getInstance(UUID.fromString(instanceId))).thenReturn(Optional.of(instance));
    var started = new CountDownLatch(1);
    var result = Sinks.<Map<String, NodeValue>>one();
    var scriptId = saveScript(registerAction(() -> {
      started.countDown();
      return result.asMono();
    }), "trigger.on_script_init");
    try {
      if (automatic) {
        this.<UpdateScriptResponse>rpcResponse(o -> service.updateScript(UpdateScriptRequest.newBuilder()
          .setInstanceId(instanceId).setScriptId(scriptId).setPaused(false).build(), o));
      } else {
        rpcContext.run(() -> service.activateScript(ActivateScriptRequest.newBuilder()
          .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
      }
      assertTrue(started.await(5, TimeUnit.SECONDS));
      var request = GetScriptStatusRequest.newBuilder().setInstanceId(instanceId).setScriptId(scriptId).build();
      var active = this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(request, o)).getStatus();
      assertEquals(actionId, active.getActiveNodeId());
      assertEquals(1, active.getActivationCount());
      result.tryEmitValue(Map.of());
      var finished = this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(request, o)).getStatus();
      assertTrue(finished.getIsActive());
      assertFalse(finished.hasActiveNodeId());
      assertEquals(1, finished.getActivationCount());
    } finally {
      service.stopAllScripts(UUID.fromString(instanceId));
      scheduler.shutdown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void statusIncludesPersistedPauseSettingWithoutAnActiveRuntime(boolean paused) {
    var scriptId = saveScript("utility.log");
    server.dsl().update(Tables.SCRIPTS).set(Tables.SCRIPTS.PAUSED, paused)
      .where(Tables.SCRIPTS.ID.eq(scriptId)).execute();

    var status = this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(GetScriptStatusRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).build(), o)).getStatus();

    assertTrue(status.hasPaused());
    assertEquals(paused, status.getPaused());
    assertFalse(status.getIsActive());
  }

  private void run(DryRunScriptRequest request) {
    rpcContext.run(() -> service.dryRunScript(request, observer));
  }

  private void awaitCompletion() throws InterruptedException {
    assertTrue(terminal.await(5, TimeUnit.SECONDS));
    assertNull(error.get());
  }

  @Test
  void preservesExplicitNullMembersAcrossCreateReadAndUpdate() {
    var nil = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    var nested = Value.newBuilder().setStructValue(Struct.newBuilder()
      .putFields("missing", nil)
      .putFields("items", Value.newBuilder().setListValue(ListValue.newBuilder()
        .addValues(nil)
        .addValues(Value.newBuilder().setStructValue(Struct.newBuilder().putFields("missing", nil)))
        .addValues(Value.newBuilder().setBoolValue(false))
        .addValues(Value.newBuilder().setNumberValue(0))
        .addValues(Value.newBuilder().setStringValue(""))).build())).build();
    var node = ScriptNode.newBuilder().setId(actionId).setType("json.stringify")
      .putData("value", nested).putData("nullable", nil).build();
    var created = this.<CreateScriptResponse>rpcResponse(observer -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setName("null values").setPaused(true).addNodes(node).build(), observer));
    var scriptId = created.getScript().getId();
    assertEquals(node.getDataMap(), created.getScript().getNodes(0).getDataMap());
    var read = this.<GetScriptResponse>rpcResponse(observer -> service.getScript(GetScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
    assertEquals(node.getDataMap(), read.getScript().getNodes(0).getDataMap());

    var changed = node.toBuilder().putData("added", nested).build();
    var updated = this.<UpdateScriptResponse>rpcResponse(observer -> service.updateScript(UpdateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).setUpdateNodes(true).addNodes(changed).build(), observer));
    assertEquals(changed.getDataMap(), updated.getScript().getNodes(0).getDataMap());
    var reread = this.<GetScriptResponse>rpcResponse(observer -> service.getScript(GetScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
    assertEquals(changed.getDataMap(), reread.getScript().getNodes(0).getDataMap());
  }

  private <T> T rpcResponse(Consumer<StreamObserver<T>> action) {
    var response = new AtomicReference<T>();
    var failure = new AtomicReference<Throwable>();
    StreamObserver<T> observer = mock();
    doAnswer(call -> { response.set(call.getArgument(0)); return null; }).when(observer).onNext(any());
    doAnswer(call -> { failure.set(call.getArgument(0)); return null; }).when(observer).onError(any());
    rpcContext.run(() -> action.accept(observer));
    assertNull(failure.get());
    return response.get();
  }

  @Test
  void preservesNodeMetadataAndEdgeOrderAcrossSaveAndReload() {
    var frameId = UUID.randomUUID().toString();
    var frame = ScriptNode.newBuilder().setId(frameId).setType("layout.frame")
      .setPosition(Position.newBuilder().setX(-20).setY(-30))
      .setWidth(512.5).setHeight(321).setLabel("frame").addContainedNodes(actionId).build();
    var node = ScriptNode.newBuilder().setId(actionId).setType("action.print")
      .setPosition(Position.newBuilder().setX(10).setY(20))
      .setMuted(true).setCollapsed(true).setLabel("muted print").setWidth(0).setHeight(234.5)
      .setParentFrameId(frameId).setResolvedType(PortType.PORT_TYPE_STRING).build();
    var trigger = ScriptNode.newBuilder().setId(triggerId).setType("trigger.on_chat")
      .setPosition(Position.getDefaultInstance()).build();
    var edge = ScriptEdge.newBuilder().setId(UUID.randomUUID().toString())
      .setSource(triggerId).setSourceHandle("out").setTarget(actionId).setTargetHandle("in")
      .setEdgeType(EdgeType.EDGE_TYPE_EXECUTION).setOrder(9).build();
    var nodes = List.of(trigger, node, frame);
    var created = this.<CreateScriptResponse>rpcResponse(observer -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setName("metadata").setPaused(true).addAllNodes(nodes).addEdges(edge).build(), observer));
    assertEquals(nodes, created.getScript().getNodesList());
    assertEquals(List.of(edge), created.getScript().getEdgesList());
    var scriptId = created.getScript().getId();
    for (var explicitZero : List.of(false, true)) {
      var changedBuilder = node.toBuilder().setMuted(false).setCollapsed(false).setLabel("")
        .clearWidth().clearHeight().clearContainedNodes().setParentFrameId("")
        .clearResolvedType();
      var changedEdgeBuilder = edge.toBuilder().clearOrder();
      if (explicitZero) {
        changedBuilder.setResolvedTypeValue(0);
        changedEdgeBuilder.setOrder(0);
      }
      var changed = changedBuilder.build();
      var changedNodes = List.of(trigger, changed, frame);
      var changedEdge = changedEdgeBuilder.build();
      var updated = this.<UpdateScriptResponse>rpcResponse(observer -> service.updateScript(UpdateScriptRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(scriptId).setUpdateNodes(true).addAllNodes(changedNodes)
        .setUpdateEdges(true).addEdges(changedEdge).build(), observer));
      assertEquals(changedNodes, updated.getScript().getNodesList());
      assertEquals(List.of(changedEdge), updated.getScript().getEdgesList());
      var read = this.<GetScriptResponse>rpcResponse(observer -> service.getScript(GetScriptRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
      assertEquals(changedNodes, read.getScript().getNodesList());
      assertEquals(List.of(changedEdge), read.getScript().getEdgesList());
    }
  }

  @Test
  void readsStoredGraphsWithoutOptionalMetadata() {
    var scriptId = saveScript("action.print");
    var record = server.dsl().selectFrom(Tables.SCRIPTS).where(Tables.SCRIPTS.ID.eq(scriptId)).fetchSingle();
    var nodes = GsonInstance.GSON.fromJson(record.getNodesJson(), JsonArray.class);
    for (var node : nodes) {
      node.getAsJsonObject().keySet().retainAll(List.of("id", "type", "position", "data"));
    }
    server.dsl().update(Tables.SCRIPTS).set(Tables.SCRIPTS.NODES_JSON, GsonInstance.CONFIG_GSON.toJson(nodes))
      .where(Tables.SCRIPTS.ID.eq(scriptId)).execute();
    var read = this.<GetScriptResponse>rpcResponse(observer -> service.getScript(GetScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
    assertEquals(2, read.getScript().getNodesCount());
    for (var node : read.getScript().getNodesList()) {
      assertFalse(node.getMuted());
      assertFalse(node.getCollapsed());
      assertFalse(node.hasWidth());
      assertFalse(node.hasHeight());
      assertFalse(node.hasResolvedType());
      assertTrue(node.getContainedNodesList().isEmpty());
      assertTrue(node.getLabel().isEmpty());
      assertTrue(node.getParentFrameId().isEmpty());
    }
    assertFalse(read.getScript().getEdges(0).hasOrder());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void savedMutedNodesBypassExecutionAndKeepDownstreamNodesRunning(boolean insideGroup) throws InterruptedException {
    var groupId = UUID.randomUUID().toString();
    var afterId = UUID.randomUUID().toString();
    var muted = ScriptNode.newBuilder().setId(actionId).setType("action.print").setMuted(true)
      .putData("message", Value.newBuilder().setStringValue("skip").build());
    if (insideGroup) {
      muted.putData("parentGroupId", Value.newBuilder().setStringValue(groupId).build());
    }
    var request = CreateScriptRequest.newBuilder().setInstanceId(instanceId).setName("muted node").setPaused(true)
      .addNodes(ScriptNode.newBuilder().setId(triggerId).setType("trigger.on_chat"))
      .addNodes(muted)
      .addNodes(ScriptNode.newBuilder().setId(afterId).setType("action.print")
        .putData("message", Value.newBuilder().setStringValue("after").build()))
      .addEdges(ScriptEdge.newBuilder().setId(UUID.randomUUID().toString())
        .setSource(triggerId).setSourceHandle("out").setTarget(actionId).setTargetHandle("in"))
      .addEdges(ScriptEdge.newBuilder().setId(UUID.randomUUID().toString())
        .setSource(actionId).setSourceHandle("out").setTarget(afterId).setTargetHandle("in"));
    if (insideGroup) {
      request.addNodes(ScriptNode.newBuilder().setId(groupId).setType("layout.group"));
    }
    var created = this.<CreateScriptResponse>rpcResponse(observer -> service.createScript(request.build(), observer));
    run(request(created.getScript().getId()).build());
    awaitCompletion();
    assertTrue(events.getLast().getScriptCompleted().getSuccess());
    assertEquals(List.of("after"), events.stream().filter(ScriptEvent::hasScriptLog)
      .map(event -> event.getScriptLog().getMessage()).toList());
    assertTrue(events.stream().filter(ScriptEvent::hasNodeCompleted)
      .anyMatch(event -> event.getNodeCompleted().getNodeId().equals(actionId)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"debug", "info", "warn", "error"})
  void automaticallyStartedScriptsStreamEventsAndRetainScopedLogs(String level) throws InterruptedException {
    var scheduler = new SoulFireScheduler(runnable -> runnable);
    var instance = mock(InstanceManager.class);
    when(instance.scheduler()).thenReturn(scheduler);
    when(server.getInstance(UUID.fromString(instanceId))).thenReturn(Optional.of(instance));
    var scriptId = saveScript("action.print", "trigger.on_interval");
    var logEvents = new CopyOnWriteArrayList<SFLogAppender.SFLogEvent>();
    Consumer<SFLogAppender.SFLogEvent> consumer = entry -> {
      if (UUID.fromString(scriptId).equals(entry.scriptId())) {
        logEvents.add(entry);
      }
    };
    SFLogAppender.INSTANCE.start();
    SFLogAppender.INSTANCE.logConsumers().add(consumer);
    var logReceived = new CountDownLatch(1);
    doAnswer(call -> {
      ScriptEvent event = call.getArgument(0);
      events.add(event);
      if (event.hasScriptLog()) {
        logReceived.countDown();
      }
      return null;
    }).when(observer).onNext(any());
    try {
      var update = UpdateScriptRequest.newBuilder().setInstanceId(instanceId).setScriptId(scriptId)
        .setPaused(false).setUpdateNodes(true)
        .addNodes(ScriptNode.newBuilder().setId(triggerId).setType("trigger.on_interval")
          .putData("intervalMs", Value.newBuilder().setNumberValue(50).build()))
        .addNodes(ScriptNode.newBuilder().setId(actionId).setType("action.print")
          .putData("message", Value.newBuilder().setStringValue("monitoring").build())
          .putData("level", Value.newBuilder().setStringValue(level).build()))
        .build();
      rpcContext.run(() -> service.updateScript(update, mock()));
      var activation = ActivateScriptRequest.newBuilder().setInstanceId(instanceId).setScriptId(scriptId).build();
      rpcContext.run(() -> service.activateScript(activation, observer));

      assertTrue(logReceived.await(5, TimeUnit.SECONDS));
      assertTrue(events.stream().anyMatch(ScriptEvent::hasScriptStarted));
      assertFalse(logEvents.isEmpty());
      assertEquals(UUID.fromString(instanceId), logEvents.getFirst().instanceId());
      assertEquals(level.toUpperCase(java.util.Locale.ROOT), logEvents.getFirst().level());
    } finally {
      rpcContext.run(() -> service.deactivateScript(DeactivateScriptRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(scriptId).build(), mock()));
      SFLogAppender.INSTANCE.logConsumers().remove(consumer);
      scheduler.shutdown();
    }
    verify(observer, times(1)).onCompleted();
    assertEquals(1, events.stream().filter(ScriptEvent::hasScriptCompleted).count());
  }

  @Test
  void stoppingAnInstanceCompletesItsObserversAndKeepsOtherInstancesActive() {
    var otherInstance = UUID.randomUUID().toString();
    var owner = server.dsl().selectFrom(Tables.INSTANCES)
      .where(Tables.INSTANCES.ID.eq(instanceId)).fetchSingle().getOwnerId();
    server.dsl().insertInto(Tables.INSTANCES).set(Tables.INSTANCES.ID, otherInstance)
      .set(Tables.INSTANCES.FRIENDLY_NAME, "other").set(Tables.INSTANCES.OWNER_ID, owner)
      .set(Tables.INSTANCES.ICON, "pickaxe").set(Tables.INSTANCES.SETTINGS, "{}").execute();
    when(server.getInstance(any(UUID.class))).thenReturn(Optional.of(mock(InstanceManager.class)));
    var first = this.<CreateScriptResponse>rpcResponse(o -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setName("first").setPaused(true).build(), o)).getScript().getId();
    var second = this.<CreateScriptResponse>rpcResponse(o -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(otherInstance).setName("second").setPaused(true).build(), o)).getScript().getId();
    ServerCallStreamObserver<ScriptEvent> otherObserver = mock();
    try {
      rpcContext.run(() -> service.activateScript(ActivateScriptRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(first).build(), observer));
      rpcContext.run(() -> service.activateScript(ActivateScriptRequest.newBuilder()
        .setInstanceId(otherInstance).setScriptId(second).build(), otherObserver));
      service.stopAllScripts(UUID.fromString(instanceId));
      service.stopAllScripts(UUID.fromString(instanceId));
      verify(observer, times(1)).onCompleted();
      assertEquals(1, events.stream().filter(ScriptEvent::hasScriptCompleted).count());
      assertFalse(events.getLast().getScriptCompleted().getSuccess());
      verify(otherObserver, never()).onCompleted();
      assertFalse(this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(GetScriptStatusRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(first).build(), o)).getStatus().getIsActive());
      assertTrue(this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(GetScriptStatusRequest.newBuilder()
        .setInstanceId(otherInstance).setScriptId(second).build(), o)).getStatus().getIsActive());
    } finally {
      service.stopAllScripts(UUID.fromString(otherInstance));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void mcpControlsTheSameActivationAsGrpc(boolean delete) throws Exception {
    when(server.getInstance(any(UUID.class))).thenReturn(Optional.of(mock(InstanceManager.class)));
    var auth = mock(AuthSystem.class);
    when(server.authSystem()).thenReturn(auth);
    when(auth.authenticateByHeader("Bearer test", "api"))
      .thenReturn(Optional.of(ServerRPCConstants.USER_CONTEXT_KEY.get(rpcContext)));
    var scriptId = this.<CreateScriptResponse>rpcResponse(o -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setName("MCP lifecycle").setPaused(true).build(), o)).getScript().getId();
    rpcContext.run(() -> service.activateScript(ActivateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).build(), observer));
    var mcp = new MCPService(server, service);
    try (var http = Server.builder().http(0).service("/mcp", mcp.getTransportProvider().httpService()).build()) {
      http.start().get(5, TimeUnit.SECONDS);
      var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + http.activeLocalPort())
        .endpoint("/mcp").jsonMapper(new JacksonMcpJsonMapper(new JsonMapper()))
        .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer test")).build();
      try (var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()) {
        client.initialize();
        Map<String, Object> arguments = Map.of("instance_id", instanceId, "script_id", scriptId);
        var statusResult = client.callTool(new McpSchema.CallToolRequest("get_script_status", arguments));
        assertFalse(Boolean.TRUE.equals(statusResult.isError()));
        var status = GetScriptStatusResponse.newBuilder();
        JsonFormat.parser().merge(((McpSchema.TextContent) statusResult.content().getFirst()).text(), status);
        assertTrue(status.getStatus().getIsActive());
        assertEquals(1, status.getStatus().getActivationCount());

        var result = client.callTool(new McpSchema.CallToolRequest(delete ? "delete_script" : "deactivate_script", arguments));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        awaitCompletion();
        verify(observer, times(1)).onCompleted();
        assertEquals(1, events.stream().filter(ScriptEvent::hasScriptCompleted).count());
        if (delete) {
          assertFalse(server.dsl().fetchExists(Tables.SCRIPTS, Tables.SCRIPTS.ID.eq(scriptId)));
        } else {
          assertTrue(server.dsl().selectFrom(Tables.SCRIPTS).where(Tables.SCRIPTS.ID.eq(scriptId)).fetchSingle().getPaused());
          assertFalse(this.<GetScriptStatusResponse>rpcResponse(o -> service.getScriptStatus(GetScriptStatusRequest.newBuilder()
            .setInstanceId(instanceId).setScriptId(scriptId).build(), o)).getStatus().getIsActive());
        }
      }
    } finally {
      mcp.shutdown();
      service.stopAllScripts(UUID.fromString(instanceId));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectedActivationPreservesThePausedFlag(boolean missingInstance) {
    var scriptId = saveScript(missingInstance ? "action.print" : "unknown.node");
    when(server.getInstance(UUID.fromString(instanceId))).thenReturn(
      missingInstance ? Optional.empty() : Optional.of(mock(InstanceManager.class)));
    var request = ActivateScriptRequest.newBuilder().setInstanceId(instanceId).setScriptId(scriptId).build();

    var failure = assertThrows(StatusRuntimeException.class,
      () -> rpcContext.run(() -> service.activateScript(request, observer)));

    assertEquals(missingInstance ? Status.Code.NOT_FOUND : Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    assertTrue(server.dsl().selectFrom(Tables.SCRIPTS).where(Tables.SCRIPTS.ID.eq(scriptId)).fetchSingle().getPaused());
    verify(observer, never()).onNext(any());
  }

  @Test
  void preservesStructuredMocksOutputsAndTerminalOrdering() throws InterruptedException {
    var scriptId = saveScript("action.print");
    var nested = Value.newBuilder().setStructValue(Struct.newBuilder().putFields("values",
      Value.newBuilder().setListValue(ListValue.newBuilder()
        .addValues(Value.newBuilder().setNullValue(NullValue.NULL_VALUE))
        .addValues(Value.newBuilder().setBoolValue(true))
        .addValues(Value.newBuilder().setNumberValue(2.5))).build())).build();
    run(request(scriptId).putMockInputs("message", nested).build());
    awaitCompletion();
    var completed = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(triggerId)).findFirst().orElseThrow();
    assertEquals(nested, completed.getOutputsOrThrow("message"));
    assertTrue(completed.hasTimestamp());
    assertTrue(events.stream().filter(ScriptEvent::hasNodeStarted).allMatch(event -> event.getNodeStarted().hasTimestamp()));
    var last = events.getLast().getScriptCompleted();
    assertTrue(last.getSuccess());
    assertEquals(scriptId, last.getScriptId());
    assertTrue(last.hasTimestamp());
    assertTrue(events.get(events.size() - 2).hasExecutionStats());
    verify(observer, times(1)).onCompleted();
    verify(observer, never()).onError(any());
  }

  @Test
  void rejectsMissingAndNonTriggerNodes() {
    var scriptId = saveScript("action.print");
    for (var id : List.of(UUID.randomUUID().toString(), actionId)) {
      var failure = assertThrows(StatusRuntimeException.class, () -> run(request(scriptId).setTriggerNodeId(id).build()));
      assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    }
    assertTrue(events.isEmpty());
  }

  @Test
  void mocksOverrideGeneratedTriggerOutputs() throws InterruptedException {
    var scriptId = saveScript("action.print", "trigger.on_script_init");
    run(request(scriptId).putMockInputs("timestamp", Value.newBuilder().setNumberValue(42).build()).build());
    awaitCompletion();
    var trigger = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(triggerId)).findFirst().orElseThrow();
    assertEquals(42, trigger.getOutputsOrThrow("timestamp").getNumberValue());
  }

  @Test
  void honorsSavedQuotasAndKeepsTheScriptPaused() throws InterruptedException {
    var scriptId = saveScript("action.print");
    rpcContext.run(() -> service.updateScript(UpdateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(scriptId).setUpdateQuotas(true)
      .setQuotas(ScriptQuotas.newBuilder().setMaxExecutionCount(0)).build(), mock()));
    run(request(scriptId).build());
    awaitCompletion();
    assertTrue(events.stream().anyMatch(ScriptEvent::hasNodeError));
    assertFalse(events.getLast().getScriptCompleted().getSuccess());
    assertEquals(0, events.stream().filter(ScriptEvent::hasExecutionStats).findFirst().orElseThrow().getExecutionStats().getMaxCount());
    assertTrue(server.dsl().selectFrom(Tables.SCRIPTS).where(Tables.SCRIPTS.ID.eq(scriptId)).fetchSingle().getPaused());
  }

  @Test
  void rejectsInvalidGraphs() {
    var scriptId = saveScript("unknown.node");
    var failure = assertThrows(StatusRuntimeException.class, () -> run(request(scriptId).build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
  }

  private String registerAction(Supplier<Mono<Map<String, NodeValue>>> execution) {
    var type = "test.dry_run_" + UUID.randomUUID();
    NodeRegistry.register(NodeMetadata.builder().type(type).displayName("test").category(CategoryRegistry.ACTIONS)
      .addInputs(PortDefinition.execIn()).addOutputs(PortDefinition.execOut()).build(),
      () -> new AbstractScriptNode() {
        @Override
        public Mono<Map<String, NodeValue>> executeReactive(NodeRuntime runtime, Map<String, NodeValue> inputs) {
          return execution.get();
        }
      });
    return type;
  }

  @Test
  void nodeErrorsProduceFailedCompletionWithoutDoubleTermination() throws InterruptedException {
    var type = registerAction(() -> Mono.error(new IllegalStateException()));
    run(request(saveScript(type)).build());
    awaitCompletion();
    assertTrue(events.stream().anyMatch(ScriptEvent::hasNodeError));
    assertFalse(events.getLast().getScriptCompleted().getSuccess());
    verify(observer, times(1)).onCompleted();
    verify(observer, never()).onError(any());
  }

  @Test
  void disconnectDisposesExecutionAndDoesNotCompleteTheCancelledStream() throws InterruptedException {
    var started = new CountDownLatch(1);
    var cancelled = new CountDownLatch(1);
    var type = registerAction(() -> Mono.defer(() -> {
      started.countDown();
      return Mono.<Map<String, NodeValue>>never().doOnCancel(cancelled::countDown);
    }));
    run(request(saveScript(type)).build());
    assertTrue(started.await(5, TimeUnit.SECONDS));
    cancel.get().run();
    assertTrue(cancelled.await(5, TimeUnit.SECONDS));
    verify(observer, never()).onCompleted();
    verify(observer, never()).onError(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void instanceStopDisposesAllConcurrentPreviewsOfTheSameScript(boolean disconnectDuringCompletion) throws InterruptedException {
    var started = new CountDownLatch(2);
    var disposed = new CountDownLatch(2);
    var type = registerAction(() -> Mono.defer(() -> {
      started.countDown();
      return Mono.<Map<String, NodeValue>>never().doOnCancel(disposed::countDown);
    }));
    var scriptId = saveScript(type);
    run(request(scriptId).build());
    var firstCancel = cancel.get();
    var secondCancel = new AtomicReference<Runnable>();
    ServerCallStreamObserver<ScriptEvent> second = mock();
    doAnswer(call -> { secondCancel.set(call.getArgument(0)); return null; }).when(second).setOnCancelHandler(any());
    try {
      rpcContext.run(() -> service.dryRunScript(request(scriptId).build(), second));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      if (disconnectDuringCompletion) doThrow(Status.CANCELLED.asRuntimeException()).when(observer).onCompleted();
      service.stopAllScripts(UUID.fromString(instanceId));
      assertTrue(disposed.await(5, TimeUnit.SECONDS));
      verify(observer, times(1)).onCompleted();
      verify(second, times(1)).onCompleted();
      assertEquals(1, events.stream().filter(ScriptEvent::hasScriptCompleted).count());
      assertFalse(events.getLast().getScriptCompleted().getSuccess());
      verify(second).onNext(argThat(event -> event.hasScriptCompleted() && !event.getScriptCompleted().getSuccess()));
    } finally {
      firstCancel.run();
      if (secondCancel.get() != null) secondCancel.get().run();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deletingAScriptCancelsItsPreviewsAndKeepsASiblingPreviewAlive(boolean rejectDelete) throws InterruptedException {
    var started = new CountDownLatch(3);
    var disposed = new CountDownLatch(3);
    var type = registerAction(() -> Mono.defer(() -> {
      started.countDown();
      return Mono.<Map<String, NodeValue>>never().doOnCancel(disposed::countDown);
    }));
    var target = saveScript(type);
    var sibling = saveScript(type);
    var observers = List.<ServerCallStreamObserver<ScriptEvent>>of(observer, mock(), mock());
    var scriptIds = List.of(target, target, sibling);
    var cancellations = new CopyOnWriteArrayList<Runnable>();
    try {
      for (var index = 0; index < observers.size(); index++) {
        var monitor = observers.get(index);
        var scriptId = scriptIds.get(index);
        doAnswer(call -> { cancellations.add(call.getArgument(0)); return null; }).when(monitor).setOnCancelHandler(any());
        rpcContext.run(() -> service.dryRunScript(request(scriptId).build(), monitor));
      }
      assertTrue(started.await(5, TimeUnit.SECONDS));
      if (rejectDelete) {
        server.dsl().execute("CREATE TRIGGER reject_script_delete BEFORE DELETE ON scripts BEGIN SELECT RAISE(ABORT, 'Delete rejected'); END");
        var failure = assertThrows(StatusRuntimeException.class, () -> rpcContext.run(() -> service.deleteScript(DeleteScriptRequest.newBuilder()
          .setInstanceId(instanceId).setScriptId(target).build(), mock())));
        assertEquals(Status.Code.INTERNAL, failure.getStatus().getCode());
        assertEquals(3, disposed.getCount());
        for (var monitor : observers) verify(monitor, never()).onCompleted();
        assertTrue(server.dsl().fetchExists(Tables.SCRIPTS, Tables.SCRIPTS.ID.eq(target)));
      } else {
        rpcContext.run(() -> service.deleteScript(DeleteScriptRequest.newBuilder()
          .setInstanceId(instanceId).setScriptId(target).build(), mock()));
        assertEquals(1, disposed.getCount());
        verify(observers.get(0), times(1)).onCompleted();
        verify(observers.get(1), times(1)).onCompleted();
        verify(observers.get(2), never()).onCompleted();
        assertFalse(server.dsl().fetchExists(Tables.SCRIPTS, Tables.SCRIPTS.ID.eq(target)));
      }
      assertTrue(server.dsl().fetchExists(Tables.SCRIPTS, Tables.SCRIPTS.ID.eq(sibling)));
    } finally {
      cancellations.forEach(Runnable::run);
    }
    assertEquals(0, disposed.getCount());
  }

  @Test
  void instanceStopDuringPreviewRegistrationDoesNotStartExecution() {
    var started = new CountDownLatch(1);
    var type = registerAction(() -> { started.countDown(); return Mono.empty(); });
    var scriptId = saveScript(type);
    doAnswer(_ -> { service.stopAllScripts(UUID.fromString(instanceId)); return null; })
      .when(observer).setOnCancelHandler(any());
    try {
      run(request(scriptId).build());
      assertEquals(1, started.getCount());
      verify(observer, times(1)).onCompleted();
      assertEquals(1, events.stream().filter(ScriptEvent::hasScriptCompleted).count());
      assertFalse(events.getLast().getScriptCompleted().getSuccess());
    } finally {
      if (cancel.get() != null) cancel.get().run();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void previewCannotStartFromASnapshotAfterItsOwnerDisappears(boolean instanceDeleted) {
    var started = new CountDownLatch(1);
    var type = registerAction(() -> { started.countDown(); return Mono.never(); });
    var scriptId = saveScript(type);
    var dsl = server.dsl();
    var intercepted = spy(dsl);
    doAnswer(call -> {
      var snapshot = call.callRealMethod();
      if (instanceDeleted) dsl.deleteFrom(Tables.INSTANCES).where(Tables.INSTANCES.ID.eq(instanceId)).execute();
      else dsl.deleteFrom(Tables.SCRIPTS).where(Tables.SCRIPTS.ID.eq(scriptId)).execute();
      return snapshot;
    }).when(intercepted).transactionResult(any(TransactionalCallable.class));
    when(server.dsl()).thenReturn(intercepted);
    try {
      run(request(scriptId).build());
      assertEquals(1, started.getCount());
      verify(observer, times(1)).onCompleted();
      assertFalse(events.getLast().getScriptCompleted().getSuccess());
    } finally {
      if (cancel.get() != null) cancel.get().run();
    }
  }

  @Test
  void cancellationDuringHandlerRegistrationDoesNotStartExecution() {
    var started = new CountDownLatch(1);
    var type = registerAction(() -> { started.countDown(); return Mono.empty(); });
    var scriptId = saveScript(type);
    doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(observer).setOnCancelHandler(any());
    run(request(scriptId).build());
    assertEquals(1, started.getCount());
    assertTrue(events.isEmpty());
    verify(observer, never()).onCompleted();
  }

  @Test
  void serializesVectorAndCollectionOutputs() throws InterruptedException {
    var type = registerAction(() -> Mono.just(Map.of(
      "vector", NodeValue.ofVector3(new Vec3(1, 2, 3)),
      "collection", NodeValue.ofSet(List.of(NodeValue.ofNumber(4), NodeValue.ofNumber(4), NodeValue.ofNumber(5)))
    )));
    run(request(saveScript(type)).build());
    awaitCompletion();
    var outputs = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(actionId)).findFirst().orElseThrow().getOutputsMap();
    assertEquals(3, outputs.get("vector").getStructValue().getFieldsCount());
    assertEquals(2, outputs.get("vector").getStructValue().getFieldsOrThrow("y").getNumberValue());
    assertEquals(List.of(4.0, 5.0), outputs.get("collection").getListValue().getValuesList().stream().map(Value::getNumberValue).toList());
  }

  private record LayoutGroup(ScriptNode node, ScriptNode input, ScriptNode output) {}

  private CreateScriptRequest.Builder groupRequest() {
    return CreateScriptRequest.newBuilder().setInstanceId(instanceId).setName("group routing").setPaused(true)
      .addNodes(ScriptNode.newBuilder().setId(triggerId).setType("trigger.on_chat"));
  }

  private static ScriptNode.Builder layoutNode(String type) {
    return ScriptNode.newBuilder().setId(UUID.randomUUID().toString()).setType(type);
  }

  private static ScriptNode.Builder insideGroup(String type, LayoutGroup group) {
    return layoutNode(type).putData("parentGroupId", Value.newBuilder().setStringValue(group.node().getId()).build());
  }

  private static LayoutGroup addLayoutGroup(CreateScriptRequest.Builder request, Optional<LayoutGroup> parent) {
    var node = layoutNode("layout.group");
    parent.ifPresent(group -> node.putData("parentGroupId", Value.newBuilder().setStringValue(group.node().getId()).build()));
    var root = node.build();
    var parentId = Value.newBuilder().setStringValue(root.getId()).build();
    var input = layoutNode("layout.group_input").putData("parentGroupId", parentId).build();
    var output = layoutNode("layout.group_output").putData("parentGroupId", parentId).build();
    request.addNodes(root).addNodes(input).addNodes(output);
    return new LayoutGroup(root, input, output);
  }

  private static ScriptEdge.Builder layoutEdge(ScriptNode source, String sourceHandle, ScriptNode target, String targetHandle, EdgeType type) {
    return ScriptEdge.newBuilder().setId(UUID.randomUUID().toString()).setSource(source.getId()).setSourceHandle(sourceHandle)
      .setTarget(target.getId()).setTargetHandle(targetHandle).setEdgeType(type);
  }

  private void runGroup(CreateScriptRequest.Builder request) throws InterruptedException {
    var created = this.<CreateScriptResponse>rpcResponse(observer -> service.createScript(request.build(), observer));
    run(request(created.getScript().getId()).build());
    awaitCompletion();
    assertTrue(events.getLast().getScriptCompleted().getSuccess());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupInputsReachEveryExecutionTarget(boolean nested) throws InterruptedException {
    var request = groupRequest();
    var trigger = request.getNodes(0);
    var outer = addLayoutGroup(request, Optional.empty());
    var inner = nested ? addLayoutGroup(request, Optional.of(outer)) : outer;
    if (nested) request.addEdges(layoutEdge(outer.input(), "input", inner.node(), "input", EdgeType.EDGE_TYPE_EXECUTION));
    var first = insideGroup("action.print", inner).putData("message", Value.newBuilder().setNumberValue(1).build()).build();
    var second = insideGroup("action.print", inner).putData("message", Value.newBuilder().setNumberValue(2).build()).build();
    request.addNodes(first).addNodes(second)
      .addEdges(layoutEdge(trigger, "out", outer.node(), "input", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(inner.input(), "input", first, "in", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(inner.input(), "input", second, "in", EdgeType.EDGE_TYPE_EXECUTION));
    runGroup(request);
    var executed = events.stream().filter(ScriptEvent::hasNodeCompleted).map(event -> event.getNodeCompleted().getNodeId()).toList();
    assertTrue(executed.contains(first.getId()));
    assertTrue(executed.contains(second.getId()));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupPassThroughPreservesExecutionAndData(boolean data) throws InterruptedException {
    var request = groupRequest();
    var trigger = request.getNodes(0);
    var group = addLayoutGroup(request, Optional.empty());
    var action = layoutNode("action.print").putData("message", Value.newBuilder().setNumberValue(17).build()).build();
    request.addNodes(action);
    var type = data ? EdgeType.EDGE_TYPE_DATA : EdgeType.EDGE_TYPE_EXECUTION;
    request.addEdges(layoutEdge(group.input(), "input", group.output(), "output", type));
    if (data) {
      var constant = layoutNode("constant.number").putData("value", Value.newBuilder().setNumberValue(29).build()).build();
      request.addNodes(constant)
        .addEdges(layoutEdge(trigger, "out", action, "in", EdgeType.EDGE_TYPE_EXECUTION))
        .addEdges(layoutEdge(constant, "value", group.node(), "input", type))
        .addEdges(layoutEdge(group.node(), "output", action, "message", type));
    } else {
      request.addEdges(layoutEdge(trigger, "out", group.node(), "input", type))
        .addEdges(layoutEdge(group.node(), "output", action, "in", type));
    }
    runGroup(request);
    var values = events.stream().filter(ScriptEvent::hasScriptLog)
      .map(event -> Double.parseDouble(event.getScriptLog().getMessage())).toList();
    assertEquals(List.of(data ? 29.0 : 17.0), values);
  }

  @Test
  void groupOutputsPreserveEveryIncomingExecutionBranch() throws InterruptedException {
    var request = groupRequest();
    var trigger = request.getNodes(0);
    var group = addLayoutGroup(request, Optional.empty());
    var first = insideGroup("action.print", group).putData("message", Value.newBuilder().setNumberValue(1).build()).build();
    var second = insideGroup("action.print", group).putData("message", Value.newBuilder().setNumberValue(2).build()).build();
    var after = layoutNode("action.print").putData("message", Value.newBuilder().setNumberValue(3).build()).build();
    request.addNodes(first).addNodes(second).addNodes(after)
      .addEdges(layoutEdge(trigger, "out", group.node(), "first", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(trigger, "out", group.node(), "second", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(group.input(), "first", first, "in", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(group.input(), "second", second, "in", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(first, "out", group.output(), "output", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(second, "out", group.output(), "output", EdgeType.EDGE_TYPE_EXECUTION))
      .addEdges(layoutEdge(group.node(), "output", after, "in", EdgeType.EDGE_TYPE_EXECUTION));
    runGroup(request);
    assertEquals(2, events.stream().filter(ScriptEvent::hasNodeCompleted)
      .filter(event -> event.getNodeCompleted().getNodeId().equals(after.getId())).count());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupInputsReachEveryDataConsumer(boolean nested) throws InterruptedException {
    var request = groupRequest();
    var trigger = request.getNodes(0);
    var outer = addLayoutGroup(request, Optional.empty());
    var inner = nested ? addLayoutGroup(request, Optional.of(outer)) : outer;
    if (nested) {
      request.addEdges(layoutEdge(outer.input(), "input", inner.node(), "input", EdgeType.EDGE_TYPE_DATA));
      for (var port : List.of("first", "second")) {
        request.addEdges(layoutEdge(inner.node(), port, outer.output(), port, EdgeType.EDGE_TYPE_DATA));
      }
    }
    var constant = layoutNode("constant.number").putData("value", Value.newBuilder().setNumberValue(17).build()).build();
    var first = insideGroup("math.add", inner).putData("b", Value.newBuilder().setNumberValue(2).build()).build();
    var second = insideGroup("math.add", inner).putData("b", Value.newBuilder().setNumberValue(3).build()).build();
    request.addNodes(constant).addNodes(first).addNodes(second)
      .addEdges(layoutEdge(constant, "value", outer.node(), "input", EdgeType.EDGE_TYPE_DATA))
      .addEdges(layoutEdge(inner.input(), "input", first, "a", EdgeType.EDGE_TYPE_DATA))
      .addEdges(layoutEdge(inner.input(), "input", second, "a", EdgeType.EDGE_TYPE_DATA))
      .addEdges(layoutEdge(first, "result", inner.output(), "first", EdgeType.EDGE_TYPE_DATA))
      .addEdges(layoutEdge(second, "result", inner.output(), "second", EdgeType.EDGE_TYPE_DATA));
    for (var port : List.of("first", "second")) {
      var print = layoutNode("action.print").build();
      request.addNodes(print).addEdges(layoutEdge(trigger, "out", print, "in", EdgeType.EDGE_TYPE_EXECUTION))
        .addEdges(layoutEdge(outer.node(), port, print, "message", EdgeType.EDGE_TYPE_DATA));
    }
    runGroup(request);
    var sums = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(first.getId()) || event.getNodeId().equals(second.getId()))
      .map(event -> event.getOutputsOrThrow("result").getNumberValue()).sorted().toList();
    assertEquals(List.of(19.0, 20.0), sums);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void groupRewiringKeepsTheConsumersMultiInputOrder(boolean nested) throws InterruptedException {
    var request = groupRequest();
    var trigger = request.getNodes(0);
    var outer = addLayoutGroup(request, Optional.empty());
    var inner = nested ? addLayoutGroup(request, Optional.of(outer)) : outer;
    var list = insideGroup("list.create", inner).build();
    var print = layoutNode("action.print").build();
    request.addNodes(list).addNodes(print).addEdges(layoutEdge(trigger, "out", print, "in", EdgeType.EDGE_TYPE_EXECUTION));
    for (var value : List.of(1, 2)) {
      var port = String.valueOf(value);
      var constant = layoutNode("constant.number").putData("value", Value.newBuilder().setNumberValue(value).build()).build();
      request.addNodes(constant).addEdges(layoutEdge(constant, "value", outer.node(), port, EdgeType.EDGE_TYPE_DATA).setOrder(value - 1));
      if (nested) request.addEdges(layoutEdge(outer.input(), port, inner.node(), port, EdgeType.EDGE_TYPE_DATA).setOrder(value - 1));
      request.addEdges(layoutEdge(inner.input(), port, list, "items", EdgeType.EDGE_TYPE_DATA).setOrder(2 - value));
    }
    request.addEdges(layoutEdge(list, "list", inner.output(), "output", EdgeType.EDGE_TYPE_DATA));
    if (nested) request.addEdges(layoutEdge(inner.node(), "output", outer.output(), "output", EdgeType.EDGE_TYPE_DATA));
    request.addEdges(layoutEdge(outer.node(), "output", print, "message", EdgeType.EDGE_TYPE_DATA));
    runGroup(request);
    var output = events.stream().filter(ScriptEvent::hasNodeCompleted).map(ScriptEvent::getNodeCompleted)
      .filter(event -> event.getNodeId().equals(list.getId())).findFirst().orElseThrow();
    assertEquals(List.of(2.0, 1.0), output.getOutputsOrThrow("list").getListValue().getValuesList().stream()
      .map(Value::getNumberValue).toList());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void cyclicGroupParentsReturnDiagnosticsAndRejectExecution(int size) {
    var nodes = new ArrayList<ScriptNode>();
    for (var index = 0; index < size; index++) {
      var parent = size == 3 && index == size - 1 ? 1 : (index + 1) % size;
      nodes.add(ScriptNode.newBuilder().setId("group-" + index).setType("layout.group")
        .putData("parentGroupId", Value.newBuilder().setStringValue("group-" + parent).build()).build());
    }
    ValidateScriptResponse validation = rpcResponse(observer -> service.validateScript(ValidateScriptRequest.newBuilder()
      .setInstanceId(instanceId).addAllNodes(nodes).build(), observer));
    assertEquals(1, validation.getDiagnosticsCount());
    var diagnostic = validation.getDiagnostics(0);
    assertEquals(DiagnosticSeverity.DIAGNOSTIC_ERROR, diagnostic.getSeverity());
    assertTrue(nodes.stream().anyMatch(node -> node.getId().equals(diagnostic.getNodeId())));

    CreateScriptResponse created = rpcResponse(observer -> service.createScript(CreateScriptRequest.newBuilder()
      .setInstanceId(instanceId).setName("cyclic groups").setPaused(true).addAllNodes(nodes).build(), observer));
    var failure = assertThrows(StatusRuntimeException.class,
      () -> run(request(created.getScript().getId()).build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    assertTrue(events.isEmpty());
    GetScriptResponse saved = rpcResponse(observer -> service.getScript(GetScriptRequest.newBuilder()
      .setInstanceId(instanceId).setScriptId(created.getScript().getId()).build(), observer));
    assertTrue(saved.getScript().getPaused());
  }


  @Test
  void scriptLogSubscriptionFiltersScopeAndPreservesMetadata() throws Exception {
    try (var logs = new ScriptLogFixture()) {
      var viewer = logs.subscribe();
      logs.holder.broadcastLogMessage(logs.event(UUID.randomUUID(), logs.id, "INFO"));
      logs.holder.broadcastLogMessage(logs.event(logs.instance, UUID.randomUUID(), "INFO"));
      logs.holder.sendPersonalMessage(logs.owner, "personal");
      for (var level : List.of("DEBUG", "INFO", "WARN", "ERROR"))
        logs.holder.broadcastLogMessage(logs.event(logs.instance, logs.id, level));
      assertEquals(List.of(LogLevel.LOG_LEVEL_DEBUG, LogLevel.LOG_LEVEL_INFO,
        LogLevel.LOG_LEVEL_WARN, LogLevel.LOG_LEVEL_ERROR), viewer.messages.stream().map(ScriptLogEntry::getLevel).toList());
      for (var entry : viewer.messages) {
        assertEquals(logs.id.toString(), entry.getScriptId());
        assertEquals("script entry", entry.getMessage());
        assertEquals(1234, entry.getTimestamp().getSeconds());
        assertEquals(567_000_000, entry.getTimestamp().getNanos());
      }
    }
  }

  @Test
  void scriptLogSubscriptionRechecksQueuedPermissions() throws Exception {
    try (var logs = new ScriptLogFixture()) {
      var viewer = logs.subscribe();
      viewer.ready.set(false);
      logs.holder.broadcastLogMessage(logs.event(logs.instance, logs.id, "INFO"));
      when(logs.user.hasPermission(any(PermissionContext.class))).thenReturn(false);
      viewer.ready.set(true);
      viewer.onReady.get().run();
      assertTrue(viewer.messages.isEmpty());
      when(logs.user.hasPermission(any(PermissionContext.class))).thenReturn(true);
      logs.holder.broadcastLogMessage(logs.event(logs.instance, logs.id, "WARN"));
      assertEquals(1, viewer.messages.size());
    }
  }

  @Test
  void cancellingScriptLogsClearsPendingMessagesWithoutDisconnectingOtherViewers() throws Exception {
    try (var logs = new ScriptLogFixture()) {
      var cancelled = logs.subscribe();
      var healthy = logs.subscribe();
      cancelled.ready.set(false);
      logs.holder.broadcastLogMessage(logs.event(logs.instance, logs.id, "INFO"));
      cancelled.onCancel.get().run();
      cancelled.ready.set(true);
      cancelled.onReady.get().run();
      logs.holder.broadcastLogMessage(logs.event(logs.instance, logs.id, "WARN"));
      assertTrue(cancelled.messages.isEmpty());
      assertEquals(2, healthy.messages.size());
      logs.holder.disconnect(logs.owner);
      verify(healthy.observer).onCompleted();
      verify(cancelled.observer, never()).onCompleted();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scriptLogSubscriptionRejectsMissingOrForeignScripts(boolean foreignInstance) throws Exception {
    try (var logs = new ScriptLogFixture()) {
      var request = SubscribeScriptLogsRequest.newBuilder()
        .setInstanceId(foreignInstance ? UUID.randomUUID().toString() : instanceId)
        .setScriptId(foreignInstance ? logs.id.toString() : UUID.randomUUID().toString()).build();
      var failure = assertThrows(StatusRuntimeException.class, () ->
        rpcContext.run(() -> service.subscribeScriptLogs(request, mock())));
      assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
    }
  }

  private final class ScriptLogFixture implements AutoCloseable {
    final UUID instance = UUID.fromString(instanceId);
    final UUID owner = UUID.randomUUID();
    final SoulFireUser user = rpcContext.call(ServerRPCConstants.USER_CONTEXT_KEY::get);
    final UUID id;
    final LogServiceImpl.StateHolder holder;
    final Consumer<SFLogAppender.SFLogEvent> consumer;

    ScriptLogFixture() throws Exception {
      var before = new HashSet<>(SFLogAppender.INSTANCE.logConsumers());
      holder = new LogServiceImpl.StateHolder();
      consumer = SFLogAppender.INSTANCE.logConsumers().stream().filter(c -> !before.contains(c)).findFirst().orElseThrow();
      when(server.logStateHolder()).thenReturn(holder);
      var auth = mock(AuthSystem.class);
      var issued = Instant.now();
      when(server.authSystem()).thenReturn(auth);
      when(user.getUniqueId()).thenReturn(owner);
      when(user.getIssuedAt()).thenReturn(issued);
      when(user.hasPermission(any(PermissionContext.class))).thenReturn(true);
      when(auth.authenticateBySubject(owner, issued)).thenReturn(Optional.of(user));
      id = UUID.fromString(saveScript("action.print"));
    }

    ScriptLogViewer subscribe() {
      var viewer = new ScriptLogViewer();
      rpcContext.run(() -> service.subscribeScriptLogs(SubscribeScriptLogsRequest.newBuilder()
        .setInstanceId(instanceId).setScriptId(id.toString()).build(), viewer.observer));
      return viewer;
    }

    SFLogAppender.SFLogEvent event(UUID eventInstance, UUID eventScript, String level) {
      return new SFLogAppender.SFLogEvent(UUID.randomUUID().toString(), "script entry", 1_234_567,
        level, "script", eventInstance, null, eventScript, null, null);
    }

    @Override public void close() { SFLogAppender.INSTANCE.logConsumers().remove(consumer); }
  }

  private static final class ScriptLogViewer {
    final ServerCallStreamObserver<ScriptLogEntry> observer = mock();
    final List<ScriptLogEntry> messages = new ArrayList<>();
    final AtomicBoolean ready = new AtomicBoolean(true);
    final AtomicReference<Runnable> onReady = new AtomicReference<>(() -> {});
    final AtomicReference<Runnable> onCancel = new AtomicReference<>();

    ScriptLogViewer() {
      when(observer.isReady()).thenAnswer(_ -> ready.get());
      doAnswer(call -> { messages.add(call.getArgument(0)); return null; }).when(observer).onNext(any());
      doAnswer(call -> { onReady.set(call.getArgument(0)); return null; }).when(observer).setOnReadyHandler(any());
      doAnswer(call -> { onCancel.set(call.getArgument(0)); return null; }).when(observer).setOnCancelHandler(any());
    }
  }
}
