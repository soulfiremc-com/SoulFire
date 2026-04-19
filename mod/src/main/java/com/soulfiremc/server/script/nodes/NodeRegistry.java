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
package com.soulfiremc.server.script.nodes;

import com.google.gson.JsonParser;
import com.soulfiremc.server.script.*;
import com.soulfiremc.server.script.nodes.action.*;
import com.soulfiremc.server.script.nodes.ai.*;
import com.soulfiremc.server.script.nodes.collection.*;
import com.soulfiremc.server.script.nodes.constant.BooleanConstantNode;
import com.soulfiremc.server.script.nodes.constant.NumberConstantNode;
import com.soulfiremc.server.script.nodes.constant.StringConstantNode;
import com.soulfiremc.server.script.nodes.constant.Vector3ConstantNode;
import com.soulfiremc.server.script.nodes.data.*;
import com.soulfiremc.server.script.nodes.encoding.*;
import com.soulfiremc.server.script.nodes.flow.*;
import com.soulfiremc.server.script.nodes.integration.*;
import com.soulfiremc.server.script.nodes.json.*;
import com.soulfiremc.server.script.nodes.list.*;
import com.soulfiremc.server.script.nodes.logic.*;
import com.soulfiremc.server.script.nodes.math.*;
import com.soulfiremc.server.script.nodes.network.*;
import com.soulfiremc.server.script.nodes.state.*;
import com.soulfiremc.server.script.nodes.string.*;
import com.soulfiremc.server.script.nodes.trigger.*;
import com.soulfiremc.server.script.nodes.util.*;
import com.soulfiremc.server.script.nodes.variable.*;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/// Registry of all available script node types.
 /// Metadata is stored separately from node instances — nodes are pure executors.
@Slf4j
public final class NodeRegistry {
  private static final Map<String, Supplier<ScriptNode>> FACTORIES = new ConcurrentHashMap<>();
  private static final Map<String, NodeMetadata> METADATA_MAP = new ConcurrentHashMap<>();
  private static final Map<String, ScriptNode> CACHED_INSTANCES = new ConcurrentHashMap<>();
  private static final Map<String, Map<String, NodeValue>> DEFAULT_INPUTS_CACHE = new ConcurrentHashMap<>();

