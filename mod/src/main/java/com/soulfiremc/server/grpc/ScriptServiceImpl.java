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

import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.*;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.Timestamps;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.grpc.generated.PortType;
import com.soulfiremc.grpc.generated.ScriptNode;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.database.generated.Tables;
import com.soulfiremc.server.database.generated.tables.records.ScriptsRecord;
import com.soulfiremc.server.script.*;
import com.soulfiremc.server.script.PortDefinition;
import com.soulfiremc.server.script.nodes.NodeRegistry;
import com.soulfiremc.server.user.PermissionContext;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import org.jooq.impl.DSL;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * gRPC service implementation for visual script management.
 * Handles CRUD operations for scripts and script execution lifecycle.
 */
@Slf4j
@RequiredArgsConstructor
public final class ScriptServiceImpl extends ScriptServiceGrpc.ScriptServiceImplBase {
  private static final Type NODE_LIST_TYPE = new TypeToken<List<ScriptNodeData>>() {}.getType();
  private static final Type EDGE_LIST_TYPE = new TypeToken<List<ScriptEdgeData>>() {}.getType();
  private static final int MAX_NAME_LENGTH = 128;
  private static final int MAX_DESCRIPTION_LENGTH = 1024;

  private final SoulFireServer soulFireServer;
  private final ScriptTriggerService triggerService = new ScriptTriggerService();

  // Track active scripts: scriptId -> activation state
  private final Map<UUID, ScriptActivationState> activeScripts = new ConcurrentHashMap<>();

  // Internal data classes for JSON serialization
  private record ScriptNodeData(
    String id,
    String type,
    PositionData position,
    Map<String, JsonElement> data
  ) {}

  private record PositionData(double x, double y) {}

  private record ScriptEdgeData(
    String id,
    String source,
    String sourceHandle,
    String target,
    String targetHandle,
    String edgeType,
    Integer order
  ) {}

  private record FlattenedGraph(List<ScriptNodeData> nodes, List<ScriptEdgeData> edges) {}

  private record ScriptActivationState(
    UUID scriptId,
    UUID instanceId,
    boolean active,
    String activeNodeId,
    long activationCount,
    ReactiveScriptContext context,
    AtomicReference<ServerCallStreamObserver<ScriptEvent>> observerRef
  ) {}

  // ============================================================================
  // Internal Script Lifecycle Methods
  // ============================================================================

  /// Starts a script without modifying the paused flag in the database.
  /// Used for automatic script starting on create/update/server startup.
  private void startScriptInternal(ScriptsRecord record) {
    var scriptId = UUID.fromString(record.getId());
    var instanceId = UUID.fromString(record.getInstanceId());

    try {
      // Build the script graph from the record
      var graph = buildScriptGraph(record);

      // Get the instance manager
      var instanceManager = soulFireServer.getInstance(instanceId).orElse(null);
      if (instanceManager == null) {
        log.warn("Instance {} not found, cannot start script {}", instanceId, scriptId);
        return;
      }

      // Create internal event listener (no stream, just logging)
      var eventListener = createInternalEventListener(scriptId);

      // Parse quotas from the database record
      var quotas = ScriptQuotasConfig.fromProto(jsonToQuotas(record.getQuotasJson()));

      // Create reactive context for trigger execution
      var context = new ReactiveScriptContext(instanceManager, eventListener, quotas);

      // Atomically check-and-set to avoid race condition
      var activationState = new ScriptActivationState(
        scriptId,
        instanceId,
        true,
        null,
        1,
        context,
        null
      );
      var existing = activeScripts.putIfAbsent(scriptId, activationState);
      if (existing != null) {
        log.debug("Script {} is already running, skipping start", scriptId);
        return;
      }

      // Register triggers for event-driven execution
      var engine = new ReactiveScriptEngine();
      triggerService.registerTriggers(scriptId, graph, context, engine);

      log.info("Started script {} with {} triggers, {} data edges", scriptId,
        graph.findTriggerNodes().size(), graph.dataEdges().size());
    } catch (ScriptGraphValidationException e) {
      log.error("Script '{}' ({}) has invalid graph: {}", record.getName(), scriptId, e.errors());
    } catch (Exception e) {
      log.error("Failed to start script {}", scriptId, e);
    }
  }

  /// Stops a script without modifying the paused flag in the database.
  /// Used for stopping scripts before restart or when pausing.
  private void stopScriptInternal(UUID scriptId) {
    var activationState = activeScripts.remove(scriptId);
    if (activationState == null) {
      return;
    }

    // Unregister triggers
    triggerService.unregisterTriggers(scriptId);

    // Cancel the context if it exists
    if (activationState.context() != null) {
      activationState.context().cancel();
    }

    // If there's a client observer, send completion event
    var observerRef = activationState.observerRef();
    if (observerRef != null) {
      var observer = observerRef.get();
      if (observer != null && !observer.isCancelled()) {
        try {
          observer.onNext(ScriptEvent.newBuilder()
            .setScriptCompleted(ScriptCompleted.newBuilder()
              .setScriptId(scriptId.toString())
              .setSuccess(true)
              .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
              .build())
            .build());
          observer.onCompleted();
        } catch (Exception e) {
          log.debug("Error completing script stream on stop", e);
        }
      }
    }

    log.info("Stopped script {}", scriptId);
  }

  /// Restarts a script by stopping it and starting it again.
  /// Used when a script is updated to apply new configuration.
  private void restartScriptInternal(ScriptsRecord record) {
    var scriptId = UUID.fromString(record.getId());
    log.info("Restarting script {}", scriptId);
    stopScriptInternal(scriptId);
    startScriptInternal(record);
  }

