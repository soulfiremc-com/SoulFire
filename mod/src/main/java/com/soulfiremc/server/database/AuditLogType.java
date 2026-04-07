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
package com.soulfiremc.server.database;

public enum AuditLogType {
  EXECUTE_COMMAND,
  START_SESSION,
  PAUSE_SESSION,
  RESUME_SESSION,
  STOP_SESSION,
  AUTOMATION_START,
  AUTOMATION_PAUSE,
  AUTOMATION_RESUME,
  AUTOMATION_STOP,
  AUTOMATION_UPDATE_SETTINGS,
  AUTOMATION_APPLY_PRESET,
  AUTOMATION_RESET_MEMORY,
  AUTOMATION_RESET_COORDINATION,
}
