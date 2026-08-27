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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.FieldBehavior;
import com.google.api.FieldBehaviorProto;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.NullValue;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ai.mcp.ArmeriaStreamableServerTransportProvider;
import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.grpc.generated.*;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.api.PluginRpcRegistration;
import com.soulfiremc.server.api.SoulFireAPI;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.server.util.RPCConstants;
import com.soulfiremc.server.util.structs.GsonInstance;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/// MCP (Model Context Protocol) service that exposes all SoulFire gRPC operations
/// as MCP tools. This enables AI assistants (like Claude, ChatGPT, etc.) to
/// control SoulFire instances, manage bots, execute commands, and more.
///
/// Authentication is done via Bearer token in the HTTP Authorization header,
/// reusing the same JWT system as the gRPC API.
@Slf4j
public final class MCPService {
  private static final String USER_CONTEXT_KEY = "sf-user";
  private static final Metadata.Key<String> PLUGIN_MCP_USER =
    Metadata.Key.of("x-soulfire-plugin-mcp-user", Metadata.ASCII_STRING_MARSHALLER);

  private final ArmeriaStreamableServerTransportProvider transportProvider;
  private final io.grpc.Server pluginMcpServer;
  private final ManagedChannel pluginMcpChannel;
  private final Map<String, SoulFireUser> pluginMcpUsers = new ConcurrentHashMap<>();

  public ArmeriaStreamableServerTransportProvider getTransportProvider() {
    return transportProvider;
  }

