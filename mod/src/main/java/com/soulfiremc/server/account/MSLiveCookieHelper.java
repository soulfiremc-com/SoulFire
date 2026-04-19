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
package com.soulfiremc.server.account;

import com.google.gson.JsonArray;
import com.soulfiremc.server.util.structs.GsonInstance;

/// Utility for parsing various cookie input formats into a Cookie header string
/// suitable for Microsoft Live authentication.
///
/// Supports three cookie input formats:
/// - Netscape cookie jar (tab-separated, e.g. from curl or browser export)
/// - Cookie Editor JSON (array of objects with domain/name/value)
/// - Raw cookie header string (semicolon-separated name=value pairs)
public final class MSLiveCookieHelper {
  private MSLiveCookieHelper() {}

  /// Parses a cookie input string (in any supported format) into a Cookie header value.
  public static String parseCookieHeader(String cookieInput) {
    var input = stripEnclosingQuotes(cookieInput.strip());
    if (input.isEmpty()) {
      throw new IllegalArgumentException("Empty cookie input");
    }

    if (looksLikeCookieJar(input)) {
      var header = parseCookieJar(input);
      if (header.isEmpty()) {
        throw new IllegalArgumentException("No valid login.live.com cookies found in cookie jar");
      }
      return header;
    }

    if (looksLikeCookieEditorJson(input)) {
      var header = parseCookieEditorJson(input);
      if (header.isEmpty()) {
        throw new IllegalArgumentException("No valid login.live.com cookies found in JSON");
      }
      return header;
    }

    if (looksLikeRawCookieHeader(input)) {
      var header = normalizeCookieHeader(input);
      if (header.isEmpty()) {
        throw new IllegalArgumentException("No usable cookies found in header");
      }
      return header;
    }

    throw new IllegalArgumentException("Unrecognized cookie format");
  }

  private static boolean looksLikeCookieJar(String input) {
    return input.contains("\t")
      && (input.contains("__Host-MSAAUTHP") || input.contains("login.live.com"));
  }

  private static boolean looksLikeRawCookieHeader(String input) {
    return input.contains("__Host-MSAAUTHP=")
      || input.contains("MSPRequ=")
      || input.contains("MSPOK=")
      || input.contains("PPLState=");
  }

  private static boolean looksLikeCookieEditorJson(String input) {
    var t = input.strip();
    return t.startsWith("[") && t.endsWith("]")
      && t.contains("\"domain\"") && t.contains("\"name\"") && t.contains("\"value\"");
  }

  private static String parseCookieJar(String cookieJar) {
    var out = new StringBuilder();
    for (var line : cookieJar.split("\\R")) {
      var l = line.strip();
      if (l.isEmpty() || l.startsWith("#")) {
        continue;
      }

      var parts = l.split("\t");
      if (parts.length < 7) {
        continue;
      }

      var domain = stripDomain(parts[0].strip());
      if (!isLiveDomain(domain)) {
        continue;
      }

      var name = parts[5].strip();
      var value = parts[6].strip();
      if (name.isEmpty() || value.isEmpty()) {
        continue;
      }

      appendCookie(out, name, value);
    }

    return out.toString();
  }

  private static String parseCookieEditorJson(String cookieJson) {
    JsonArray arr;
    try {
      arr = GsonInstance.GSON.fromJson(cookieJson, JsonArray.class);
    } catch (Exception _) {
      return "";
    }
    if (arr == null || arr.isEmpty()) {
      return "";
    }

    var out = new StringBuilder();
    for (var el : arr) {
      if (!el.isJsonObject()) {
        continue;
      }
      var obj = el.getAsJsonObject();
      if (!obj.has("name") || !obj.has("value")) {
        continue;
      }

      var name = obj.get("name").getAsString();
      var value = obj.get("value").getAsString();
      if (name == null || name.isEmpty() || value == null || value.isEmpty()) {
        continue;
      }

      if (obj.has("domain")) {
        try {
          var domain = stripDomain(obj.get("domain").getAsString());
          if (!isLiveDomain(domain)) {
            continue;
          }
        } catch (Exception _) {
        }
      }

      appendCookie(out, name.strip(), value.strip());
    }

    return out.toString();
  }

  private static String normalizeCookieHeader(String headerInput) {
    var t = stripEnclosingQuotes(headerInput.strip());
    t = t.replace("\r\n", "\n").replace("\n", "; ");
    t = t.replaceFirst("^(?i)cookie:\\s*", "");

    var out = new StringBuilder();
    for (var piece : t.split(";")) {
      var p = piece.strip();
      if (p.isEmpty()) {
        continue;
      }
      var idx = p.indexOf('=');
      if (idx <= 0) {
        continue;
      }
      var name = p.substring(0, idx).strip();
      var value = p.substring(idx + 1).strip();
      if (name.isEmpty() || value.isEmpty()) {
        continue;
      }
      appendCookie(out, name, value);
    }
    return out.toString();
  }

  private static void appendCookie(StringBuilder out, String name, String value) {
    if (!out.isEmpty()) {
      out.append("; ");
    }
    out.append(name).append('=').append(value);
  }

  private static boolean isLiveDomain(String domain) {
    return "login.live.com".equals(domain) || domain.endsWith(".login.live.com");
  }

  private static String stripEnclosingQuotes(String v) {
    var t = v.strip();
    if (t.length() >= 2) {
      var first = t.charAt(0);
      var last = t.charAt(t.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return t.substring(1, t.length() - 1).strip();
      }
    }
    return t;
  }

  private static String stripDomain(String domain) {
    var d = domain.strip();
    if (d.startsWith("http://")) {
      d = d.substring("http://".length());
    }
    if (d.startsWith("https://")) {
      d = d.substring("https://".length());
    }
    var slash = d.indexOf('/');
    if (slash >= 0) {
      d = d.substring(0, slash);
    }
    return d.strip();
  }
}
