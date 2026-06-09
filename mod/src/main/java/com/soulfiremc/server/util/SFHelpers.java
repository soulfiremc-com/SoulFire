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
package com.soulfiremc.server.util;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.soulfiremc.server.SoulFireScheduler;
import com.soulfiremc.server.util.structs.CancellationCollector;
import com.soulfiremc.server.util.structs.SafeCloseable;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jspecify.annotations.Nullable;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.slf4j.MDC;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.regex.Pattern;

@Slf4j
public final class SFHelpers {
  public static final char[] ILLEGAL_FILE_CHARACTERS = new char[]{'/', '\n', '\r', '\t', '\u0000', '\f', '`', '?', '*', '\\', '<', '>', '|', '"', ':'};
  private static final Pattern RESERVED_WINDOWS_FILENAMES = Pattern.compile(".*\\.|(?:COM|CLOCK\\$|CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?", Pattern.CASE_INSENSITIVE);

  private SFHelpers() {}

  public static byte[][] split(byte[] data, byte separator) {
    var count = 0;
    for (var b : data) {
      if (b == separator) {
        count++;
      }
    }

    if (count == 0) {
      return new byte[][]{data};
    }

    var result = new byte[count + 1][];
    var last = 0;
    var index = 0;
    for (var i = 0; i < data.length; i++) {
      if (data[i] == separator) {
        result[index++] = Arrays.copyOfRange(data, last, i);
        last = i + 1;
      }
    }

    result[index] = Arrays.copyOfRange(data, last, data.length);
    return result;
  }

  public static OptionalInt parseInt(String s) {
    try {
      return OptionalInt.of(Integer.parseInt(s));
    } catch (NumberFormatException _) {
      return OptionalInt.empty();
    }
  }

  public static BooleanSupplier not(BooleanSupplier supplier) {
    return () -> !supplier.getAsBoolean();
  }

  public static void writeIfNeeded(Path path, String content) throws IOException {
    if (Files.exists(path)) {
      var existingContent = Files.readString(path);
      if (!existingContent.equals(content)) {
        Files.writeString(path, content);
      }
    } else {
      Files.createDirectories(path.getParent());
      Files.writeString(path, content);
    }
  }

  public static <S> void maxFutures(SoulFireScheduler scheduler, int maxConcurrency, Collection<S> source, Consumer<S> task, CancellationCollector cancellationCollector) {
    if (maxConcurrency < 1) {
      throw new IllegalArgumentException("maxConcurrency must be at least 1");
    }

    var semaphore = new Semaphore(maxConcurrency);
    var phaser = new Phaser(1);
    try {
      for (var item : source) {
        if (!acquirePermit(semaphore, cancellationCollector)) {
          break;
        }

        if (cancellationCollector.cancelled()) {
          semaphore.release();
          break;
        }

        phaser.register();
        var taskFinished = new AtomicBoolean();
        Runnable finishTask = () -> {
          if (taskFinished.compareAndSet(false, true)) {
            semaphore.release();
            phaser.arriveAndDeregister();
          }
        };

        var scheduledTask = SoulFireScheduler.FinalizableRunnable.withFinalizer(() -> {
          try {
            if (!cancellationCollector.cancelled()) {
              task.accept(item);
            }
          } finally {
            finishTask.run();
          }
        }, finishTask);

        try {
          scheduler.execute(scheduledTask);
        } catch (Throwable t) {
          finishTask.run();
          throw t;
        }
      }
    } finally {
      awaitScheduledTasks(phaser, cancellationCollector);
    }
  }