  static {
    // Trigger Nodes
    register(OnPreEntityTickNode.METADATA, OnPreEntityTickNode::new);
    register(OnPostEntityTickNode.METADATA, OnPostEntityTickNode::new);
    register(OnJoinNode.METADATA, OnJoinNode::new);
    register(OnBotInitNode.METADATA, OnBotInitNode::new);
    register(OnChatNode.METADATA, OnChatNode::new);
    register(OnDamageNode.METADATA, OnDamageNode::new);
    register(OnDeathNode.METADATA, OnDeathNode::new);
    register(OnIntervalNode.METADATA, OnIntervalNode::new);
    register(OnScriptInitNode.METADATA, OnScriptInitNode::new);
    register(OnScriptEndNode.METADATA, OnScriptEndNode::new);
    register(OnDisconnectNode.METADATA, OnDisconnectNode::new);
    register(OnContainerOpenNode.METADATA, OnContainerOpenNode::new);

    // Math Nodes
    register(AddNode.METADATA, AddNode::new);
    register(SubtractNode.METADATA, SubtractNode::new);
    register(MultiplyNode.METADATA, MultiplyNode::new);
    register(DivideNode.METADATA, DivideNode::new);
    register(ModuloNode.METADATA, ModuloNode::new);
    register(FormulaNode.METADATA, FormulaNode::new);
    register(RandomNode.METADATA, RandomNode::new);
    register(ClampNode.METADATA, ClampNode::new);
    register(LerpNode.METADATA, LerpNode::new);
    register(BSplineNode.METADATA, BSplineNode::new);
    register(AbsNode.METADATA, AbsNode::new);
    register(FloorNode.METADATA, FloorNode::new);
    register(CeilNode.METADATA, CeilNode::new);
    register(RoundNode.METADATA, RoundNode::new);
    register(MinNode.METADATA, MinNode::new);
    register(MaxNode.METADATA, MaxNode::new);
    register(PowNode.METADATA, PowNode::new);
    register(SqrtNode.METADATA, SqrtNode::new);
    register(SinNode.METADATA, SinNode::new);
    register(CosNode.METADATA, CosNode::new);
    register(TanNode.METADATA, TanNode::new);
    register(DistanceNode.METADATA, DistanceNode::new);

    // Logic Nodes
    register(CompareNode.METADATA, CompareNode::new);
    register(AndNode.METADATA, AndNode::new);
    register(OrNode.METADATA, OrNode::new);
    register(NotNode.METADATA, NotNode::new);
    register(XorNode.METADATA, XorNode::new);

    // String Nodes
    register(ConcatNode.METADATA, ConcatNode::new);
    register(ReplaceNode.METADATA, ReplaceNode::new);
    register(SplitNode.METADATA, SplitNode::new);
    register(SubstringNode.METADATA, SubstringNode::new);
    register(StringLengthNode.METADATA, StringLengthNode::new);
    register(StartsWithNode.METADATA, StartsWithNode::new);
    register(EndsWithNode.METADATA, EndsWithNode::new);
    register(ToLowerCaseNode.METADATA, ToLowerCaseNode::new);
    register(ToUpperCaseNode.METADATA, ToUpperCaseNode::new);
    register(TrimNode.METADATA, TrimNode::new);
    register(StringContainsNode.METADATA, StringContainsNode::new);
    register(FormatNode.METADATA, FormatNode::new);
    register(IndexOfNode.METADATA, IndexOfNode::new);
    register(RegexMatchNode.METADATA, RegexMatchNode::new);
    register(RegexReplaceNode.METADATA, RegexReplaceNode::new);

    // Collection Nodes
    register(CollectionSizeNode.METADATA, CollectionSizeNode::new);
    register(CollectionContainsNode.METADATA, CollectionContainsNode::new);
    register(CollectionIsEmptyNode.METADATA, CollectionIsEmptyNode::new);
    register(CollectionAddNode.METADATA, CollectionAddNode::new);
    register(CollectionRemoveNode.METADATA, CollectionRemoveNode::new);
    register(CollectionAddAllNode.METADATA, CollectionAddAllNode::new);
    register(CollectionRemoveAllNode.METADATA, CollectionRemoveAllNode::new);
    register(CollectionRetainAllNode.METADATA, CollectionRetainAllNode::new);
    register(CollectionToListNode.METADATA, CollectionToListNode::new);
    register(CollectionToSetNode.METADATA, CollectionToSetNode::new);
    register(ListCreateNode.METADATA, ListCreateNode::new);
    register(ListAddNode.METADATA, ListAddNode::new);
    register(ListRemoveAtNode.METADATA, ListRemoveAtNode::new);
    register(SetCreateNode.METADATA, SetCreateNode::new);
    register(MapKeysNode.METADATA, MapKeysNode::new);
    register(MapValuesNode.METADATA, MapValuesNode::new);

    // List Nodes
    register(ListLengthNode.METADATA, ListLengthNode::new);
    register(GetAtNode.METADATA, GetAtNode::new);
    register(FirstNode.METADATA, FirstNode::new);
    register(LastNode.METADATA, LastNode::new);
    register(ListContainsNode.METADATA, ListContainsNode::new);
    register(RangeNode.METADATA, RangeNode::new);
    register(JoinToStringNode.METADATA, JoinToStringNode::new);

    // Utility Nodes
    register(ToStringNode.METADATA, ToStringNode::new);
    register(ToNumberNode.METADATA, ToNumberNode::new);
    register(IsNullNode.METADATA, IsNullNode::new);
    register(IsEmptyNode.METADATA, IsEmptyNode::new);
    register(CreateVector3Node.METADATA, CreateVector3Node::new);
    register(SplitVector3Node.METADATA, SplitVector3Node::new);
    register(TimestampNode.METADATA, TimestampNode::new);

    // Action Nodes
    register(SetRotationNode.METADATA, SetRotationNode::new);
    register(LookAtNode.METADATA, LookAtNode::new);
    register(SneakNode.METADATA, SneakNode::new);
    register(SprintNode.METADATA, SprintNode::new);
    register(JumpNode.METADATA, JumpNode::new);
    register(MoveForwardNode.METADATA, MoveForwardNode::new);
    register(MoveBackwardNode.METADATA, MoveBackwardNode::new);
    register(StrafeLeftNode.METADATA, StrafeLeftNode::new);
    register(StrafeRightNode.METADATA, StrafeRightNode::new);
    register(AttackNode.METADATA, AttackNode::new);
    register(SwingHandNode.METADATA, SwingHandNode::new);
    register(UseItemNode.METADATA, UseItemNode::new);
    register(PathfindToNode.METADATA, PathfindToNode::new);
    register(PathfindAwayFromNode.METADATA, PathfindAwayFromNode::new);
    register(BreakBlockNode.METADATA, BreakBlockNode::new);
    register(PlaceBlockNode.METADATA, PlaceBlockNode::new);
    register(SelectSlotNode.METADATA, SelectSlotNode::new);
    register(SendChatNode.METADATA, SendChatNode::new);
    register(WaitNode.METADATA, WaitNode::new);
    register(PrintNode.METADATA, PrintNode::new);
    register(ClickSlotNode.METADATA, ClickSlotNode::new);
    register(DropItemNode.METADATA, DropItemNode::new);
    register(DropSlotNode.METADATA, DropSlotNode::new);
    register(OpenInventoryNode.METADATA, OpenInventoryNode::new);
    register(CloseInventoryNode.METADATA, CloseInventoryNode::new);
    register(RespawnNode.METADATA, RespawnNode::new);

    // Data Nodes
    register(GetPositionNode.METADATA, GetPositionNode::new);
    register(GetRotationNode.METADATA, GetRotationNode::new);
    register(GetHealthNode.METADATA, GetHealthNode::new);
    register(GetHungerNode.METADATA, GetHungerNode::new);
    register(GetInventoryNode.METADATA, GetInventoryNode::new);
    register(GetBlockNode.METADATA, GetBlockNode::new);
    register(FindEntityNode.METADATA, FindEntityNode::new);
    register(CanSeePositionNode.METADATA, CanSeePositionNode::new);
    register(GetEntityStateNode.METADATA, GetEntityStateNode::new);
    register(FindBlockNode.METADATA, FindBlockNode::new);
    register(GetBotsNode.METADATA, GetBotsNode::new);
    register(FilterBotsNode.METADATA, FilterBotsNode::new);
    register(GetBotByNameNode.METADATA, GetBotByNameNode::new);
    register(GetExperienceNode.METADATA, GetExperienceNode::new);
    register(GetArmorNode.METADATA, GetArmorNode::new);
    register(GetVelocityNode.METADATA, GetVelocityNode::new);
    register(GetGamemodeNode.METADATA, GetGamemodeNode::new);
    register(GetSelectedSlotNode.METADATA, GetSelectedSlotNode::new);
    register(GetBotStateNode.METADATA, GetBotStateNode::new);
    register(GetDimensionNode.METADATA, GetDimensionNode::new);
    register(GetTimeNode.METADATA, GetTimeNode::new);
    register(GetWeatherNode.METADATA, GetWeatherNode::new);
    register(GetBiomeNode.METADATA, GetBiomeNode::new);
    register(GetLightLevelNode.METADATA, GetLightLevelNode::new);
    register(GetBotInfoNode.METADATA, GetBotInfoNode::new);
    register(GetTargetBlockNode.METADATA, GetTargetBlockNode::new);
    register(GetEffectsNode.METADATA, GetEffectsNode::new);
    register(FindItemNode.METADATA, FindItemNode::new);
    register(IsContainerOpenNode.METADATA, IsContainerOpenNode::new);

    // Constant Nodes
    register(NumberConstantNode.METADATA, NumberConstantNode::new);
    register(StringConstantNode.METADATA, StringConstantNode::new);
    register(BooleanConstantNode.METADATA, BooleanConstantNode::new);
    register(Vector3ConstantNode.METADATA, Vector3ConstantNode::new);

    // Flow Control Nodes
    register(BranchNode.METADATA, BranchNode::new);
    register(SwitchNode.METADATA, SwitchNode::new);
    register(LoopNode.METADATA, LoopNode::new);
    register(ForEachNode.METADATA, ForEachNode::new);
    register(ForEachBotNode.METADATA, ForEachBotNode::new);
    register(SequenceNode.METADATA, SequenceNode::new);
    register(RepeatUntilNode.METADATA, RepeatUntilNode::new);
    register(ResultNode.METADATA, ResultNode::new);
    register(GateNode.METADATA, GateNode::new);
    register(DebounceNode.METADATA, DebounceNode::new);
    register(RateLimitNode.METADATA, RateLimitNode::new);

    // Network Nodes
    register(WebFetchNode.METADATA, WebFetchNode::new);

    // AI Nodes
    register(LLMChatNode.METADATA, LLMChatNode::new);

    // JSON Nodes
    register(JsonParseNode.METADATA, JsonParseNode::new);
    register(JsonStringifyNode.METADATA, JsonStringifyNode::new);
    register(JsonGetNode.METADATA, JsonGetNode::new);
    register(JsonSetNode.METADATA, JsonSetNode::new);
    register(JsonArrayNode.METADATA, JsonArrayNode::new);
    register(JsonObjectNode.METADATA, JsonObjectNode::new);

    // Encoding Nodes
    register(HashNode.METADATA, HashNode::new);
    register(Base64EncodeNode.METADATA, Base64EncodeNode::new);
    register(Base64DecodeNode.METADATA, Base64DecodeNode::new);
    register(EncryptNode.METADATA, EncryptNode::new);
    register(DecryptNode.METADATA, DecryptNode::new);
    register(CompressionNode.METADATA, CompressionNode::new);

    // State Nodes
    register(CacheNode.METADATA, CacheNode::new);
    register(StateNode.METADATA, StateNode::new);

    // Integration Nodes
    register(DiscordWebhookNode.METADATA, DiscordWebhookNode::new);

    // Variable Nodes
    register(SetPersistentBotVariableNode.METADATA, SetPersistentBotVariableNode::new);
    register(GetPersistentBotVariableNode.METADATA, GetPersistentBotVariableNode::new);
    register(SetSessionBotVariableNode.METADATA, SetSessionBotVariableNode::new);
    register(GetSessionBotVariableNode.METADATA, GetSessionBotVariableNode::new);
    register(SetPersistentInstanceVariableNode.METADATA, SetPersistentInstanceVariableNode::new);
    register(GetPersistentInstanceVariableNode.METADATA, GetPersistentInstanceVariableNode::new);
    register(SetSessionInstanceVariableNode.METADATA, SetSessionInstanceVariableNode::new);
    register(GetSessionInstanceVariableNode.METADATA, GetSessionInstanceVariableNode::new);
  }