  public MCPService(SoulFireServer soulFireServer) {
    var jsonMapper = new JacksonMcpJsonMapper(new JsonMapper());

    this.transportProvider = ArmeriaStreamableServerTransportProvider
      .builder()
      .jsonMapper(jsonMapper)
      .contextExtractor((ServiceRequestContext ctx) -> {
        var authorization = ctx.request().headers().get("Authorization");
        if (authorization != null) {
          var user = soulFireServer.authSystem().authenticateByHeader(authorization, RPCConstants.API_AUDIENCE);
          if (user.isPresent()) {
            return McpTransportContext.create(Map.of(USER_CONTEXT_KEY, user.get()));
          }
        }
        return McpTransportContext.EMPTY;
      })
      .build();

    var instanceService = new InstanceServiceImpl(soulFireServer);
    var commandService = new CommandServiceImpl(soulFireServer);
    var serverService = new ServerServiceImpl(soulFireServer);
    var botService = new BotServiceImpl(soulFireServer);
    var userService = new UserServiceImpl(soulFireServer);
    var clientService = new ClientServiceImpl(soulFireServer);
    var metricsService = new MetricsServiceImpl(soulFireServer);
    var logService = new LogServiceImpl(soulFireServer);

    var tools = new ArrayList<McpServerFeatures.AsyncToolSpecification>();

    // === Instance Management Tools ===
    tools.add(tool("list_instances",
      "List all SoulFire instances visible to the current user. Returns instance IDs, names, bot summaries, and permissions.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<InstanceListResponse>grpc(o -> instanceService.listInstances(InstanceListRequest.newBuilder().build(), o)))));

    tools.add(tool("create_instance",
      "Create a new SoulFire instance with the given name. Returns the UUID of the new instance.",
      Map.of("friendly_name", prop("string", "Human-readable name for the instance")),
      List.of("friendly_name"),
      authed((_, args) ->
        MCPService.<InstanceCreateResponse>grpc(o -> instanceService.createInstance(
          InstanceCreateRequest.newBuilder().setFriendlyName(str(args, "friendly_name")).build(), o)))));

    tools.add(tool("delete_instance",
      "Permanently delete a SoulFire instance and all its data. Cannot be undone.",
      Map.of("id", prop("string", "UUID of the instance to delete")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<InstanceDeleteResponse>grpc(o -> instanceService.deleteInstance(
          InstanceDeleteRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    tools.add(tool("get_instance_info",
      "Get detailed information about a specific instance including its configuration, bot summary, settings definitions, and plugins.",
      Map.of("id", prop("string", "UUID of the instance")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<InstanceInfoResponse>grpc(o -> instanceService.getInstanceInfo(
          InstanceInfoRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    tools.add(tool("update_instance_meta",
      "Update instance metadata (name or icon). Only one field can be updated per call.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "friendly_name", prop("string", "New name for the instance (optional)"),
        "icon", prop("string", "New icon identifier (optional)")),
      List.of("id"),
      authed((_, args) -> {
        var builder = InstanceUpdateMetaRequest.newBuilder().setId(str(args, "id"));
        if (args.containsKey("friendly_name")) {
          builder.setFriendlyName(str(args, "friendly_name"));
        } else if (args.containsKey("icon")) {
          builder.setIcon(str(args, "icon"));
        }
        return MCPService.<InstanceUpdateMetaResponse>grpc(o -> instanceService.updateInstanceMeta(builder.build(), o));
      })));

    tools.add(tool("update_instance_config_entry",
      "Update a single configuration setting for an instance by namespace and key.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "namespace", prop("string", "Setting namespace (e.g., 'bot', 'account', plugin ID)"),
        "key", prop("string", "Setting key within the namespace"),
        "value", Map.of("description", "The new value (string, number, boolean, etc.)")),
      List.of("id", "namespace", "key", "value"),
      authed((_, args) ->
        MCPService.<InstanceUpdateConfigEntryResponse>grpc(o -> instanceService.updateInstanceConfigEntry(
          InstanceUpdateConfigEntryRequest.newBuilder()
            .setId(str(args, "id"))
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .setValue(toProtoValue(args.get("value")))
            .build(), o)))));

    tools.add(tool("get_audit_log",
      "Retrieve the audit log for an instance showing who performed what actions.",
      Map.of("id", prop("string", "UUID of the instance")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<InstanceAuditLogResponse>grpc(o -> instanceService.getAuditLog(
          InstanceAuditLogRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    // === Account Management Tools ===
    tools.add(tool("add_instance_account",
      "Add an offline Minecraft account to an instance with just a username.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "username", prop("string", "Minecraft username")),
      List.of("id", "username"),
      authed((_, args) -> {
        var username = str(args, "username");
        var profileId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes()).toString();
        return MCPService.<InstanceAddAccountResponse>grpc(o -> instanceService.addInstanceAccount(
          InstanceAddAccountRequest.newBuilder()
            .setId(str(args, "id"))
            .setAccount(MinecraftAccountProto.newBuilder()
              .setType(MinecraftAccountProto.AccountTypeProto.OFFLINE)
              .setProfileId(profileId)
              .setLastKnownName(username)
              .setOfflineJavaData(MinecraftAccountProto.OfflineJavaData.newBuilder().build())
              .build())
            .build(), o));
      })));

    tools.add(tool("remove_instance_account",
      "Remove a Minecraft account from an instance by its profile ID.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "profile_id", prop("string", "Profile UUID of the account to remove")),
      List.of("id", "profile_id"),
      authed((_, args) ->
        MCPService.<InstanceRemoveAccountResponse>grpc(o -> instanceService.removeInstanceAccount(
          InstanceRemoveAccountRequest.newBuilder()
            .setId(str(args, "id"))
            .setProfileId(str(args, "profile_id"))
            .build(), o)))));

    // === Proxy Management Tools ===
    tools.add(tool("add_instance_proxy",
      "Add a proxy to an instance for routing bot connections.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "proxy_type", prop("string", "Proxy type: HTTP, SOCKS4, or SOCKS5"),
        "address", prop("string", "Proxy address in host:port format"),
        "username", prop("string", "Optional proxy username"),
        "password", prop("string", "Optional proxy password")),
      List.of("id", "proxy_type", "address"),
      authed((_, args) -> {
        var proxyBuilder = ProxyProto.newBuilder()
          .setType(ProxyProto.Type.valueOf(str(args, "proxy_type")))
          .setAddress(str(args, "address"));
        if (args.containsKey("username")) {
          proxyBuilder.setUsername(str(args, "username"));
        }
        if (args.containsKey("password")) {
          proxyBuilder.setPassword(str(args, "password"));
        }
        return MCPService.<InstanceAddProxyResponse>grpc(o -> instanceService.addInstanceProxy(
          InstanceAddProxyRequest.newBuilder()
            .setId(str(args, "id"))
            .setProxy(proxyBuilder.build())
            .build(), o));
      })));

    tools.add(tool("remove_instance_proxy",
      "Remove a proxy from an instance by its index.",
      Map.of(
        "id", prop("string", "UUID of the instance"),
        "index", prop("integer", "Zero-based index of the proxy to remove")),
      List.of("id", "index"),
      authed((_, args) ->
        MCPService.<InstanceRemoveProxyResponse>grpc(o -> instanceService.removeInstanceProxy(
          InstanceRemoveProxyRequest.newBuilder()
            .setId(str(args, "id"))
            .setIndex(num(args, "index"))
            .build(), o)))));

    // === Command Execution Tools ===
    tools.add(tool("execute_command",
      "Execute a SoulFire command (Brigadier format). Supports global, instance, or bot scoped execution. Examples: 'help', 'move 100 64 200', 'say Hello'.",
      Map.of(
        "command", prop("string", "The command to execute"),
        "scope_type", prop("string", "Scope: 'global', 'instance', or 'bot'"),
        "instance_id", prop("string", "Instance UUID (required for instance/bot scope)"),
        "bot_id", prop("string", "Bot profile UUID (required for bot scope)")),
      List.of("command", "scope_type"),
      authed((_, args) ->
        MCPService.<CommandResponse>grpc(o -> commandService.executeCommand(
          CommandRequest.newBuilder()
            .setScope(buildCommandScope(args))
            .setCommand(str(args, "command"))
            .build(), o)))));

    tools.add(tool("tab_complete_command",
      "Get tab-completion suggestions for a partial command string.",
      Map.of(
        "command", prop("string", "Partial command to complete"),
        "scope_type", prop("string", "Scope: 'global', 'instance', or 'bot'"),
        "instance_id", prop("string", "Instance UUID (for instance/bot scope)"),
        "bot_id", prop("string", "Bot profile UUID (for bot scope)")),
      List.of("command", "scope_type"),
      authed((_, args) -> {
        var cmd = str(args, "command");
        return MCPService.<CommandCompletionResponse>grpc(o -> commandService.tabCompleteCommand(
          CommandCompletionRequest.newBuilder()
            .setScope(buildCommandScope(args))
            .setCommand(cmd)
            .setCursor(cmd.length())
            .build(), o));
      })));

    // === Bot Control Tools ===
    tools.add(tool("set_bots_desired_state",
      "Set configured bots to running or stopped. SoulFire persists the desired state and reconciles connections asynchronously.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_ids", arrayProp("Profile UUIDs of the configured bots"),
        "desired_state", prop("string", "Desired state: running or stopped")),
      List.of("instance_id", "bot_ids", "desired_state"),
      authed((_, args) -> {
        var rawState = str(args, "desired_state").trim().toUpperCase(Locale.ROOT);
        var desiredState = BotDesiredState.valueOf("BOT_DESIRED_STATE_" + rawState);
        return MCPService.<SetBotsDesiredStateResponse>grpc(o -> botService.setBotsDesiredState(
          SetBotsDesiredStateRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .addAllBotIds(strList(args, "bot_ids"))
            .setDesiredState(desiredState)
            .build(), o));
      })));

    tools.add(tool("restart_bots",
      "Reconnect configured bots and leave their desired state set to running.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_ids", arrayProp("Profile UUIDs of the configured bots")),
      List.of("instance_id", "bot_ids"),
      authed((_, args) ->
        MCPService.<RestartBotsResponse>grpc(o -> botService.restartBots(
          RestartBotsRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .addAllBotIds(strList(args, "bot_ids"))
            .build(), o)))));

    tools.add(tool("get_bot_list",
      "Get a list of all bots in an instance with their status, position, health, and other live data.",
      Map.of("instance_id", prop("string", "UUID of the instance")),
      List.of("instance_id"),
      authed((_, args) ->
        MCPService.<BotListResponse>grpc(o -> botService.getBotList(
          BotListRequest.newBuilder().setInstanceId(str(args, "instance_id")).build(), o)))));

    tools.add(tool("get_bot_info",
      "Get detailed information about a specific bot including full inventory data, position, health, etc.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot")),
      List.of("instance_id", "bot_id"),
      authed((_, args) ->
        MCPService.<BotInfoResponse>grpc(o -> botService.getBotInfo(
          BotInfoRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .build(), o)))));

    tools.add(tool("set_bot_movement",
      "Control bot movement (WASD keys, jump, sneak, sprint). Only set the fields you want to change.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot"),
        "forward", prop("boolean", "Move forward (W key)"),
        "backward", prop("boolean", "Move backward (S key)"),
        "left", prop("boolean", "Strafe left (A key)"),
        "right", prop("boolean", "Strafe right (D key)"),
        "jump", prop("boolean", "Jump (Space key)"),
        "sneak", prop("boolean", "Sneak (Shift key)"),
        "sprint", prop("boolean", "Sprint (Ctrl key)")),
      List.of("instance_id", "bot_id"),
      authed((_, args) -> {
        var builder = BotSetMovementStateRequest.newBuilder()
          .setInstanceId(str(args, "instance_id"))
          .setBotId(str(args, "bot_id"));
        ifPresent(args, "forward", v -> builder.setForward((Boolean) v));
        ifPresent(args, "backward", v -> builder.setBackward((Boolean) v));
        ifPresent(args, "left", v -> builder.setLeft((Boolean) v));
        ifPresent(args, "right", v -> builder.setRight((Boolean) v));
        ifPresent(args, "jump", v -> builder.setJump((Boolean) v));
        ifPresent(args, "sneak", v -> builder.setSneak((Boolean) v));
        ifPresent(args, "sprint", v -> builder.setSprint((Boolean) v));
        return MCPService.<BotSetMovementStateResponse>grpc(o -> botService.setMovementState(builder.build(), o));
      })));

    tools.add(tool("reset_bot_movement",
      "Reset all bot movement controls to their default state (stop all movement).",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot")),
      List.of("instance_id", "bot_id"),
      authed((_, args) ->
        MCPService.<BotResetMovementResponse>grpc(o -> botService.resetMovement(
          BotResetMovementRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .build(), o)))));

    tools.add(tool("set_bot_rotation",
      "Set the bot's view direction (yaw and pitch).",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot"),
        "yaw", prop("number", "Horizontal rotation in degrees (0-360)"),
        "pitch", prop("number", "Vertical rotation in degrees (-90 to 90)")),
      List.of("instance_id", "bot_id", "yaw", "pitch"),
      authed((_, args) ->
        MCPService.<BotSetRotationResponse>grpc(o -> botService.setRotation(
          BotSetRotationRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .setYaw(((Number) args.get("yaw")).floatValue())
            .setPitch(((Number) args.get("pitch")).floatValue())
            .build(), o)))));

    tools.add(tool("set_bot_hotbar_slot",
      "Set the bot's active hotbar slot (0-8).",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot"),
        "slot", prop("integer", "Hotbar slot index (0-8)")),
      List.of("instance_id", "bot_id", "slot"),
      authed((_, args) ->
        MCPService.<BotSetHotbarSlotResponse>grpc(o -> botService.setHotbarSlot(
          BotSetHotbarSlotRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .setSlot(num(args, "slot"))
            .build(), o)))));

    tools.add(tool("click_inventory_slot",
      "Click a slot in the bot's open inventory/container. Click types: LEFT_CLICK, RIGHT_CLICK, SHIFT_LEFT_CLICK, SHIFT_RIGHT_CLICK, DROP, DROP_ALL, SWAP_HOTBAR.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot"),
        "slot", prop("integer", "Slot index to click"),
        "click_type", prop("string", "Click type: LEFT_CLICK, RIGHT_CLICK, SHIFT_LEFT_CLICK, SHIFT_RIGHT_CLICK, DROP, DROP_ALL, SWAP_HOTBAR")),
      List.of("instance_id", "bot_id", "slot", "click_type"),
      authed((_, args) ->
        MCPService.<BotInventoryClickResponse>grpc(o -> botService.clickInventorySlot(
          BotInventoryClickRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .setSlot(num(args, "slot"))
            .setClickType(ClickType.valueOf(str(args, "click_type")))
            .build(), o)))));

    tools.add(tool("get_inventory_state",
      "Get the current inventory/container state for a bot including all slots and items.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot")),
      List.of("instance_id", "bot_id"),
      authed((_, args) ->
        MCPService.<BotInventoryStateResponse>grpc(o -> botService.getInventoryState(
          BotInventoryStateRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .build(), o)))));

    tools.add(tool("open_bot_inventory",
      "Open the bot's own player inventory.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot")),
      List.of("instance_id", "bot_id"),
      authed((_, args) ->
        MCPService.<BotOpenInventoryResponse>grpc(o -> botService.openInventory(
          BotOpenInventoryRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .build(), o)))));

