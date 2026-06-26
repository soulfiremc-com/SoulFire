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
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.filter.Filterable;

public final class ProxyCheckLogFilter extends AbstractFilter {
  static final String PROXY_CHECK_ACCOUNT_NAME = "ProxyCheck";
  private static Configuration registeredConfiguration;

  private ProxyCheckLogFilter() {
    super(Filter.Result.DENY, Filter.Result.NEUTRAL);
  }

  public static synchronized void register() {
    var context = (LoggerContext) LogManager.getContext(false);
    var config = context.getConfiguration();
    if (registeredConfiguration == config) {
      return;
    }

    addFilter(config);
    addFilter(config.getRootLogger());
    for (var loggerConfig : config.getLoggers().values()) {
      addFilter(loggerConfig);
    }

    for (var appender : config.getAppenders().values()) {
      if (appender instanceof Filterable filterable) {
        addFilter(filterable);
      }
    }

    registeredConfiguration = config;
    context.updateLoggers(config);
  }

  private static void addFilter(Filterable filterable) {
    var filter = new ProxyCheckLogFilter();
    filter.start();
    filterable.addFilter(filter);
  }

  static boolean shouldDeny(LogEvent event) {
    return PROXY_CHECK_ACCOUNT_NAME.equals(event.getContextData().getValue(SFLogAppender.SF_BOT_ACCOUNT_NAME));
  }

  @Override
  public Result filter(LogEvent event) {
    return shouldDeny(event) ? onMatch : onMismatch;
  }
}