  private NodeRegistry() {
    // Prevent instantiation
  }

  /// Registers a node type with its metadata and factory.
  /// Metadata is stored separately — no node instance is created during registration.
  /// Validates that the node class has no mutable instance fields (nodes are cached as singletons).
  ///
  /// @param metadata the node metadata (provides type identifier, ports, etc.)
  /// @param factory  the factory to create node instances on demand
  /// @throws IllegalStateException if the node has mutable instance fields
  public static void register(NodeMetadata metadata, Supplier<ScriptNode> factory) {
    var type = metadata.type();

    // Validate statelessness: nodes are cached singletons, so they must not have mutable fields
    // Walk the entire class hierarchy to catch fields in superclasses too
    var instance = factory.get();
    var clazz = (Class<?>) instance.getClass();
    while (clazz != null && clazz != Object.class) {
      for (var field : clazz.getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
          throw new IllegalStateException("Node " + type + " has mutable instance field '"
            + field.getName() + "' (from " + clazz.getSimpleName()
            + "). Script nodes must be stateless (cached as singletons).");
        }
      }
      clazz = clazz.getSuperclass();
    }

    METADATA_MAP.put(type, metadata);
    FACTORIES.put(type, factory);
  }

  /// Gets a cached instance of a node by its type.
  /// Node instances are stateless, so the same instance can be safely reused.
  ///
  /// @param type the node type identifier
  /// @return the cached node instance
  /// @throws IllegalArgumentException if the type is not registered
  public static ScriptNode create(String type) {
    return CACHED_INSTANCES.computeIfAbsent(type, t -> {
      var factory = FACTORIES.get(t);
      if (factory == null) {
        throw new IllegalArgumentException("Unknown node type: " + t);
      }
      return factory.get();
    });
  }

  /// Gets the metadata for a registered node type.
  ///
  /// @param type the node type identifier
  /// @return the node metadata
  /// @throws IllegalArgumentException if the type is not registered
  public static NodeMetadata getMetadata(String type) {
    var metadata = METADATA_MAP.get(type);
    if (metadata == null) {
      throw new IllegalArgumentException("Unknown node type: " + type);
    }
    return metadata;
  }

  /// Computes default input values from node metadata port definitions.
  /// Results are cached per node type since metadata is immutable.
  ///
  /// @param metadata the node metadata
  /// @return an unmodifiable map of input names to their default values
  public static Map<String, NodeValue> computeDefaultInputs(NodeMetadata metadata) {
    return DEFAULT_INPUTS_CACHE.computeIfAbsent(metadata.type(), _ -> {
      var defaults = new HashMap<String, NodeValue>();
      for (var input : metadata.inputs()) {
        if (input.type() == PortType.EXEC) {
          continue;
        }
        var defaultValueStr = input.defaultValue();
        if (defaultValueStr != null && !defaultValueStr.isEmpty()) {
          try {
            var jsonElement = JsonParser.parseString(defaultValueStr);
            var defaultValue = NodeValue.fromJson(jsonElement);
            if (!NodeValueConversion.canConvert(defaultValue, input.type())) {
              log.warn("Node {} port '{}' has incompatible default for {}: {}",
                metadata.type(), input.id(), input.type(), defaultValueStr);
            }
            defaults.put(input.id(), defaultValue);
          } catch (Exception _) {
            log.warn("Node {} port '{}' has unparseable default: {}", metadata.type(), input.id(), defaultValueStr);
            defaults.put(input.id(), NodeValue.ofString(defaultValueStr));
          }
        }
      }
      return Collections.unmodifiableMap(defaults);
    });
  }

  /// Checks if a node type is registered.
  ///
  /// @param type the node type identifier
  /// @return true if the type is registered
  public static boolean isRegistered(String type) {
    return METADATA_MAP.containsKey(type);
  }

  /// Gets all registered node types.
  ///
  /// @return set of all registered type identifiers
  public static Set<String> getRegisteredTypes() {
    return Set.copyOf(METADATA_MAP.keySet());
  }

  /// Gets the number of registered node types.
  ///
  /// @return the count of registered types
  public static int getRegisteredCount() {
    return METADATA_MAP.size();
  }

  /// Gets metadata for all registered node types.
  ///
  /// @return list of all node metadata
  public static List<NodeMetadata> getAllMetadata() {
    return List.copyOf(METADATA_MAP.values());
  }

  /// Gets metadata for all registered node types, optionally filtered.
  ///
  /// @param categoryId        optional category ID filter (null for all)
  /// @param includeDeprecated whether to include deprecated nodes
  /// @return list of matching node metadata
  public static List<NodeMetadata> getFilteredMetadata(String categoryId, boolean includeDeprecated) {
    return METADATA_MAP.values().stream()
      .filter(m -> categoryId == null || categoryId.isEmpty() || m.category().id().equals(categoryId))
      .filter(m -> includeDeprecated || !m.deprecated())
      .toList();
  }

  /// Gets all categories sorted by sort order.
  ///
  /// @return sorted list of all NodeCategory values
  public static List<NodeCategory> getAllCategories() {
    return CategoryRegistry.allSorted();
  }
}