    tools.add(tool("close_bot_container",
      "Close the currently open container/inventory window for a bot.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "bot_id", prop("string", "Profile UUID of the bot")),
      List.of("instance_id", "bot_id"),
      authed((_, args) ->
        MCPService.<BotCloseContainerResponse>grpc(o -> botService.closeContainer(
          BotCloseContainerRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setBotId(str(args, "bot_id"))
            .build(), o)))));

    // === Server Config Tools ===
    tools.add(tool("get_server_info",
      "Get server-level configuration, settings definitions, settings pages, and registered plugins.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<ServerInfoResponse>grpc(o -> serverService.getServerInfo(ServerInfoRequest.newBuilder().build(), o)))));

    tools.add(tool("update_server_config_entry",
      "Update a single server configuration entry by namespace and key.",
      Map.of(
        "namespace", prop("string", "Setting namespace"),
        "key", prop("string", "Setting key"),
        "value", Map.of("description", "The new value")),
      List.of("namespace", "key", "value"),
      authed((_, args) ->
        MCPService.<ServerUpdateConfigEntryResponse>grpc(o -> serverService.updateServerConfigEntry(
          ServerUpdateConfigEntryRequest.newBuilder()
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .setValue(toProtoValue(args.get("value")))
            .build(), o)))));

    // === User Management Tools ===
    tools.add(tool("list_users",
      "List all users in the SoulFire system.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<UserListResponse>grpc(o -> userService.listUsers(UserListRequest.newBuilder().build(), o)))));

    tools.add(tool("create_user",
      "Create a new user account.",
      Map.of(
        "username", prop("string", "Username (3-32 chars, lowercase alphanumeric with dashes)"),
        "email", prop("string", "Email address"),
        "role", prop("string", "Role: ADMIN or USER")),
      List.of("username", "email", "role"),
      authed((_, args) ->
        MCPService.<UserCreateResponse>grpc(o -> userService.createUser(
          UserCreateRequest.newBuilder()
            .setUsername(str(args, "username"))
            .setEmail(str(args, "email"))
            .setRole(UserRole.valueOf(str(args, "role")))
            .build(), o)))));

    tools.add(tool("delete_user",
      "Delete a user account. Cannot delete yourself or the root user.",
      Map.of("id", prop("string", "UUID of the user to delete")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<UserDeleteResponse>grpc(o -> userService.deleteUser(
          UserDeleteRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    tools.add(tool("get_user_info",
      "Get detailed information about a specific user.",
      Map.of("id", prop("string", "UUID of the user")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<UserInfoResponse>grpc(o -> userService.getUserInfo(
          UserInfoRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    tools.add(tool("generate_user_api_token",
      "Generate an API token for a user that can be used for authentication.",
      Map.of("id", prop("string", "UUID of the user")),
      List.of("id"),
      authed((_, args) ->
        MCPService.<GenerateUserAPITokenResponse>grpc(o -> userService.generateUserAPIToken(
          GenerateUserAPITokenRequest.newBuilder().setId(str(args, "id")).build(), o)))));

    // === Client/Self Tools ===
    tools.add(tool("get_client_data",
      "Get information about the currently authenticated user including permissions and server info.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<ClientDataResponse>grpc(o -> clientService.getClientData(ClientDataRequest.newBuilder().build(), o)))));

    tools.add(tool("generate_api_token",
      "Generate a new API token for the current user.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<GenerateAPITokenResponse>grpc(o -> clientService.generateAPIToken(GenerateAPITokenRequest.newBuilder().build(), o)))));

    // === Metrics Tools ===
    tools.add(tool("get_instance_metrics",
      "Get time-series metrics for an instance including bot counts, network traffic, tick duration, health, and position data.",
      Map.of("instance_id", prop("string", "UUID of the instance")),
      List.of("instance_id"),
      authed((_, args) ->
        MCPService.<GetInstanceMetricsResponse>grpc(o -> metricsService.getInstanceMetrics(
          GetInstanceMetricsRequest.newBuilder().setInstanceId(str(args, "instance_id")).build(), o)))));

    tools.add(tool("get_server_metrics",
      "Get server-level system metrics including CPU, memory, threads, GC, and aggregate bot statistics.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<GetServerMetricsResponse>grpc(o -> metricsService.getServerMetrics(GetServerMetricsRequest.newBuilder().build(), o)))));

    // === Logs Tools ===
    tools.add(tool("get_previous_logs",
      "Retrieve recent historical log entries from the server's log buffer.",
      Map.of(
        "scope_type", prop("string", "Scope: 'global', 'instance', 'bot', or 'personal'"),
        "instance_id", prop("string", "Instance UUID (for instance/bot scope)"),
        "bot_id", prop("string", "Bot UUID (for bot scope)"),
        "count", prop("integer", "Number of log entries to retrieve (max 300)")),
      List.of("scope_type", "count"),
      authed((_, args) ->
        MCPService.<PreviousLogResponse>grpc(o -> logService.getPrevious(
          PreviousLogRequest.newBuilder()
            .setScope(buildLogScope(args))
            .setCount(num(args, "count"))
            .build(), o)))));

    // === Instance Metadata Tools ===
    tools.add(tool("get_instance_metadata",
      "Get persistent metadata for an instance.",
      Map.of("instance_id", prop("string", "UUID of the instance")),
      List.of("instance_id"),
      authed((_, args) ->
        MCPService.<GetInstanceMetadataResponse>grpc(o -> instanceService.getInstanceMetadata(
          GetInstanceMetadataRequest.newBuilder().setInstanceId(str(args, "instance_id")).build(), o)))));

    tools.add(tool("set_instance_metadata_entry",
      "Set a persistent metadata entry for an instance.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "namespace", prop("string", "Metadata namespace"),
        "key", prop("string", "Metadata key"),
        "value", Map.of("description", "The value to set")),
      List.of("instance_id", "namespace", "key", "value"),
      authed((_, args) ->
        MCPService.<SetInstanceMetadataEntryResponse>grpc(o -> instanceService.setInstanceMetadataEntry(
          SetInstanceMetadataEntryRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .setValue(toProtoValue(args.get("value")))
            .build(), o)))));

    tools.add(tool("delete_instance_metadata_entry",
      "Delete a persistent metadata entry from an instance.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "namespace", prop("string", "Metadata namespace"),
        "key", prop("string", "Metadata key")),
      List.of("instance_id", "namespace", "key"),
      authed((_, args) ->
        MCPService.<DeleteInstanceMetadataEntryResponse>grpc(o -> instanceService.deleteInstanceMetadataEntry(
          DeleteInstanceMetadataEntryRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .build(), o)))));

    // === Account Metadata Tools ===
    tools.add(tool("get_account_metadata",
      "Get persistent metadata for a Minecraft account within an instance.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "account_id", prop("string", "Profile UUID of the account")),
      List.of("instance_id", "account_id"),
      authed((_, args) ->
        MCPService.<GetAccountMetadataResponse>grpc(o -> instanceService.getAccountMetadata(
          GetAccountMetadataRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setAccountId(str(args, "account_id"))
            .build(), o)))));

    tools.add(tool("set_account_metadata_entry",
      "Set a persistent metadata entry for a Minecraft account.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "account_id", prop("string", "Profile UUID of the account"),
        "namespace", prop("string", "Metadata namespace"),
        "key", prop("string", "Metadata key"),
        "value", Map.of("description", "The value to set")),
      List.of("instance_id", "account_id", "namespace", "key", "value"),
      authed((_, args) ->
        MCPService.<SetAccountMetadataEntryResponse>grpc(o -> instanceService.setAccountMetadataEntry(
          SetAccountMetadataEntryRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setAccountId(str(args, "account_id"))
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .setValue(toProtoValue(args.get("value")))
            .build(), o)))));

    tools.add(tool("delete_account_metadata_entry",
      "Delete a persistent metadata entry from a Minecraft account.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "account_id", prop("string", "Profile UUID of the account"),
        "namespace", prop("string", "Metadata namespace"),
        "key", prop("string", "Metadata key")),
      List.of("instance_id", "account_id", "namespace", "key"),
      authed((_, args) ->
        MCPService.<DeleteAccountMetadataEntryResponse>grpc(o -> instanceService.deleteAccountMetadataEntry(
          DeleteAccountMetadataEntryRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setAccountId(str(args, "account_id"))
            .setNamespace(str(args, "namespace"))
            .setKey(str(args, "key"))
            .build(), o)))));

    // === Script Tools ===
    var scriptService = new ScriptServiceImpl(soulFireServer);
    tools.add(tool("list_scripts",
      "List all scripts in an instance.",
      Map.of("instance_id", prop("string", "UUID of the instance")),
      List.of("instance_id"),
      authed((_, args) ->
        MCPService.<ListScriptsResponse>grpc(o -> scriptService.listScripts(
          ListScriptsRequest.newBuilder().setInstanceId(str(args, "instance_id")).build(), o)))));

    tools.add(tool("get_script",
      "Get a specific script including its full node graph.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "script_id", prop("string", "UUID of the script")),
      List.of("instance_id", "script_id"),
      authed((_, args) ->
        MCPService.<GetScriptResponse>grpc(o -> scriptService.getScript(
          GetScriptRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setScriptId(str(args, "script_id"))
            .build(), o)))));

    tools.add(tool("delete_script",
      "Delete a script from an instance.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "script_id", prop("string", "UUID of the script")),
      List.of("instance_id", "script_id"),
      authed((_, args) ->
        MCPService.<DeleteScriptResponse>grpc(o -> scriptService.deleteScript(
          DeleteScriptRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setScriptId(str(args, "script_id"))
            .build(), o)))));

    tools.add(tool("deactivate_script",
      "Deactivate a running script.",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "script_id", prop("string", "UUID of the script")),
      List.of("instance_id", "script_id"),
      authed((_, args) ->
        MCPService.<DeactivateScriptResponse>grpc(o -> scriptService.deactivateScript(
          DeactivateScriptRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setScriptId(str(args, "script_id"))
            .build(), o)))));

    tools.add(tool("get_script_status",
      "Get the execution status of a script (active, executing node, activation count).",
      Map.of(
        "instance_id", prop("string", "UUID of the instance"),
        "script_id", prop("string", "UUID of the script")),
      List.of("instance_id", "script_id"),
      authed((_, args) ->
        MCPService.<GetScriptStatusResponse>grpc(o -> scriptService.getScriptStatus(
          GetScriptStatusRequest.newBuilder()
            .setInstanceId(str(args, "instance_id"))
            .setScriptId(str(args, "script_id"))
            .build(), o)))));

    tools.add(tool("get_node_types",
      "Get all available script node types with their metadata, ports, and categories.",
      Map.of(), List.of(),
      authed((_, _) ->
        MCPService.<GetNodeTypesResponse>grpc(o -> scriptService.getNodeTypes(GetNodeTypesRequest.newBuilder().build(), o)))));

    tools.add(tool("get_registry_data",
      "Get Minecraft registry data (blocks, entities, items, biomes) for use in scripts.",
      Map.of("registry", prop("string", "Optional: specific registry ('blocks', 'entities', 'items', 'biomes')")),
      List.of(),
      authed((_, args) -> {
        var builder = GetRegistryDataRequest.newBuilder();
        if (args.containsKey("registry")) {
          builder.setRegistry(str(args, "registry"));
        }
        return MCPService.<GetRegistryDataResponse>grpc(o -> scriptService.getRegistryData(builder.build(), o));
      })));

    var pluginServerName = InProcessServerBuilder.generateName();
    var pluginServerBuilder = InProcessServerBuilder
      .forName(pluginServerName)
      .directExecutor();
    var pluginRegistrations = SoulFireAPI.pluginApis().rpcServices();
    for (var registration : pluginRegistrations) {
      var permissionChecked = ServerInterceptors.intercept(
        registration.service(),
        new PluginRpcPermissionInterceptor(soulFireServer, registration)
      );
      pluginServerBuilder.addService(ServerInterceptors.intercept(
        permissionChecked,
        pluginMcpUserInterceptor()
      ));
    }
    try {
      pluginMcpServer = pluginServerBuilder.build().start();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to start the internal plugin MCP transport", e);
    }
    pluginMcpChannel = InProcessChannelBuilder
      .forName(pluginServerName)
      .directExecutor()
      .build();

    for (var registration : pluginRegistrations) {
      addPluginTools(tools, registration);
    }

    // Build the MCP server
    McpServer.async(transportProvider)
      .serverInfo("SoulFire", BuildData.VERSION)
      .instructions("""
        SoulFire is a Minecraft bot automation tool. You can use these tools to:
        - Manage instances (create, delete, start, stop, pause, configure)
        - Control bots (movement, rotation, inventory, hotbar)
        - Execute commands (Brigadier command format)
        - Manage accounts and proxies
        - View logs and metrics
        - Manage users and permissions
        - Work with visual scripts (node-based automation)

        Authentication: Include a Bearer token in the Authorization header.
        All IDs are UUIDs. Use list_instances and get_bot_list to discover available IDs.
        """)
      .tools(tools)
      .build();
  }

  public void shutdown() {
    pluginMcpChannel.shutdownNow();
    pluginMcpServer.shutdownNow();
  }

  private ServerInterceptor pluginMcpUserInterceptor() {
    return new ServerInterceptor() {
      @Override
      public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
        io.grpc.ServerCall<ReqT, RespT> call,
        Metadata headers,
        io.grpc.ServerCallHandler<ReqT, RespT> next
      ) {
        var userToken = headers.get(PLUGIN_MCP_USER);
        SoulFireUser user = userToken == null ? null : pluginMcpUsers.get(userToken);
        if (user == null) {
          call.close(
            io.grpc.Status.UNAUTHENTICATED.withDescription("Plugin MCP user context is missing"),
            new Metadata()
          );
          return new io.grpc.ServerCall.Listener<>() {};
        }
        return Contexts.interceptCall(
          Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, user),
          call,
          headers,
          next
        );
      }
    };
  }

  private void addPluginTools(
    List<McpServerFeatures.AsyncToolSpecification> tools,
    PluginRpcRegistration registration
  ) {
    for (var method : registration.descriptor().getMethods()) {
      var docs = method.getOptions().hasExtension(ApiDocsProto.apiMethod)
        ? method.getOptions().getExtension(ApiDocsProto.apiMethod)
        : ApiMethodDocs.getDefaultInstance();
      if (!docs.getExposeToMcp()) {
        continue;
      }

      var fullMethodName = io.grpc.MethodDescriptor.generateFullMethodName(
        registration.descriptor().getFullName(),
        method.getName()
      );
      var grpcMethod = registration.service().getMethods().stream()
        .map(io.grpc.ServerMethodDefinition::getMethodDescriptor)
        .filter(candidate -> candidate.getFullMethodName().equals(fullMethodName))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
          "Registered plugin RPC is missing its gRPC method: " + fullMethodName
        ));
      var properties = new LinkedHashMap<String, Object>();
      var required = new ArrayList<String>();
      for (var field : method.getInputType().getFields()) {
        properties.put(field.getJsonName(), pluginMcpFieldSchema(field, new HashSet<>()));
        if (field.getOptions().getExtension(FieldBehaviorProto.fieldBehavior)
          .contains(FieldBehavior.REQUIRED)) {
          required.add(field.getJsonName());
        }
      }
      if (docs.getMcpRequiresConfirmation()) {
        properties.put(
          "confirm",
          prop("boolean", "Confirm this mutating or destructive plugin action.")
        );
        required.add("confirm");
      }

      var toolName = "plugin_%s_%s_%s".formatted(
        registration.owner().id().replace('-', '_'),
        snakeCase(registration.descriptor().getName()),
        snakeCase(method.getName())
      );
      var displayName = docs.getDisplayName().isBlank()
        ? method.getName()
        : docs.getDisplayName();
      var description = docs.getDescription().isBlank()
        ? displayName
        : "%s %s".formatted(displayName, docs.getDescription());
      tools.add(tool(
        toolName,
        description,
        properties,
        required,
        authed((exchange, args) -> invokePluginTool(
          (SoulFireUser) exchange.transportContext().get(USER_CONTEXT_KEY),
          method,
          grpcMethod,
          docs.getMcpRequiresConfirmation(),
          args
        ))
      ));
    }
  }

  private Mono<McpSchema.CallToolResult> invokePluginTool(
    SoulFireUser user,
    Descriptors.MethodDescriptor method,
    io.grpc.MethodDescriptor<?, ?> grpcMethod,
    boolean requiresConfirmation,
    Map<String, Object> arguments
  ) {
    if (requiresConfirmation && !Boolean.TRUE.equals(arguments.get("confirm"))) {
      return Mono.just(errorResult("This plugin action requires confirm=true."));
    }

    var requestArguments = new LinkedHashMap<>(arguments);
    requestArguments.remove("confirm");
    var request = DynamicMessage.newBuilder(method.getInputType());
    try {
      JsonFormat.parser().merge(GsonInstance.GSON.toJson(requestArguments), request);
    } catch (InvalidProtocolBufferException e) {
      return Mono.just(errorResult("Invalid plugin tool arguments: " + e.getMessage()));
    }

    var userToken = UUID.randomUUID().toString();
    pluginMcpUsers.put(userToken, user);
    var metadata = new Metadata();
    metadata.put(PLUGIN_MCP_USER, userToken);
    var channel = ClientInterceptors.intercept(
      pluginMcpChannel,
      MetadataUtils.newAttachHeadersInterceptor(metadata)
    );

    return invokePluginUnary(channel, grpcMethod, request.build())
      .doFinally(_ -> pluginMcpUsers.remove(userToken));
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Mono<McpSchema.CallToolResult> invokePluginUnary(
    io.grpc.Channel channel,
    io.grpc.MethodDescriptor<?, ?> grpcMethod,
    DynamicMessage request
  ) {
    return Mono.create(sink -> {
      ClientCall call = channel.newCall((io.grpc.MethodDescriptor) grpcMethod, CallOptions.DEFAULT);
      var responseSent = new java.util.concurrent.atomic.AtomicBoolean();
      sink.onCancel(() -> call.cancel("MCP plugin call cancelled", null));
      ClientCalls.asyncUnaryCall(call, request, new StreamObserver<MessageOrBuilder>() {
        @Override
        public void onNext(MessageOrBuilder value) {
          if (!responseSent.compareAndSet(false, true)) {
            return;
          }
          try {
            var json = JsonFormat.printer().includingDefaultValueFields().print(value);
            sink.success(new McpSchema.CallToolResult(
              List.of(new McpSchema.TextContent(json)),
              false,
              null,
              Map.of()
            ));
          } catch (InvalidProtocolBufferException e) {
            sink.success(errorResult(e.getMessage()));
          }
        }

        @Override
        public void onError(Throwable throwable) {
          if (responseSent.compareAndSet(false, true)) {
            var message = throwable instanceof StatusRuntimeException status
              ? status.getStatus().getCode() + ": " + status.getStatus().getDescription()
              : throwable.getMessage();
            sink.success(errorResult(message));
          }
        }

        @Override
        public void onCompleted() {
          if (responseSent.compareAndSet(false, true)) {
            sink.success(errorResult("Plugin RPC completed without a response."));
          }
        }
      });
    });
  }

  private static Map<String, Object> pluginMcpFieldSchema(
    Descriptors.FieldDescriptor field,
    Set<String> visiting
  ) {
    Map<String, Object> valueSchema;
    if (field.isMapField()) {
      var mapValue = field.getMessageType().findFieldByName("value");
      valueSchema = new LinkedHashMap<>();
      valueSchema.put("type", "object");
      valueSchema.put("additionalProperties", pluginMcpScalarSchema(mapValue, visiting));
    } else {
      valueSchema = pluginMcpScalarSchema(field, visiting);
    }

    if (field.isRepeated() && !field.isMapField()) {
      return Map.of(
        "type", "array",
        "items", valueSchema
      );
    }
    return valueSchema;
  }

  private static Map<String, Object> pluginMcpScalarSchema(
    Descriptors.FieldDescriptor field,
    Set<String> visiting
  ) {
    var schema = new LinkedHashMap<String, Object>();
    switch (field.getJavaType()) {
      case BOOLEAN -> schema.put("type", "boolean");
      case INT, LONG -> schema.put("type", "integer");
      case FLOAT, DOUBLE -> schema.put("type", "number");
      case STRING -> schema.put("type", "string");
      case BYTE_STRING -> {
        schema.put("type", "string");
        schema.put("contentEncoding", "base64");
      }
      case ENUM -> {
        schema.put("type", "string");
        schema.put(
          "enum",
          field.getEnumType().getValues().stream()
            .map(Descriptors.EnumValueDescriptor::getName)
            .toList()
        );
      }
      case MESSAGE -> {
        var descriptor = field.getMessageType();
        if (!visiting.add(descriptor.getFullName())) {
          schema.put("type", "object");
          break;
        }
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var child : descriptor.getFields()) {
          properties.put(child.getJsonName(), pluginMcpFieldSchema(child, visiting));
          if (child.getOptions().getExtension(FieldBehaviorProto.fieldBehavior)
            .contains(FieldBehavior.REQUIRED)) {
            required.add(child.getJsonName());
          }
        }
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
          schema.put("required", required);
        }
        visiting.remove(descriptor.getFullName());
      }
    }

    if (field.getOptions().hasExtension(ApiDocsProto.apiField)) {
      var format = field.getOptions().getExtension(ApiDocsProto.apiField).getFormat();
      if (!format.isBlank()) {
        schema.put("format", format);
      }
    }
    return schema;
  }

  private static String snakeCase(String value) {
    var result = new StringBuilder(value.length() + 8);
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (Character.isUpperCase(character) && index > 0) {
        result.append('_');
      }
      result.append(Character.toLowerCase(character));
    }
    return result.toString();
  }

  // === Helper Methods ===

  private static Map<String, Object> prop(String type, String description) {
    return Map.of("type", type, "description", description);
  }

  private static Map<String, Object> arrayProp(String description) {
    return Map.of(
      "type", "array",
      "description", description,
      "items", Map.of("type", "string"));
  }

  private static String str(Map<String, Object> args, String key) {
    return (String) args.get(key);
  }

  private static int num(Map<String, Object> args, String key) {
    return ((Number) args.get(key)).intValue();
  }

  private static boolean bool(Map<String, Object> args, String key) {
    return (Boolean) args.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<String> strList(Map<String, Object> args, String key) {
    return (List<String>) args.get(key);
  }

  private static void ifPresent(Map<String, Object> args, String key, Consumer<Object> action) {
    if (args.containsKey(key)) {
      action.accept(args.get(key));
    }
  }

  private static CommandScope buildCommandScope(Map<String, Object> args) {
    var scopeType = str(args, "scope_type");
    var scopeBuilder = CommandScope.newBuilder();
    switch (scopeType) {
      case "global" -> scopeBuilder.setGlobal(GlobalCommandScope.newBuilder().build());
      case "instance" -> scopeBuilder.setInstance(InstanceCommandScope.newBuilder()
        .setInstanceId(str(args, "instance_id")).build());
      case "bot" -> scopeBuilder.setBot(BotCommandScope.newBuilder()
        .setInstanceId(str(args, "instance_id"))
        .setBotId(str(args, "bot_id")).build());
      default -> throw new IllegalArgumentException("Invalid scope_type: " + scopeType);
    }
    return scopeBuilder.build();
  }

  private static LogScope buildLogScope(Map<String, Object> args) {
    var scopeType = str(args, "scope_type");
    var builder = LogScope.newBuilder();
    switch (scopeType) {
      case "global" -> builder.setGlobal(GlobalLogScope.newBuilder().build());
      case "instance" -> builder.setInstance(InstanceLogScope.newBuilder()
        .setInstanceId(str(args, "instance_id")).build());
      case "bot" -> builder.setBot(BotLogScope.newBuilder()
        .setInstanceId(str(args, "instance_id"))
        .setBotId(str(args, "bot_id")).build());
      case "personal" -> builder.setPersonal(PersonalLogScope.newBuilder().build());
      default -> throw new IllegalArgumentException("Invalid scope_type: " + scopeType);
    }
    return builder.build();
  }

  private static McpServerFeatures.AsyncToolSpecification tool(
    String name,
    String description,
    Map<String, Object> properties,
    List<String> required,
    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest,
      Mono<McpSchema.CallToolResult>> handler) {
    var inputSchema = Map.<String, Object>of(
      "type", "object",
      "properties", properties,
      "required", required
    );
    return new McpServerFeatures.AsyncToolSpecification(
      McpSchema.Tool.builder(name, inputSchema)
        .description(description)
        .build(),
      handler
    );
  }

  private BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest,
    Mono<McpSchema.CallToolResult>> authed(
    BiFunction<McpAsyncServerExchange, Map<String, Object>,
      Mono<McpSchema.CallToolResult>> action) {
    return (exchange, args) -> {
      var transportContext = exchange.transportContext();
      var user = (SoulFireUser) transportContext.get(USER_CONTEXT_KEY);
      if (user == null) {
        return Mono.just(errorResult("Authentication required. Include a Bearer token in the Authorization header."));
      }
      var grpcContext = Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, user);
      var previousContext = grpcContext.attach();
      try {
        return action.apply(exchange, args.arguments());
      } catch (StatusRuntimeException e) {
        return Mono.just(errorResult(e.getStatus().getCode() + ": " + e.getStatus().getDescription()));
      } catch (Exception e) {
        return Mono.just(errorResult(e.getMessage()));
      } finally {
        grpcContext.detach(previousContext);
      }
    };
  }

  @SuppressWarnings("deprecation")
  private static <T extends MessageOrBuilder> Mono<McpSchema.CallToolResult> grpc(
    Consumer<StreamObserver<T>> grpcCall) {
    return Mono.create(sink -> {
      StreamObserver<T> observer = new StreamObserver<>() {
        @Override
        public void onNext(T value) {
          try {
            var json = JsonFormat.printer().includingDefaultValueFields().print(value);
            sink.success(new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false, null, Map.of()));
          } catch (Exception e) {
            sink.error(e);
          }
        }

        @Override
        public void onError(Throwable t) {
          var message = t.getMessage();
          if (t instanceof StatusRuntimeException sre) {
            message = sre.getStatus().getCode() + ": " + sre.getStatus().getDescription();
          }
          sink.success(errorResult(message));
        }

        @Override
        public void onCompleted() {
          // Response already sent in onNext
        }
      };

      try {
        grpcCall.accept(observer);
      } catch (StatusRuntimeException e) {
        sink.success(errorResult(e.getStatus().getCode() + ": " + e.getStatus().getDescription()));
      } catch (Exception e) {
        sink.success(errorResult(e.getMessage()));
      }
    });
  }

  @SuppressWarnings("deprecation")
  private static McpSchema.CallToolResult errorResult(String message) {
    return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("Error: " + message)), true, null, Map.of());
  }

  private static Value toProtoValue(Object value) {
    return switch (value) {
      case null -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
      case String s -> Value.newBuilder().setStringValue(s).build();
      case Number n -> Value.newBuilder().setNumberValue(n.doubleValue()).build();
      case Boolean b -> Value.newBuilder().setBoolValue(b).build();
      default -> Value.newBuilder().setStringValue(value.toString()).build();
    };
  }

}