  private static boolean acquirePermit(Semaphore semaphore, CancellationCollector cancellationCollector) {
    while (!cancellationCollector.cancelled()) {
      try {
        if (semaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
          return true;
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        cancellationCollector.cancelAll();
        return false;
      }
    }

    return false;
  }

  private static void awaitScheduledTasks(Phaser phaser, CancellationCollector cancellationCollector) {
    var phase = phaser.arriveAndDeregister();
    while (phaser.getRegisteredParties() > 0 && !cancellationCollector.cancelled()) {
      try {
        phase = phaser.awaitAdvanceInterruptibly(phase, 100, TimeUnit.MILLISECONDS);
      } catch (TimeoutException _) {
        // Re-check cancellation periodically instead of blocking forever.
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        cancellationCollector.cancelAll();
        return;
      }
    }
  }

  public static int getRandomInt(int min, int max) {
    if (min > max) {
      throw new IllegalArgumentException("min must not be greater than max");
    }

    if (min == max) {
      return min;
    }

    return ThreadLocalRandom.current().nextInt(min, max);
  }

  public static <E> E getRandomEntry(List<E> list) {
    if (list.isEmpty()) {
      throw new IllegalArgumentException("List must not be empty");
    }

    return list.get(ThreadLocalRandom.current().nextInt(list.size()));
  }

  public static boolean isNewer(String currentVersion, String checkVersion) {
    currentVersion = currentVersion.replace("-SNAPSHOT", "");
    checkVersion = checkVersion.replace("-SNAPSHOT", "");

    try {
      var currentVersionData =
        Arrays.stream(currentVersion.split("\\.")).mapToInt(Integer::parseInt).toArray();
      var checkVersionData =
        Arrays.stream(checkVersion.split("\\.")).mapToInt(Integer::parseInt).toArray();

      var i = 0;
      for (var version : checkVersionData) {
        if (i == currentVersionData.length) {
          return true;
        }

        if (version > currentVersionData[i]) {
          return true;
        } else if (version < currentVersionData[i]) {
          return false;
        }

        i++;
      }
    } catch (NumberFormatException e) {
      log.error("Error while parsing version!", e);
    }

    return false;
  }

  public static String getResourceAsString(String path) {
    return new String(getResourceAsBytes(path), StandardCharsets.UTF_8);
  }

  public static byte[] getResourceAsBytes(String path) {
    try {
      return Objects.requireNonNull(SFHelpers.class.getClassLoader().getResourceAsStream(path), path).readAllBytes();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get file", e);
    }
  }

  public static <T> T make(T object, Consumer<? super T> consumer) {
    consumer.accept(object);
    return object;
  }

  public static <T> T make(Supplier<T> consumer) {
    return consumer.get();
  }

  public static String stripForChat(String s) {
    var builder = new StringBuilder(s.length());
    for (var c : s.toCharArray()) {
      if (isAllowedChatCharacter(c)) {
        builder.append(c);
      }
    }

    return builder.toString();
  }

  public static boolean isAllowedChatCharacter(char c) {
    return c != 167 && c >= ' ' && c != 127;
  }

  public static void mustSupply(Supplier<Runnable> supplier) {
    supplier.get().run();
  }

  public static String sanitizeFileName(String name) {
    for (var c : ILLEGAL_FILE_CHARACTERS) {
      name = name.replace(c, '_');
    }

    name = name.replaceAll("[./\"]", "_");

    if (RESERVED_WINDOWS_FILENAMES.matcher(name).matches()) {
      name = "_" + name + "_";
    }

    return name;
  }

  public static byte[] md5Hash(String str) {
    try {
      var md = MessageDigest.getInstance("MD5");
      md.update(str.getBytes(StandardCharsets.UTF_8));
      return md.digest();
    } catch (NoSuchAlgorithmException ex) {
      throw new AcmeProtocolException("Could not compute hash", ex);
    }
  }

  public static <T> Optional<T> awaitResultPredicate(Iterator<T> iterator, Predicate<T> function) {
    while (iterator.hasNext()) {
      var next = iterator.next();
      if (function.test(next)) {
        return Optional.of(next);
      }
    }

    return Optional.empty();
  }

  public static void deleteDirectory(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }

    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  public static SafeCloseable smartMDCCloseable(String key, String val) {
    var oldVal = MDC.get(key);
    MDC.put(key, val);
    return () -> {
      if (oldVal == null) {
        MDC.remove(key);
      } else {
        MDC.put(key, oldVal);
      }
    };
  }

  public static <T> SafeCloseable smartThreadLocalCloseable(ThreadLocal<T> threadLocal, T val) {
    var oldVal = threadLocal.get();
    threadLocal.set(val);
    return () -> threadLocal.set(oldVal);
  }

  public static String changeExtension(String filename, String newExt) {
    var dotIndex = filename.lastIndexOf('.');
    if (dotIndex == -1) {
      return filename + "." + newExt; // No extension found
    }
    return filename.substring(0, dotIndex) + "." + newExt;
  }

  public static BufferedImage toBufferedImage(MapItemSavedData map) {
    var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
    for (var x = 0; x < 128; x++) {
      for (var y = 0; y < 128; y++) {
        image.setRGB(x, y, MapColor.getColorFromPackedId(map.colors[x + y * 128]));
      }
    }

    return image;
  }

  public static net.kyori.adventure.text.Component nativeToAdventure(Component component) {
    return GsonComponentSerializer.gson()
      .deserializeFromTree(ComponentSerialization.CODEC
        .encodeStart(JsonOps.INSTANCE, component).getOrThrow());
  }

  public static <K, V> Map<K, V> nullToEmptyMap(@Nullable Map<K, V> settings) {
    return Objects.requireNonNullElse(settings, Map.of());
  }
}
