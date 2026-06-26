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
package com.soulfiremc.shared;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.lookup.Interpolator;
import org.apache.logging.log4j.core.lookup.StrLookup;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;

public class SFInfoPlaceholder implements StrLookup {
  public static void register() {
    LoggerContext context = (LoggerContext) LogManager.getContext(false);
    Configuration config = context.getConfiguration();
    ((Interpolator) config.getStrSubstitutor().getVariableResolver()).getStrLookupMap().put("soulfire", new SFInfoPlaceholder());
  }

  @Override
  public @Nullable String lookup(String key) {
    return null;
  }

  @Override
  public @Nullable String lookup(LogEvent event, String key) {
    if ("context_info".equals(key)) {
      var builder = new StringBuilder();

      var contextData = event.getContextData();
      var usedData = new HashMap<String, String>();
      if (contextData.containsKey(SFLogAppender.SF_INSTANCE_NAME)) {
        usedData.put("instance", contextData.getValue(SFLogAppender.SF_INSTANCE_NAME));
      }
      if (contextData.containsKey(SFLogAppender.SF_BOT_ACCOUNT_NAME)) {
        usedData.put("bot", contextData.getValue(SFLogAppender.SF_BOT_ACCOUNT_NAME));
      }

      if (!usedData.isEmpty()) {
        builder.append(" [");
        builder.append(usedData
          .values()
          .stream()
          .filter(Objects::nonNull)
          .collect(Collectors.joining(": ")));
        builder.append("]");
      }

      return builder.toString();
    } else {
      return null;
    }
  }
}
