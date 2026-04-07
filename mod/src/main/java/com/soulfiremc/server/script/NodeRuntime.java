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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/// Runtime environment for script node execution.
/// Provides minimal API surface for nodes - just what they need to execute their logic.
/// Execution machinery (output storage, cancellation, event listeners) is hidden.
public interface NodeRuntime {
  /// Gets the per-script state store for stateful nodes.
  /// State is scoped to the script execution and cleaned up on deactivation.
  ///
  /// @return the script state store
  ScriptStateStore stateStore();

  /// Gets the SoulFire instance for accessing bots and game state.
  ///
  /// @return the instance manager
  InstanceManager instance();

  /// Gets the scheduler for async operations.
  ///
  /// @return the instance scheduler
  SoulFireScheduler scheduler();

  /// Gets the Reactor scheduler for reactive timing operations.
  ///
  /// @return the reactor scheduler
  default Scheduler reactorScheduler() {
    return Schedulers.parallel();
  }

  /// Logs a message from script execution.
  /// Used by Print node and other debugging nodes.
  ///
  /// @param level   the log level (debug, info, warn, error)
  /// @param message the message to log
  void log(String level, String message);

  /// Returns whether this execution is running synchronously on the tick thread.
  /// When true, action nodes should execute game API calls directly instead of
  /// deferring via the bot control task system.
  ///
  /// @return true if executing synchronously on the tick thread
  default boolean isTickSynchronous() {
    return false;
  }

  /// Triggers downstream execution along a named exec handle.
  /// Only functional during reactive engine execution; self-driving nodes
  /// (LoopNode, ForEachNode, etc.) use this to iterate.
  ///
  /// @param handle  the exec output port ID to follow (e.g., "exec_loop")
  /// @param outputs the outputs to merge into the execution context
  /// @return a Mono that completes when downstream execution finishes
  default Mono<Void> executeDownstream(String handle, Map<String, NodeValue> outputs) {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Sets the check result flag for condition-based loops.
  /// Called by ResultNode to communicate a boolean back to the enclosing loop.
  ///
  /// @param value the boolean result
  default void setCheckResult(boolean value) {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Returns whether setCheckResult was called since the last reset.
  /// Used by RepeatUntilNode to detect missing ResultNode in check chains.
  ///
  /// @return true if a ResultNode set the check result
  default boolean wasCheckResultSet() {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Gets and resets the check result flag.
  /// Called by loop nodes (e.g., RepeatUntilNode) after executing a check chain.
  ///
  /// @return the check result before reset
  default boolean getAndResetCheckResult() {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Resets all data-only node trigger flags, allowing them to re-execute
  /// with fresh upstream values. Called by loop nodes before evaluating
  /// check chains so that data-only condition nodes get re-evaluated.
  default void resetDataNodeTriggers() {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Pushes a new check context for nested loop support.
  /// Called by loop nodes before starting their check chain evaluation.
  default void pushCheckContext() {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }

  /// Pops the current check context after loop iteration completes.
  /// Called by loop nodes when they are done with their check chain.
  default void popCheckContext() {
    throw new UnsupportedOperationException("Only available during reactive engine execution");
  }
}
