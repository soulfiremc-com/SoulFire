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

import lombok.Getter;
import net.minecrell.terminalconsole.util.LoggerNamePatternSelector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternMatch;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

@Getter
public final class SFLogAppender extends AbstractAppender {
  public static final String SF_INSTANCE_ID = "sf-instance-id";
  public static final String SF_INSTANCE_NAME = "sf-instance-name";
  public static final String SF_BOT_ACCOUNT_ID = "sf-bot-account-id";
  public static final String SF_BOT_ACCOUNT_NAME = "sf-bot-account-name";
  public static final String SF_SCRIPT_ID = "sf-script-id";
  public static final String SF_SKIP_PUBLISHING = "sf-skip-publishing";
  public static final String SF_SKIP_LOCAL_APPENDERS = "sf-skip-local-appenders";
  public static final SFLogAppender INSTANCE = new SFLogAppender();
  private static final AtomicInteger LOG_COUNTER = new AtomicInteger(0);
  private static final LoggerNamePatternSelector SELECTOR = LoggerNamePatternSelector.createSelector(
    "%highlight{%minecraftFormatting{%msg}%ex}{FATAL=red, ERROR=red, WARN=yellow, INFO=normal, DEBUG=cyan, TRACE=black}",
    new PatternMatch[]{
      new PatternMatch("com.soulfiremc.", "%highlight{%minecraftFormatting{%msg}%ex}{FATAL=red, ERROR=red, WARN=yellow, INFO=normal, DEBUG=cyan, TRACE=black}"),
    },
    true,
    false,
    false,
    ((LoggerContext) LogManager.getContext(false)).getConfiguration()
  );
  private final List<Consumer<SFLogEvent>> logConsumers = new CopyOnWriteArrayList<>();
  private final QueueWithMaxSize<SFLogEvent> logs = new QueueWithMaxSize<>(300); // Keep max 300 logs

  private SFLogAppender() {
    super("SFLogAppender", null, null, false, Property.EMPTY_ARRAY);
  }

  @Override
  public void start() {
    if (isStarted()) {
      return;
    }

    super.start();
    ((Logger) LogManager.getRootLogger()).addAppender(this);
  }

  @Override
  public void append(LogEvent event) {
    if (event.getContextData().containsKey(SF_SKIP_PUBLISHING) || ProxyCheckLogFilter.shouldDeny(event)) {
      return;
    }

    var formattedBuilder = new StringBuilder();
    for (var formatter : SELECTOR.getFormatters(event)) {
      formatter.format(event, formattedBuilder);
    }

    var formatted = formattedBuilder.toString();
    if (formatted.isBlank()) {
      return;
    }

    publishLogEvent(SFLogEvent.fromEvent(event, formatted));
  }

  public void publishLogEvent(SFLogEvent event) {
    for (var consumer : logConsumers) {
      consumer.accept(event);
    }

    logs.add(event);
  }

  public record SFLogEvent(
    String id,
    String message,
    long timestamp,
    @Nullable String level,
    @Nullable String loggerName,
    @Nullable UUID instanceId,
    @Nullable UUID botAccountId,
    @Nullable UUID scriptId,
    @Nullable String instanceName,
    @Nullable String botAccountName
  ) {
    public static SFLogEvent fromEvent(LogEvent event, String formatted) {
      return new SFLogEvent(
        event.getTimeMillis() + "-" + LOG_COUNTER.getAndIncrement(),
        formatted,
        event.getTimeMillis(),
        event.getLevel().name(),
        Objects.requireNonNullElse(event.getLoggerName(), event.getLoggerFqcn()),
        UUIDHelper.tryParseUniqueIdOrNull(event.getContextData().getValue(SF_INSTANCE_ID)),
        UUIDHelper.tryParseUniqueIdOrNull(event.getContextData().getValue(SF_BOT_ACCOUNT_ID)),
        UUIDHelper.tryParseUniqueIdOrNull(event.getContextData().getValue(SF_SCRIPT_ID)),
        event.getContextData().getValue(SF_INSTANCE_NAME),
        event.getContextData().getValue(SF_BOT_ACCOUNT_NAME)
      );
    }
  }

  public static class QueueWithMaxSize<E> {
    private final int maxSize;
    private final Queue<E> queue;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public QueueWithMaxSize(int maxSize) {
      this.maxSize = maxSize;
      this.queue = new ArrayBlockingQueue<>(maxSize);
    }

    public boolean add(E element) {
      lock.writeLock().lock();
      try {
        if (queue.size() >= maxSize) {
          queue.poll(); // Remove the oldest element if max size is reached
        }

        return queue.add(element);
      } finally {
        lock.writeLock().unlock();
      }
    }

    public List<E> getNewest(int amount) {
      if (amount > maxSize) {
        throw new IllegalArgumentException("Amount is bigger than max size!");
      }

      lock.readLock().lock();
      try {
        var list = new ArrayList<>(queue);
        var size = list.size();
        var start = size - amount;

        if (start < 0) {
          start = 0;
        }

        return list.subList(start, size);
      } finally {
        lock.readLock().unlock();
      }
    }
  }
}