  /// Starts all non-paused scripts for a given instance.
  /// Called on server startup.
  public void startAllNonPausedScripts(UUID instanceId) {
    soulFireServer.dsl().transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var scripts = ctx.selectFrom(Tables.SCRIPTS)
        .where(Tables.SCRIPTS.INSTANCE_ID.eq(instanceId.toString()))
        .and(Tables.SCRIPTS.PAUSED.eq(false))
        .fetch();

      for (var script : scripts) {
        startScriptInternal(script);
      }

      log.info("Started {} non-paused scripts for instance {}", scripts.size(), instanceId);
    });
  }

  /// Starts all non-paused scripts across all instances.
  /// Called on server startup.
  public void startAllNonPausedScripts() {
    soulFireServer.dsl().transaction(cfg -> {
      var ctx = DSL.using(cfg);
      var scripts = ctx.selectFrom(Tables.SCRIPTS)
        .where(Tables.SCRIPTS.PAUSED.eq(false))
        .fetch();

      for (var script : scripts) {
        startScriptInternal(script);
      }

      log.info("Started {} non-paused scripts on server startup", scripts.size());
    });
  }

  /// Creates an internal event listener that just logs events (no streaming to client).
  private ScriptEventListener createInternalEventListener(UUID scriptId) {
    return new ScriptEventListener() {
      @Override
      public void onNodeStarted(String nodeId) {
        log.debug("[Script {}] Node started: {}", scriptId, nodeId);
      }

      @Override
      public void onNodeCompleted(String nodeId, Map<String, NodeValue> outputs) {
        log.debug("[Script {}] Node completed: {}", scriptId, nodeId);
      }

      @Override
      public void onNodeError(String nodeId, String error) {
        log.warn("[Script {}] Node error {}: {}", scriptId, nodeId, error);
      }

      @Override
      public void onScriptCompleted(boolean success) {
        activeScripts.remove(scriptId);
        log.debug("[Script {}] Execution chain completed: {}", scriptId, success ? "success" : "failure");
      }

      @Override
      public void onScriptCancelled() {
        activeScripts.remove(scriptId);
        log.debug("[Script {}] Execution cancelled", scriptId);
      }

      @Override
      public void onLog(String level, String message) {
        log.info("[Script {}] [{}] {}", scriptId, level.toUpperCase(), message);
      }
    };
  }

  // ============================================================================
  // gRPC Service Methods
  // ============================================================================

  @Override
  public void createScript(CreateScriptRequest request, StreamObserver<CreateScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    if (request.getName().isBlank()) {
      throw Status.INVALID_ARGUMENT.withDescription("Script name must not be empty").asRuntimeException();
    }
    if (request.getName().length() > MAX_NAME_LENGTH) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Script name must not exceed " + MAX_NAME_LENGTH + " characters").asRuntimeException();
    }
    if (request.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Script description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters").asRuntimeException();
    }

    try {
      var scriptRecord = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var instanceRecord = ctx.selectFrom(Tables.INSTANCES)
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .fetchOne();
        if (instanceRecord == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        var newId = UUID.randomUUID().toString();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        ctx.insertInto(Tables.SCRIPTS)
          .set(Tables.SCRIPTS.ID, newId)
          .set(Tables.SCRIPTS.NAME, request.getName())
          .set(Tables.SCRIPTS.DESCRIPTION, request.getDescription())
          .set(Tables.SCRIPTS.INSTANCE_ID, instanceId.toString())
          .set(Tables.SCRIPTS.NODES_JSON, nodesToJson(request.getNodesList()))
          .set(Tables.SCRIPTS.EDGES_JSON, edgesToJson(request.getEdgesList()))
          .set(Tables.SCRIPTS.QUOTAS_JSON, request.hasQuotas() ? quotasToJson(request.getQuotas()) : null)
          .set(Tables.SCRIPTS.PAUSED, request.getPaused())
          .set(Tables.SCRIPTS.CREATED_AT, now)
          .set(Tables.SCRIPTS.UPDATED_AT, now)
          .set(Tables.SCRIPTS.VERSION, 0L)
          .execute();

        return ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(newId))
          .fetchOne();
      });

      // Start the script immediately if not paused
      if (!scriptRecord.getPaused()) {
        startScriptInternal(scriptRecord);
      }

      var createResponse = CreateScriptResponse.newBuilder()
        .setScript(recordToScriptData(scriptRecord));
      for (var diag : collectGraphWarnings(scriptRecord)) {
        createResponse.addDiagnostics(diag);
      }
      responseObserver.onNext(createResponse.build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error creating script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getScript(GetScriptRequest request, StreamObserver<GetScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      var scriptRecord = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
        return record;
      });

      responseObserver.onNext(GetScriptResponse.newBuilder()
        .setScript(recordToScriptData(scriptRecord))
        .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error getting script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void updateScript(UpdateScriptRequest request, StreamObserver<UpdateScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    if (request.hasName()) {
      if (request.getName().isBlank()) {
        throw Status.INVALID_ARGUMENT.withDescription("Script name must not be empty").asRuntimeException();
      }
      if (request.getName().length() > MAX_NAME_LENGTH) {
        throw Status.INVALID_ARGUMENT
          .withDescription("Script name must not exceed " + MAX_NAME_LENGTH + " characters").asRuntimeException();
      }
    }
    if (request.hasDescription() && request.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
      throw Status.INVALID_ARGUMENT
        .withDescription("Script description must not exceed " + MAX_DESCRIPTION_LENGTH + " characters").asRuntimeException();
    }

    try {
      var scriptRecord = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }

        var update = ctx.update(Tables.SCRIPTS)
          .set(Tables.SCRIPTS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC));

        if (request.hasName()) {
          update = update.set(Tables.SCRIPTS.NAME, request.getName());
        }
        if (request.hasDescription()) {
          update = update.set(Tables.SCRIPTS.DESCRIPTION, request.getDescription());
        }
        if (request.getUpdateNodes()) {
          update = update.set(Tables.SCRIPTS.NODES_JSON, nodesToJson(request.getNodesList()));
        }
        if (request.getUpdateEdges()) {
          update = update.set(Tables.SCRIPTS.EDGES_JSON, edgesToJson(request.getEdgesList()));
        }
        if (request.getUpdateQuotas()) {
          update = update.set(Tables.SCRIPTS.QUOTAS_JSON, request.hasQuotas() ? quotasToJson(request.getQuotas()) : null);
        }
        if (request.hasPaused()) {
          update = update.set(Tables.SCRIPTS.PAUSED, request.getPaused());
        }

        update.where(Tables.SCRIPTS.ID.eq(scriptId.toString())).execute();

        return ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
      });

      // Restart the script if it's not paused (to apply new configuration)
      if (!scriptRecord.getPaused()) {
        restartScriptInternal(scriptRecord);
      } else {
        // Script is paused, make sure it's stopped
        stopScriptInternal(scriptId);
      }

      var updateResponse = UpdateScriptResponse.newBuilder()
        .setScript(recordToScriptData(scriptRecord));
      for (var diag : collectGraphWarnings(scriptRecord)) {
        updateResponse.addDiagnostics(diag);
      }
      responseObserver.onNext(updateResponse.build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error updating script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void deleteScript(DeleteScriptRequest request, StreamObserver<DeleteScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.UPDATE_INSTANCE_CONFIG, instanceId));

    try {
      soulFireServer.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }

        // Stop the script if it's running (after ownership check)
        stopScriptInternal(scriptId);

        ctx.deleteFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .execute();
      });

      log.info("Script {} deleted", scriptId);
      responseObserver.onNext(DeleteScriptResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error deleting script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void listScripts(ListScriptsRequest request, StreamObserver<ListScriptsResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      var scripts = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var instanceRecord = ctx.selectFrom(Tables.INSTANCES)
          .where(Tables.INSTANCES.ID.eq(instanceId.toString()))
          .fetchOne();
        if (instanceRecord == null) {
          throw Status.NOT_FOUND.withDescription("Instance '%s' not found".formatted(instanceId)).asRuntimeException();
        }

        return ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.INSTANCE_ID.eq(instanceId.toString()))
          .orderBy(Tables.SCRIPTS.CREATED_AT.desc())
          .fetch();
      });

      var responseBuilder = ListScriptsResponse.newBuilder();
      for (var script : scripts) {
        responseBuilder.addScripts(recordToScriptInfo(script));
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error listing scripts", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void activateScript(ActivateScriptRequest request, StreamObserver<ScriptEvent> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.CHANGE_INSTANCE_STATE, instanceId));

    try {
      // Load and unpause the script in the database
      var scriptRecord = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
        // Set paused=false to persist the resumed state
        if (record.getPaused()) {
          ctx.update(Tables.SCRIPTS)
            .set(Tables.SCRIPTS.PAUSED, false)
            .set(Tables.SCRIPTS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
            .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
            .execute();
          record.setPaused(false);
        }
        return record;
      });

      var serverObserver = (ServerCallStreamObserver<ScriptEvent>) responseObserver;

      // Set up cancellation handler - client disconnect does NOT stop the script
      // (scripts run independently of client connections in the pause-based model)
      serverObserver.setOnCancelHandler(() -> {
        // Null out the observer in the AtomicReference but keep the script running
        var currentState = activeScripts.get(scriptId);
        if (currentState != null && currentState.observerRef() != null) {
          currentState.observerRef().set(null);
        }
        log.info("Client disconnected from script {} stream (script keeps running)", scriptId);
      });

      // Check if script is already running
      var existingState = activeScripts.get(scriptId);
      if (existingState != null) {
        // Script is already running, update the AtomicReference so the existing
        // event listener sends future events to the new observer
        if (existingState.observerRef() != null) {
          existingState.observerRef().set(serverObserver);
        }
        // Update activation count (record is immutable, so create new state with same observerRef)
        activeScripts.put(scriptId, new ScriptActivationState(
          existingState.scriptId(),
          existingState.instanceId(),
          existingState.active(),
          existingState.activeNodeId(),
          existingState.activationCount() + 1,
          existingState.context(),
          existingState.observerRef()
        ));

        // Send script started event once the stream is ready
        // (Armeria requires headers to be sent before messages, which happens after this method returns)
        sendScriptStartedWhenReady(serverObserver, scriptId);

        log.info("Client attached to already-running script {}", scriptId);
        return;
      }

      // Script is not running, start it
      // Build the script graph from the record and apply constant folding
      var graph = ConstantFolding.fold(buildScriptGraph(scriptRecord));

      // Get the instance manager
      var instanceManager = soulFireServer.getInstance(instanceId)
        .orElseThrow(() -> Status.NOT_FOUND.withDescription("Instance not found").asRuntimeException());

      // Create AtomicReference for the observer so the event listener always
      // sees the latest observer, even after client disconnect/reconnect
      var observerRef = new AtomicReference<>(serverObserver);

      // Create event listener that streams events to the client
      var eventListener = createStreamingEventListener(scriptId, observerRef);

      // Parse quotas from the database record
      var quotas = ScriptQuotasConfig.fromProto(jsonToQuotas(scriptRecord.getQuotasJson()));

      // Create reactive context for trigger execution
      var context = new ReactiveScriptContext(instanceManager, eventListener, quotas);

      // Register triggers for event-driven execution using reactive engine
      var engine = new ReactiveScriptEngine();
      triggerService.registerTriggers(scriptId, graph, context, engine);

      // Store active script state
      var activationState = new ScriptActivationState(
        scriptId,
        instanceId,
        true,
        null,
        1,
        context,
        observerRef
      );
      activeScripts.put(scriptId, activationState);

      // Send script started event once the stream is ready
      // (Armeria requires headers to be sent before messages, which happens after this method returns)
      sendScriptStartedWhenReady(serverObserver, scriptId);

      log.info("Script {} resumed with {} triggers, {} data edges", scriptId,
        graph.findTriggerNodes().size(), graph.dataEdges().size());

    } catch (StatusRuntimeException e) {
      throw e;
    } catch (ScriptGraphValidationException e) {
      log.error("Script {} has invalid graph: {}", scriptId, e.errors());
      throw Status.INVALID_ARGUMENT.withDescription(e.getMessage()).withCause(e).asRuntimeException();
    } catch (Throwable t) {
      log.error("Error resuming script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void deactivateScript(DeactivateScriptRequest request, StreamObserver<DeactivateScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.CHANGE_INSTANCE_STATE, instanceId));

    try {
      // Set paused=true in the database to persist the paused state
      soulFireServer.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
        ctx.update(Tables.SCRIPTS)
          .set(Tables.SCRIPTS.PAUSED, true)
          .set(Tables.SCRIPTS.UPDATED_AT, LocalDateTime.now(ZoneOffset.UTC))
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .execute();
      });

      // Stop the script if it's running
      stopScriptInternal(scriptId);

      log.info("Script {} paused", scriptId);
      responseObserver.onNext(DeactivateScriptResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error pausing script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getScriptStatus(GetScriptStatusRequest request, StreamObserver<GetScriptStatusResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      // Verify script exists
      soulFireServer.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
      });

      var activationState = activeScripts.get(scriptId);
      var statusBuilder = ScriptStatus.newBuilder()
        .setScriptId(scriptId.toString())
        .setIsActive(activationState != null)
        .setActivationCount(activationState != null ? activationState.activationCount() : 0);

      if (activationState != null && activationState.activeNodeId() != null) {
        statusBuilder.setActiveNodeId(activationState.activeNodeId());
      }

      responseObserver.onNext(GetScriptStatusResponse.newBuilder()
        .setStatus(statusBuilder.build())
        .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error getting script status", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void subscribeScriptLogs(SubscribeScriptLogsRequest request, StreamObserver<ScriptLogEntry> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    var scriptId = UUID.fromString(request.getScriptId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.INSTANCE_SUBSCRIBE_LOGS, instanceId));

    try {
      // Verify script exists
      soulFireServer.dsl().transaction(cfg -> {
        var ctx = DSL.using(cfg);
        var record = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (record == null || !record.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
      });

      var serverObserver = (ServerCallStreamObserver<ScriptLogEntry>) responseObserver;

      // TODO: Implement log subscription via the logging system
      // This would hook into SFLogAppender to filter logs by script ID
      // For now, we just keep the stream open until cancelled

      serverObserver.setOnCancelHandler(() ->
        log.debug("Script log subscription cancelled for script {}", scriptId));

    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error subscribing to script logs", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getNodeTypes(GetNodeTypesRequest request, StreamObserver<GetNodeTypesResponse> responseObserver) {
    // No authentication required - node types are public metadata
    try {
      var categoryId = request.hasCategory() ? request.getCategory() : null;
      var includeDeprecated = request.getIncludeDeprecated();

      var metadata = NodeRegistry.getFilteredMetadata(categoryId, includeDeprecated);
      var categories = NodeRegistry.getAllCategories();

      var responseBuilder = GetNodeTypesResponse.newBuilder();

      for (var nodeMeta : metadata) {
        responseBuilder.addNodeTypes(metadataToProto(nodeMeta));
      }

      for (var category : categories) {
        responseBuilder.addCategories(categoryToProto(category));
      }

      // Add port type metadata for data-driven port rendering
      for (var portTypeMeta : getPortTypeMetadata()) {
        responseBuilder.addPortTypeMetadata(portTypeMeta);
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting node types", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  @Override
  public void getRegistryData(GetRegistryDataRequest request, StreamObserver<GetRegistryDataResponse> responseObserver) {
    // No authentication required - registry data is public metadata
    try {
      var responseBuilder = GetRegistryDataResponse.newBuilder();
      var specificRegistry = request.hasRegistry() ? request.getRegistry() : null;

      // Add blocks
      if (specificRegistry == null || "blocks".equals(specificRegistry)) {
        for (var block : BuiltInRegistries.BLOCK) {
          var id = BuiltInRegistries.BLOCK.getKey(block);
          responseBuilder.addBlocks(RegistryEntry.newBuilder()
            .setId(id.toString())
            .setDisplayName(formatRegistryId(id.toString()))
            .build());
        }
      }

      // Add entities
      if (specificRegistry == null || "entities".equals(specificRegistry)) {
        for (var entityType : BuiltInRegistries.ENTITY_TYPE) {
          var id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
          responseBuilder.addEntities(RegistryEntry.newBuilder()
            .setId(id.toString())
            .setDisplayName(formatRegistryId(id.toString()))
            .build());
        }
      }

      // Add items
      if (specificRegistry == null || "items".equals(specificRegistry)) {
        for (var item : BuiltInRegistries.ITEM) {
          var id = BuiltInRegistries.ITEM.getKey(item);
          responseBuilder.addItems(RegistryEntry.newBuilder()
            .setId(id.toString())
            .setDisplayName(formatRegistryId(id.toString()))
            .build());
        }
      }

      // Add biomes - use reflection on Biomes class to dynamically list all known biome keys
      if (specificRegistry == null || "biomes".equals(specificRegistry)) {
        var biomeIds = getBiomeIds();
        for (var biomeId : biomeIds) {
          responseBuilder.addBiomes(RegistryEntry.newBuilder()
            .setId(biomeId)
            .setDisplayName(formatRegistryId(biomeId))
            .build());
        }
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (Throwable t) {
      log.error("Error getting registry data", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  /// Port type display metadata (colors, shapes, styles).
  private record PortTypeDisplayInfo(
    com.soulfiremc.server.script.PortType serverType,
    PortType protoType,
    String color,
    String displayName,
    HandleShape handleShape,
    EdgeStyle edgeStyle
  ) {}

  private static final List<PortTypeDisplayInfo> PORT_TYPE_DISPLAY = List.of(
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.EXEC, PortType.PORT_TYPE_EXEC, "#ffffff", "Execution", HandleShape.HANDLE_SHAPE_SQUARE, EdgeStyle.EDGE_STYLE_ANIMATED),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.NUMBER, PortType.PORT_TYPE_NUMBER, "#22c55e", "Number", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.BOOLEAN, PortType.PORT_TYPE_BOOLEAN, "#ef4444", "Boolean", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.STRING, PortType.PORT_TYPE_STRING, "#eab308", "String", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.VECTOR3, PortType.PORT_TYPE_VECTOR3, "#3b82f6", "Vector3", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.ENTITY, PortType.PORT_TYPE_ENTITY, "#a855f7", "Entity", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.BOT, PortType.PORT_TYPE_BOT, "#f97316", "Bot", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.BLOCK, PortType.PORT_TYPE_BLOCK, "#06b6d4", "Block", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.ITEM, PortType.PORT_TYPE_ITEM, "#ec4899", "Item", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.LIST, PortType.PORT_TYPE_LIST, "#8b5cf6", "List", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.MAP, PortType.PORT_TYPE_MAP, "#14b8a6", "Map", HandleShape.HANDLE_SHAPE_DIAMOND, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.SET, PortType.PORT_TYPE_SET, "#0ea5e9", "Set", HandleShape.HANDLE_SHAPE_DIAMOND, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.COLLECTION, PortType.PORT_TYPE_COLLECTION, "#6366f1", "Collection", HandleShape.HANDLE_SHAPE_DIAMOND, EdgeStyle.EDGE_STYLE_DEFAULT),
    new PortTypeDisplayInfo(com.soulfiremc.server.script.PortType.ANY, PortType.PORT_TYPE_ANY, "#6b7280", "Any", HandleShape.HANDLE_SHAPE_CIRCLE, EdgeStyle.EDGE_STYLE_DEFAULT)
  );

  /// Returns port type metadata for data-driven port rendering.
  /// Uses TypeCompatibility as the single source of truth for compatible_from lists.
  private List<PortTypeMetadata> getPortTypeMetadata() {
    var result = new ArrayList<PortTypeMetadata>();
    for (var info : PORT_TYPE_DISPLAY) {
      var builder = PortTypeMetadata.newBuilder()
        .setPortType(info.protoType())
        .setColor(info.color())
        .setDisplayName(info.displayName())
        .setHandleShape(info.handleShape())
        .setEdgeStyle(info.edgeStyle());

      for (var compatibleType : TypeCompatibility.getCompatibleFrom(info.serverType())) {
        builder.addCompatibleFrom(portTypeToProto(compatibleType));
      }

      result.add(builder.build());
    }
    return result;
  }

  private CategoryDefinition categoryToProto(NodeCategory category) {
    return CategoryDefinition.newBuilder()
      .setId(category.id())
      .setDisplayName(category.displayName())
      .setIcon(category.icon())
      .setDescription(category.description())
      .setSortOrder(category.sortOrder())
      .build();
  }

  private NodeTypeDefinition metadataToProto(NodeMetadata metadata) {
    var builder = NodeTypeDefinition.newBuilder()
      .setType(metadata.type())
      .setDisplayName(metadata.displayName())
      .setDescription(metadata.description())
      .setCategory(metadata.category().id())
      .setIsTrigger(metadata.isTrigger())
      .addAllKeywords(metadata.keywords())
      .setDeprecated(metadata.deprecated())
      .setIcon(metadata.icon())
      .setIsLayoutNode(metadata.isLayoutNode())
      .setSupportsMuting(metadata.supportsMuting())
      .setSupportsPreview(metadata.supportsPreview())
      .setIsExpensive(metadata.isExpensive());

    if (metadata.color() != null) {
      builder.setColor(metadata.color());
    }
    if (metadata.deprecationMessage() != null) {
      builder.setDeprecationMessage(metadata.deprecationMessage());
    }

    for (var input : metadata.inputs()) {
      builder.addInputs(portDefinitionToProto(input));
    }
    for (var output : metadata.outputs()) {
      builder.addOutputs(portDefinitionToProto(output));
    }

    return builder.build();
  }

  private com.soulfiremc.grpc.generated.PortDefinition portDefinitionToProto(PortDefinition port) {
    var builder = com.soulfiremc.grpc.generated.PortDefinition.newBuilder()
      .setId(port.id())
      .setDisplayName(port.displayName())
      .setPortType(portTypeToProto(port.type()))
      .setRequired(port.required())
      .setDescription(port.description())
      .setMultiInput(port.multiInput());

    if (port.defaultValue() != null) {
      builder.setDefaultValue(port.defaultValue());
    }
    if (port.elementType() != null) {
      builder.setElementType(portTypeToProto(port.elementType()));
    }
    if (port.typeDescriptor() != null) {
      builder.setTypeDescriptor(typeDescriptorToProto(port.typeDescriptor()));
    }

    return builder.build();
  }

  private TypeDescriptor typeDescriptorToProto(com.soulfiremc.server.script.TypeDescriptor td) {
    var builder = TypeDescriptor.newBuilder();
    switch (td) {
      case com.soulfiremc.server.script.TypeDescriptor.Simple(com.soulfiremc.server.script.PortType type) ->
        builder.setSimple(portTypeToProto(type));
      case com.soulfiremc.server.script.TypeDescriptor.Parameterized(com.soulfiremc.server.script.PortType base, List<com.soulfiremc.server.script.TypeDescriptor> params) -> {
        var paramBuilder = ParameterizedType.newBuilder()
          .setBase(portTypeToProto(base));
        for (var param : params) {
          paramBuilder.addParams(typeDescriptorToProto(param));
        }
        builder.setParameterized(paramBuilder.build());
      }
      case com.soulfiremc.server.script.TypeDescriptor.TypeVariable(String name) ->
        builder.setTypeVariable(name);
    }
    return builder.build();
  }

  private PortType portTypeToProto(com.soulfiremc.server.script.PortType type) {
    return switch (type) {
      case ANY -> PortType.PORT_TYPE_ANY;
      case NUMBER -> PortType.PORT_TYPE_NUMBER;
      case STRING -> PortType.PORT_TYPE_STRING;
      case BOOLEAN -> PortType.PORT_TYPE_BOOLEAN;
      case VECTOR3 -> PortType.PORT_TYPE_VECTOR3;
      case BOT -> PortType.PORT_TYPE_BOT;
      case LIST -> PortType.PORT_TYPE_LIST;
      case EXEC -> PortType.PORT_TYPE_EXEC;
      case BLOCK -> PortType.PORT_TYPE_BLOCK;
      case ENTITY -> PortType.PORT_TYPE_ENTITY;
      case ITEM -> PortType.PORT_TYPE_ITEM;
      case MAP -> PortType.PORT_TYPE_MAP;
      case SET -> PortType.PORT_TYPE_SET;
      case COLLECTION -> PortType.PORT_TYPE_COLLECTION;
    };
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private String nodesToJson(List<ScriptNode> nodes) {
    var nodeDataList = nodes.stream()
      .map(node -> new ScriptNodeData(
        node.getId(),
        node.getType(),
        new PositionData(node.getPosition().getX(), node.getPosition().getY()),
        node.getDataMap().entrySet().stream()
          .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> valueToJsonElement(e.getValue())
          ))
      ))
      .toList();
    return GsonInstance.GSON.toJson(nodeDataList);
  }

  private String edgesToJson(List<ScriptEdge> edges) {
    var edgeDataList = edges.stream()
      .map(edge -> new ScriptEdgeData(
        edge.getId(),
        edge.getSource(),
        edge.getSourceHandle(),
        edge.getTarget(),
        edge.getTargetHandle(),
        edge.getEdgeType().name(),
        edge.hasOrder() ? edge.getOrder() : null
      ))
      .toList();
    return GsonInstance.GSON.toJson(edgeDataList);
  }

  private String quotasToJson(ScriptQuotas quotas) {
    try {
      return JsonFormat.printer().omittingInsignificantWhitespace().print(quotas);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  private ScriptQuotas jsonToQuotas(String json) {
    if (json == null || json.isBlank()) {
      return ScriptQuotas.getDefaultInstance();
    }
    try {
      var builder = ScriptQuotas.newBuilder();
      JsonFormat.parser().merge(json, builder);
      return builder.build();
    } catch (InvalidProtocolBufferException e) {
      log.warn("Failed to parse quotas JSON: {}", json, e);
      return ScriptQuotas.getDefaultInstance();
    }
  }

  private List<ScriptNode> jsonToNodes(String json) {
    List<ScriptNodeData> nodeDataList = GsonInstance.GSON.fromJson(json, NODE_LIST_TYPE);
    if (nodeDataList == null) {
      return List.of();
    }
    return nodeDataList.stream()
      .map(data -> {
        var nodeBuilder = ScriptNode.newBuilder()
          .setId(data.id())
          .setType(data.type())
          .setPosition(Position.newBuilder()
            .setX(data.position().x())
            .setY(data.position().y())
            .build());

        if (data.data() != null) {
          data.data().forEach((key, jsonElement) ->
            nodeBuilder.putData(key, jsonElementToValue(jsonElement)));
        }

        return nodeBuilder.build();
      })
      .toList();
  }

  private List<ScriptEdge> jsonToEdges(String json) {
    List<ScriptEdgeData> edgeDataList = GsonInstance.GSON.fromJson(json, EDGE_LIST_TYPE);
    if (edgeDataList == null) {
      return List.of();
    }
    return edgeDataList.stream()
      .map(data -> {
        EdgeType edgeType;
        try {
          edgeType = EdgeType.valueOf(data.edgeType());
        } catch (IllegalArgumentException _) {
          log.warn("Unknown edge type '{}' in edge {}, defaulting to EDGE_TYPE_DATA", data.edgeType(), data.id());
          edgeType = EdgeType.EDGE_TYPE_DATA;
        }
        return ScriptEdge.newBuilder()
          .setId(data.id())
          .setSource(data.source())
          .setSourceHandle(data.sourceHandle())
          .setTarget(data.target())
          .setTargetHandle(data.targetHandle())
          .setEdgeType(edgeType)
          .build();
      })
      .toList();
  }

  private ScriptData recordToScriptData(ScriptsRecord record) {
    var builder = ScriptData.newBuilder()
      .setId(record.getId())
      .setName(record.getName())
      .setDescription(record.getDescription() != null ? record.getDescription() : "")
      .setInstanceId(record.getInstanceId())
      .addAllNodes(jsonToNodes(record.getNodesJson()))
      .addAllEdges(jsonToEdges(record.getEdgesJson()))
      .setPaused(record.getPaused());

    var quotas = jsonToQuotas(record.getQuotasJson());
    if (!quotas.equals(ScriptQuotas.getDefaultInstance())) {
      builder.setQuotas(quotas);
    }

    return builder.build();
  }

  private ScriptInfo recordToScriptInfo(ScriptsRecord record) {
    return ScriptInfo.newBuilder()
      .setId(record.getId())
      .setName(record.getName())
      .setDescription(record.getDescription() != null ? record.getDescription() : "")
      .setInstanceId(record.getInstanceId())
      .setCreatedAt(localDateTimeToTimestamp(record.getCreatedAt()))
      .setUpdatedAt(localDateTimeToTimestamp(record.getUpdatedAt()))
      .setPaused(record.getPaused())
      .build();
  }

  private Timestamp localDateTimeToTimestamp(LocalDateTime dateTime) {
    return Timestamps.fromMillis(dateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
  }

  private JsonElement valueToJsonElement(Value value) {
    try {
      return GsonInstance.GSON.fromJson(JsonFormat.printer().print(value), JsonElement.class);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  private Value jsonElementToValue(JsonElement element) {
    try {
      var builder = Value.newBuilder();
      JsonFormat.parser().merge(GsonInstance.GSON.toJson(element), builder);
      return builder.build();
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }

  private static String getParentGroupId(ScriptNodeData node) {
    if (node.data() == null) {
      return null;
    }
    var element = node.data().get("parentGroupId");
    if (element == null || element.isJsonNull()) {
      return null;
    }
    return element.getAsString();
  }

  private static int computeGroupDepth(String groupId, Map<String, String> groupParentMap) {
    var depth = 0;
    var current = groupId;
    while (true) {
      var parent = groupParentMap.get(current);
      if (parent == null) {
        break;
      }
      depth++;
      current = parent;
    }
    return depth;
  }

  /// Flattens layout nodes (groups, reroutes, frames, notes, debug) out of the node/edge lists,
  /// rewiring edges so the resulting graph contains only executable nodes.
  private static FlattenedGraph flattenLayoutNodes(List<ScriptNodeData> inputNodes, List<ScriptEdgeData> inputEdges) {
    var nodes = new ArrayList<>(inputNodes);
    var edges = new ArrayList<>(inputEdges);

    // Phase 1: Remove visual-only nodes (frames and notes)
    var visualOnlyIds = nodes.stream()
      .filter(n -> "layout.frame".equals(n.type()) || "layout.note".equals(n.type()))
      .map(ScriptNodeData::id)
      .collect(Collectors.toSet());
    nodes.removeIf(n -> visualOnlyIds.contains(n.id()));
    edges.removeIf(e -> visualOnlyIds.contains(e.source()) || visualOnlyIds.contains(e.target()));

    // Phase 2: Flatten pass-through nodes (reroute and debug)
    var passThroughIds = nodes.stream()
      .filter(n -> "layout.reroute".equals(n.type()) || "layout.debug".equals(n.type()))
      .map(ScriptNodeData::id)
      .collect(Collectors.toSet());
    for (var ptId : passThroughIds) {
      var incoming = edges.stream().filter(e -> ptId.equals(e.target())).toList();
      var outgoing = edges.stream().filter(e -> ptId.equals(e.source())).toList();
      for (var in : incoming) {
        for (var out : outgoing) {
          edges.add(new ScriptEdgeData(
            in.id() + "_" + out.id(),
            in.source(), in.sourceHandle(),
            out.target(), out.targetHandle(),
            out.edgeType(),
            out.order()
          ));
        }
      }
      edges.removeIf(e -> ptId.equals(e.source()) || ptId.equals(e.target()));
    }
    nodes.removeIf(n -> passThroughIds.contains(n.id()));

    // Phase 3: Flatten groups (bottom-up for nesting)
    var groupNodes = nodes.stream()
      .filter(n -> "layout.group".equals(n.type()))
      .toList();
    if (groupNodes.isEmpty()) {
      return new FlattenedGraph(nodes, edges);
    }

    // Build groupId -> parentGroupId map for depth computation
    var groupParentMap = new HashMap<String, String>();
    for (var g : groupNodes) {
      var parentId = getParentGroupId(g);
      if (parentId != null) {
        groupParentMap.put(g.id(), parentId);
      }
    }

    // Sort by depth descending (innermost first)
    var sortedGroups = new ArrayList<>(groupNodes);
    sortedGroups.sort(Comparator.comparingInt(
      (ScriptNodeData g) -> computeGroupDepth(g.id(), groupParentMap)).reversed());

    for (var group : sortedGroups) {
      var groupId = group.id();
      var groupParentId = getParentGroupId(group);

      // Find group_input and group_output children
      ScriptNodeData groupInput = null;
      ScriptNodeData groupOutput = null;
      for (var n : nodes) {
        if (groupId.equals(getParentGroupId(n))) {
          if ("layout.group_input".equals(n.type())) {
            groupInput = n;
          } else if ("layout.group_output".equals(n.type())) {
            groupOutput = n;
          }
        }
      }

      // Rewire incoming edges (external -> group node becomes external -> internal target)
      if (groupInput != null) {
        var incomingToGroup = edges.stream()
          .filter(e -> groupId.equals(e.target()))
          .toList();
        for (var extEdge : incomingToGroup) {
          var portId = extEdge.targetHandle();
          // Find the internal edge from groupInput with matching sourceHandle
          var giId = groupInput.id();
          var internalEdge = edges.stream()
            .filter(e -> giId.equals(e.source()) && portId.equals(e.sourceHandle()))
            .findFirst().orElse(null);
          if (internalEdge != null) {
            edges.add(new ScriptEdgeData(
              extEdge.id() + "_rewired",
              extEdge.source(), extEdge.sourceHandle(),
              internalEdge.target(), internalEdge.targetHandle(),
              extEdge.edgeType(),
              extEdge.order()
            ));
          } else {
            log.warn("Group {} flattening: could not find internal edge for handle '{}', dropping connection", groupId, portId);
          }
        }
      }

      // Rewire outgoing edges (group node -> external becomes internal source -> external)
      if (groupOutput != null) {
        var outgoingFromGroup = edges.stream()
          .filter(e -> groupId.equals(e.source()))
          .toList();
        for (var extEdge : outgoingFromGroup) {
          var portId = extEdge.sourceHandle();
          // Find the internal edge to groupOutput with matching targetHandle
          var goId = groupOutput.id();
          var internalEdge = edges.stream()
            .filter(e -> goId.equals(e.target()) && portId.equals(e.targetHandle()))
            .findFirst().orElse(null);
          if (internalEdge != null) {
            edges.add(new ScriptEdgeData(
              extEdge.id() + "_rewired",
              internalEdge.source(), internalEdge.sourceHandle(),
              extEdge.target(), extEdge.targetHandle(),
              extEdge.edgeType(),
              extEdge.order()
            ));
          } else {
            log.warn("Group {} flattening: could not find internal edge for handle '{}', dropping connection", groupId, portId);
          }
        }
      }

      // Collect IDs to remove
      var removeIds = new HashSet<String>();
      removeIds.add(groupId);
      if (groupInput != null) {
        removeIds.add(groupInput.id());
      }
      if (groupOutput != null) {
        removeIds.add(groupOutput.id());
      }

      // Promote children: update parentGroupId of remaining child nodes to the group's own parentGroupId
      var updatedNodes = new ArrayList<ScriptNodeData>();
      for (var n : nodes) {
        if (removeIds.contains(n.id())) {
          continue;
        }
        if (groupId.equals(getParentGroupId(n))) {
          // Promote: replace parentGroupId with the group's parent
          var newData = new HashMap<>(n.data() != null ? n.data() : Map.<String, JsonElement>of());
          if (groupParentId != null) {
            newData.put("parentGroupId", GsonInstance.GSON.toJsonTree(groupParentId));
          } else {
            newData.remove("parentGroupId");
          }
          updatedNodes.add(new ScriptNodeData(n.id(), n.type(), n.position(), newData));
        } else {
          updatedNodes.add(n);
        }
      }
      nodes = updatedNodes;

      // Remove all edges referencing the removed nodes
      edges.removeIf(e -> removeIds.contains(e.source()) || removeIds.contains(e.target()));
    }

    return new FlattenedGraph(nodes, edges);
  }

  private ScriptGraph buildScriptGraph(ScriptsRecord record) {
    var builder = ScriptGraph.builder(record.getId(), record.getName());

    List<ScriptNodeData> rawNodes = GsonInstance.GSON.fromJson(record.getNodesJson(), NODE_LIST_TYPE);
    List<ScriptEdgeData> rawEdges = GsonInstance.GSON.fromJson(record.getEdgesJson(), EDGE_LIST_TYPE);

    if (rawNodes == null) {
      rawNodes = List.of();
    }
    if (rawEdges == null) {
      rawEdges = List.of();
    }

    // Flatten layout nodes (groups, reroutes, frames, notes, debug)
    var flattened = flattenLayoutNodes(rawNodes, rawEdges);
    var nodes = flattened.nodes();
    var edges = flattened.edges();

    if (nodes.size() > 500) {
      log.warn("Script '{}' has {} nodes after group inlining (recommended max: 500)",
        record.getName(), nodes.size());
    }

    // Add nodes
    {
      for (var node : nodes) {
        Map<String, Object> defaultInputs = new HashMap<>();
        if (node.data() != null) {
          for (var entry : node.data().entrySet()) {
            defaultInputs.put(entry.getKey(), jsonElementToObject(entry.getValue()));
          }
        }
        builder.addNode(node.id(), node.type(), defaultInputs);
      }
    }

    // Add edges
    for (var edge : edges) {
      if ("EDGE_TYPE_EXECUTION".equals(edge.edgeType())) {
        builder.addExecutionEdge(edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle(), edge.order() != null ? edge.order() : 0);
      } else {
        log.debug("Graph {}: DATA edge {}.{} -> {}.{}", record.getName(),
          edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle());
        builder.addDataEdge(edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle(), edge.order() != null ? edge.order() : 0);
      }
    }

    return builder.build();
  }

  private Object jsonElementToObject(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }
    if (element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      if (primitive.isBoolean()) {
        return primitive.getAsBoolean();
      }
      if (primitive.isNumber()) {
        var num = primitive.getAsNumber();
        if (num.doubleValue() == Math.floor(num.doubleValue()) && !Double.isInfinite(num.doubleValue())) {
          var longVal = num.longValue();
          if ((double) longVal == num.doubleValue()) {
            return longVal;
          }
        }
        return num.doubleValue();
      }
      return primitive.getAsString();
    }
    if (element.isJsonArray()) {
      return GsonInstance.GSON.fromJson(element, List.class);
    }
    if (element.isJsonObject()) {
      return GsonInstance.GSON.fromJson(element, Map.class);
    }
    return element.toString();
  }

  /// Defers sending the ScriptStarted event until the stream is ready.
  /// Armeria requires response headers to be sent before any messages,
  /// which only happens after the RPC handler method returns.
  private void sendScriptStartedWhenReady(ServerCallStreamObserver<ScriptEvent> observer, UUID scriptId) {
    var event = ScriptEvent.newBuilder()
      .setScriptStarted(ScriptStarted.newBuilder()
        .setScriptId(scriptId.toString())
        .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
        .build())
      .build();
    var context = ServiceRequestContext.current();
    context.eventLoop().execute(() -> {
      if (observer.isCancelled()) {
        return;
      }
      try {
        observer.onNext(event);
      } catch (Exception e) {
        log.debug("Error sending script started event", e);
      }
    });
  }

  /// Sends an event to the observer if it's present and not cancelled.
  private void sendToObserver(AtomicReference<ServerCallStreamObserver<ScriptEvent>> observerRef, ScriptEvent event) {
    var observer = observerRef.get();
    if (observer != null && !observer.isCancelled()) {
      try {
        observer.onNext(event);
      } catch (Exception e) {
        log.debug("Error sending script event", e);
      }
    }
  }

  private ScriptEventListener createStreamingEventListener(UUID scriptId, AtomicReference<ServerCallStreamObserver<ScriptEvent>> observerRef) {
    return new ScriptEventListener() {
      @Override
      public void onNodeStarted(String nodeId) {
        sendToObserver(observerRef, ScriptEvent.newBuilder()
          .setNodeStarted(NodeStarted.newBuilder()
            .setNodeId(nodeId)
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
            .build())
          .build());
      }

      @Override
      public void onNodeCompleted(String nodeId, Map<String, NodeValue> outputs) {
        onNodeCompleted(nodeId, outputs, 0);
      }

      @Override
      public void onNodeCompleted(String nodeId, Map<String, NodeValue> outputs, long executionTimeNanos) {
        var builder = NodeCompleted.newBuilder()
          .setNodeId(nodeId)
          .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
          .setExecutionTimeNanos(executionTimeNanos);
        for (var entry : outputs.entrySet()) {
          builder.putOutputs(entry.getKey(), nodeValueToProtoValue(entry.getValue()));
        }
        sendToObserver(observerRef, ScriptEvent.newBuilder().setNodeCompleted(builder.build()).build());
      }

      @Override
      public void onNodeError(String nodeId, String error) {
        sendToObserver(observerRef, ScriptEvent.newBuilder()
          .setNodeError(NodeError.newBuilder()
            .setNodeId(nodeId)
            .setErrorMessage(error)
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
            .build())
          .build());
      }

      @Override
      public void onScriptCompleted(boolean success) {
        var observer = observerRef.get();
        if (observer != null && !observer.isCancelled()) {
          try {
            observer.onNext(ScriptEvent.newBuilder()
              .setScriptCompleted(ScriptCompleted.newBuilder()
                .setScriptId(scriptId.toString())
                .setSuccess(success)
                .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                .build())
              .build());
            observer.onCompleted();
          } catch (Exception e) {
            log.debug("Error sending script completed event", e);
          }
        }
      }

      @Override
      public void onScriptCancelled() {
        activeScripts.remove(scriptId);
        var observer = observerRef.get();
        if (observer != null && !observer.isCancelled()) {
          try {
            observer.onNext(ScriptEvent.newBuilder()
              .setScriptCompleted(ScriptCompleted.newBuilder()
                .setScriptId(scriptId.toString())
                .setSuccess(false)
                .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                .build())
              .build());
            observer.onCompleted();
          } catch (Exception e) {
            log.debug("Error sending script cancelled event", e);
          }
        }
      }

      @Override
      public void onLog(String level, String message) {
        log.info("[Script {}] [{}] {}", scriptId, level.toUpperCase(), message);
        sendToObserver(observerRef, ScriptEvent.newBuilder()
          .setScriptLog(ScriptLog.newBuilder()
            .setLevel(level)
            .setMessage(message)
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
            .build())
          .build());
      }

      @Override
      public void onExecutionStats(long nodeCount, long maxCount) {
        sendToObserver(observerRef, ScriptEvent.newBuilder()
          .setExecutionStats(ExecutionStats.newBuilder()
            .setNodeCount(nodeCount)
            .setMaxCount(maxCount)
            .build())
          .build());
      }
    };
  }

  private Value objectToProtoValue(Object value) {
    if (value == null) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (value instanceof Boolean b) {
      return Value.newBuilder().setBoolValue(b).build();
    }
    if (value instanceof Number n) {
      return Value.newBuilder().setNumberValue(n.doubleValue()).build();
    }
    if (value instanceof String s) {
      return Value.newBuilder().setStringValue(s).build();
    }
    if (value instanceof List<?> list) {
      var listBuilder = ListValue.newBuilder();
      for (var item : list) {
        listBuilder.addValues(objectToProtoValue(item));
      }
      return Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    if (value instanceof Map<?, ?> map) {
      var structBuilder = Struct.newBuilder();
      for (var entry : map.entrySet()) {
        structBuilder.putFields(entry.getKey().toString(), objectToProtoValue(entry.getValue()));
      }
      return Value.newBuilder().setStructValue(structBuilder.build()).build();
    }
    return Value.newBuilder().setStringValue(value.toString()).build();
  }

  private Value nodeValueToProtoValue(NodeValue value) {
    if (value == null || value.isNull()) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (value instanceof NodeValue.Json(JsonElement element)) {
      return jsonElementToProtoValue(element);
    }
    if (value instanceof NodeValue.Bot(BotConnection bot1)) {
      // Bot references are serialized as their account name
      return Value.newBuilder().setStringValue(bot1.accountName()).build();
    }
    return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
  }

  private Value jsonElementToProtoValue(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (element.isJsonPrimitive()) {
      var primitive = element.getAsJsonPrimitive();
      if (primitive.isBoolean()) {
        return Value.newBuilder().setBoolValue(primitive.getAsBoolean()).build();
      }
      if (primitive.isNumber()) {
        return Value.newBuilder().setNumberValue(primitive.getAsDouble()).build();
      }
      return Value.newBuilder().setStringValue(primitive.getAsString()).build();
    }
    if (element.isJsonArray()) {
      var listBuilder = ListValue.newBuilder();
      for (var item : element.getAsJsonArray()) {
        listBuilder.addValues(jsonElementToProtoValue(item));
      }
      return Value.newBuilder().setListValue(listBuilder.build()).build();
    }
    if (element.isJsonObject()) {
      var structBuilder = Struct.newBuilder();
      for (var entry : element.getAsJsonObject().entrySet()) {
        structBuilder.putFields(entry.getKey(), jsonElementToProtoValue(entry.getValue()));
      }
      return Value.newBuilder().setStructValue(structBuilder.build()).build();
    }
    return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
  }

  /// Gets all known biome IDs from the built-in biome registry.
  private static List<String> getBiomeIds() {
    var biomeRegistry = BuiltInRegistries.REGISTRY.getValue(Registries.BIOME.identifier());
    if (biomeRegistry == null) {
      return List.of();
    }

    return biomeRegistry.listElementIds()
      .map(key -> key.identifier().toString())
      .sorted()
      .toList();
  }

  @Override
  public void validateScript(ValidateScriptRequest request, StreamObserver<ValidateScriptResponse> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.READ_INSTANCE, instanceId));

    try {
      var responseBuilder = ValidateScriptResponse.newBuilder();

      List<ScriptNodeData> rawNodes = request.getNodesList().stream()
        .map(node -> new ScriptNodeData(
          node.getId(),
          node.getType(),
          new PositionData(node.getPosition().getX(), node.getPosition().getY()),
          node.getDataMap().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> valueToJsonElement(e.getValue())))
        ))
        .toList();
      List<ScriptEdgeData> rawEdges = request.getEdgesList().stream()
        .map(edge -> new ScriptEdgeData(
          edge.getId(),
          edge.getSource(),
          edge.getSourceHandle(),
          edge.getTarget(),
          edge.getTargetHandle(),
          edge.getEdgeType().name(),
          edge.hasOrder() ? edge.getOrder() : null
        ))
        .toList();

      var flattened = flattenLayoutNodes(new ArrayList<>(rawNodes), new ArrayList<>(rawEdges));
      var builder = ScriptGraph.builder("validate", "Validation");

      for (var node : flattened.nodes()) {
        Map<String, Object> defaultInputs = new HashMap<>();
        if (node.data() != null) {
          for (var entry : node.data().entrySet()) {
            defaultInputs.put(entry.getKey(), jsonElementToObject(entry.getValue()));
          }
        }
        builder.addNode(node.id(), node.type(), defaultInputs);
      }

      for (var edge : flattened.edges()) {
        if ("EDGE_TYPE_EXECUTION".equals(edge.edgeType())) {
          builder.addExecutionEdge(edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle(), edge.order() != null ? edge.order() : 0);
        } else {
          builder.addDataEdge(edge.source(), edge.sourceHandle(), edge.target(), edge.targetHandle(), edge.order() != null ? edge.order() : 0);
        }
      }

      try {
        var graph = builder.build();
        // Build succeeded, return warnings only
        for (var warning : graph.warnings()) {
          responseBuilder.addDiagnostics(diagnosticToProto(warning));
        }
      } catch (ScriptGraphValidationException e) {
        for (var diagnostic : e.diagnostics()) {
          responseBuilder.addDiagnostics(diagnosticToProto(diagnostic));
        }
      }

      responseObserver.onNext(responseBuilder.build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Throwable t) {
      log.error("Error validating script", t);
      throw Status.INTERNAL.withDescription(t.getMessage()).withCause(t).asRuntimeException();
    }
  }

  private static ValidationDiagnostic diagnosticToProto(ScriptGraph.ValidationDiagnostic d) {
    var builder = ValidationDiagnostic.newBuilder()
      .setMessage(d.message())
      .setSeverity(d.severity() == ScriptGraph.Severity.ERROR
        ? DiagnosticSeverity.DIAGNOSTIC_ERROR
        : DiagnosticSeverity.DIAGNOSTIC_WARNING);
    if (d.nodeId() != null) {
      builder.setNodeId(d.nodeId());
    }
    if (d.edgeId() != null) {
      builder.setEdgeId(d.edgeId());
    }
    return builder.build();
  }

  /// Collects warnings from building a script graph, returning empty list on validation failure.
  private List<ValidationDiagnostic> collectGraphWarnings(ScriptsRecord record) {
    try {
      var graph = buildScriptGraph(record);
      return graph.warnings().stream()
        .map(ScriptServiceImpl::diagnosticToProto)
        .toList();
    } catch (ScriptGraphValidationException _) {
      return List.of();
    } catch (Exception _) {
      return List.of();
    }
  }

  @Override
  public void dryRunScript(DryRunScriptRequest request, StreamObserver<ScriptEvent> responseObserver) {
    var instanceId = UUID.fromString(request.getInstanceId());
    ServerRPCConstants.USER_CONTEXT_KEY.get().hasPermissionOrThrow(
      PermissionContext.instance(InstancePermission.CHANGE_INSTANCE_STATE, instanceId));

    try {
      var scriptId = UUID.fromString(request.getScriptId());
      var triggerNodeId = request.getTriggerNodeId();

      var record = soulFireServer.dsl().transactionResult(cfg -> {
        var ctx = DSL.using(cfg);
        var r = ctx.selectFrom(Tables.SCRIPTS)
          .where(Tables.SCRIPTS.ID.eq(scriptId.toString()))
          .fetchOne();
        if (r == null || !r.getInstanceId().equals(instanceId.toString())) {
          throw Status.NOT_FOUND.withDescription(
            "Script '%s' not found in instance '%s'".formatted(scriptId, instanceId)).asRuntimeException();
        }
        return r;
      });

      var graph = buildScriptGraph(record);

      // Build mock inputs from proto values
      var mockInputs = new HashMap<String, NodeValue>();
      for (var entry : request.getMockInputsMap().entrySet()) {
        mockInputs.put(entry.getKey(), protoValueToNodeValue(entry.getValue()));
      }

      // Create a simple event listener that streams events directly to the client
      var eventListener = new ScriptEventListener() {
        private void send(ScriptEvent event) {
          try {
            responseObserver.onNext(event);
          } catch (Exception _) {
            // Client disconnected
          }
        }

        @Override
        public void onNodeStarted(String nodeId) {
          send(ScriptEvent.newBuilder()
            .setNodeStarted(NodeStarted.newBuilder().setNodeId(nodeId).build())
            .build());
        }

        @Override
        public void onNodeCompleted(String nodeId, Map<String, NodeValue> outputs) {
          send(ScriptEvent.newBuilder()
            .setNodeCompleted(NodeCompleted.newBuilder().setNodeId(nodeId).build())
            .build());
        }

        @Override
        public void onNodeError(String nodeId, String error) {
          send(ScriptEvent.newBuilder()
            .setNodeError(NodeError.newBuilder().setNodeId(nodeId).setErrorMessage(error).build())
            .build());
        }

        @Override
        public void onLog(String level, String message) {
          send(ScriptEvent.newBuilder()
            .setScriptLog(ScriptLog.newBuilder().setLevel(level).setMessage(message).build())
            .build());
        }

        @Override
        public void onScriptCompleted(boolean success) {
          send(ScriptEvent.newBuilder()
            .setScriptCompleted(ScriptCompleted.newBuilder().setSuccess(success).build())
            .build());
        }

        @Override
        public void onScriptCancelled() {}
      };

      // Execute using dry-run context (no InstanceManager)
      var context = new ReactiveScriptContext(eventListener);
      var engine = new ReactiveScriptEngine();

      engine.executeFromTriggerSync(graph, triggerNodeId, context, mockInputs)
        .doFinally(_ -> responseObserver.onCompleted())
        .subscribe(
          _ -> {},
          e -> {
            log.error("Error in dry run execution", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
          }
        );
    } catch (StatusRuntimeException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error in dry run", e);
      throw Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException();
    }
  }

  /// Converts a proto Value to a NodeValue.
  private static NodeValue protoValueToNodeValue(Value value) {
    return switch (value.getKindCase()) {
      case NUMBER_VALUE -> NodeValue.ofNumber(value.getNumberValue());
      case STRING_VALUE -> NodeValue.ofString(value.getStringValue());
      case BOOL_VALUE -> NodeValue.ofBoolean(value.getBoolValue());
      default -> NodeValue.ofString(value.toString());
    };
  }

  /// Formats a registry ID (e.g., "minecraft:diamond_ore") into a display name (e.g., "Diamond Ore").
  private String formatRegistryId(String id) {
    // Remove namespace prefix (e.g., "minecraft:")
    var name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    // Replace underscores with spaces and capitalize each word
    return Arrays.stream(name.split("_"))
      .map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1))
      .collect(Collectors.joining(" "));
  }
}
