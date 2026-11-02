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
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalComponentSerializerTest {
  private static final Pattern MARKER = Pattern.compile("\\x1b]777;soulfire;1;([A-Za-z0-9_-]+)\\x1b\\\\");

  @Test
  void matchesTheClientUtf8WireFixture() {
    var output = TerminalComponentSerializer.serialize(Component.text("text")
      .hoverEvent(HoverEvent.showText(Component.text("Héllo 🌍")))
      .clickEvent(ClickEvent.runCommand("help")));
    assertTrue(output.startsWith("\u001b]777;soulfire;1;eyJob3ZlciI6IkjDqWxsbyDwn4yNIiwiY2xpY2siOnsiYWN0aW9uIjoicnVuX2NvbW1hbmQiLCJ2YWx1ZSI6ImhlbHAifX0\u001b\\"));
  }

  @Test
  void preservesInheritedStylesAndActionsWithoutLeakingToSiblings() {
    var component = Component.empty()
      .append(Component.text("Parent ", NamedTextColor.GREEN)
        .hoverEvent(HoverEvent.showText(Component.text("Héllo 🌍")))
        .clickEvent(ClickEvent.runCommand("help"))
        .append(Component.text("child").clickEvent(ClickEvent.suggestCommand("status"))))
      .append(Component.text("plain"));
    var output = TerminalComponentSerializer.serialize(component);
    var markers = MARKER.matcher(output).results().map(match -> match.group(1)).toList();
    assertEquals(4, markers.size());
    var parent = decode(markers.getFirst());
    assertEquals("Héllo 🌍", parent.get("hover").getAsString());
    assertEquals("run_command", parent.getAsJsonObject("click").get("action").getAsString());
    var child = decode(markers.get(2));
    assertEquals("status", child.getAsJsonObject("click").get("value").getAsString());
    assertEquals("suggest_command", child.getAsJsonObject("click").get("action").getAsString());
    assertEquals("-", markers.getLast());
    assertTrue(output.contains("\u001b[38;2;85;255;85m"));
    assertTrue(output.endsWith(SoulFireAdventure.TRUE_COLOR_ANSI_SERIALIZER.serialize(Component.text("plain"))));
  }

  @Test
  void stripsLiteralControlInjectionBeforeAddingTrustedMarkers() {
    var output = TerminalComponentSerializer.serialize(Component.text("safe\u001b]777;soulfire;1;injected\u001b\\visible\u001b[2J")
      .hoverEvent(HoverEvent.showText(Component.text("tip\u001b]52;c;bad\u0007"))));
    assertFalse(output.contains("injected"));
    assertFalse(output.contains("52;"));
    assertFalse(output.contains("[2J"));
    assertTrue(output.contains("safevisible"));
    var metadata = decode(MARKER.matcher(output).results().findFirst().orElseThrow().group(1));
    assertEquals("tip", metadata.get("hover").getAsString());
  }

  @Test
  void rejectsUnsupportedActionsAndControlCharacters() {
    for (var click : List.of(ClickEvent.openFile("/tmp/file"), ClickEvent.changePage(2), ClickEvent.runCommand("help\nstop"))) {
      assertFalse(TerminalComponentSerializer.serialize(Component.text("text").clickEvent(click)).contains("777;"));
    }
    for (var value : List.of("javascript:alert(1)", "file:///tmp/a", "https://user:pass@example.com")) {
      assertFalse(TerminalComponentSerializer.serialize(Component.text("text").clickEvent(ClickEvent.openUrl(value))).contains("777;"));
    }
    for (var click : List.of(ClickEvent.openUrl("https://soulfiremc.com"), ClickEvent.copyToClipboard("héllo"))) {
      assertTrue(TerminalComponentSerializer.serialize(Component.text("text").clickEvent(click)).contains("777;"));
    }
  }

  @Test
  void boundsMetadataAndSpanCountButKeepsVisibleText() {
    var component = Component.text("head").hoverEvent(HoverEvent.showText(Component.text("x".repeat(4097))));
    assertFalse(TerminalComponentSerializer.serialize(component).contains("777;"));
    component = Component.empty();
    for (var i = 0; i < 70; i++) component = component.append(Component.text("text").clickEvent(ClickEvent.runCommand("help")));
    var output = TerminalComponentSerializer.serialize(component);
    assertEquals(128, MARKER.matcher(output).results().count());
    assertEquals(70, Pattern.compile("text").matcher(output).results().count());
  }

  private static JsonObject decode(String value) {
    return JsonParser.parseString(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)).getAsJsonObject();
  }
}
