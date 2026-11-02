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
package com.soulfiremc.server.adventure;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.regex.Pattern;

/// Encodes server-authored personal components. Ordinary log strings must not use this encoder.
/// The private OSC 777 subcommand is experimental; see proto/terminal-interactions.md.
public final class TerminalComponentSerializer {
  private static final String PREFIX = "\u001b]777;soulfire;1;";
  private static final String END = "\u001b\\";
  private static final Pattern CONTROLS = Pattern.compile(
    "(?:\\x1b\\[|\\x9b)[0-?]*[ -/]*[@-~]?|(?:\\x1b[\\]PX^_]|[\\x90\\x98\\x9d-\\x9f])[\\s\\S]*?(?:\\x07|\\x1b\\\\|\\x9c|$)|\\x1b[ -/]*[0-~]?");

  private TerminalComponentSerializer() {}

  public static String serialize(Component component) {
    var result = new StringBuilder();
    SoulFireAdventure.FLATTENER.flatten(component, new FlattenerListener() {
      private final ArrayDeque<Style> styles = new ArrayDeque<>();
      private int spans;

      @Override
      public void pushStyle(Style style) {
        styles.push(styles.isEmpty() ? style : styles.peek().merge(style));
      }

      @Override
      public void popStyle(Style style) {
        styles.pop();
      }

      @Override
      public void component(String text) {
        var style = styles.isEmpty() ? Style.empty() : styles.peek();
        var metadata = metadata(style);
        var encoded = metadata.isEmpty() ? "" : Base64.getUrlEncoder().withoutPadding()
          .encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8));
        var interactive = !metadata.isEmpty() && encoded.length() <= 16384 && spans < 64;
        if (interactive) {
          spans++;
          result.append(PREFIX).append(encoded).append(END);
        }
        result.append(SoulFireAdventure.TRUE_COLOR_ANSI_SERIALIZER.serialize(Component.text(plain(text), style)));
        if (interactive) result.append(PREFIX).append("-").append(END);
      }
    });
    return result.toString();
  }

  private static JsonObject metadata(Style style) {
    var result = new JsonObject();
    var hover = style.hoverEvent();
    if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT) {
      var text = plain(SoulFireAdventure.PLAIN_MESSAGE_SERIALIZER.serialize((Component) hover.value()));
      if (!text.isEmpty() && text.length() <= 4096) result.addProperty("hover", text);
    }
    var click = style.clickEvent();
    if (click != null) {
      var action = click.action();
      var value = click.payload() instanceof ClickEvent.Payload.Text text ? text.value() : "";
      var supported = action == ClickEvent.Action.RUN_COMMAND || action == ClickEvent.Action.SUGGEST_COMMAND
        || action == ClickEvent.Action.COPY_TO_CLIPBOARD || action == ClickEvent.Action.OPEN_URL;
      if (supported && !value.isEmpty() && value.length() <= 4096 && value.codePoints().noneMatch(Character::isISOControl)
        && (action != ClickEvent.Action.OPEN_URL || safeUrl(value))) {
        var payload = new JsonObject();
        payload.addProperty("action", action.name());
        payload.addProperty("value", value);
        result.add("click", payload);
      }
    }
    return result;
  }

  private static boolean safeUrl(String value) {
    try {
      var uri = URI.create(value);
      return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        && uri.getHost() != null && uri.getRawUserInfo() == null;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static String plain(String text) {
    return CONTROLS.matcher(text).replaceAll("").replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
  }
}
