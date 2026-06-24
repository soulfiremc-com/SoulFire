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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MSLiveCookieHelperTest {
  @Test
  void parsesParentDomainCookiesFromNetscapeJar() {
    var cookieJar = String.join("\n",
      ".live.com\tTRUE\t/\tTRUE\t1809981143\tMSPAuth\tparent-auth",
      ".live.com\tTRUE\t/\tTRUE\t1809981143\tPPLState\tparent-state",
      "login.live.com\tFALSE\t/\tTRUE\t1809981143\t__Host-MSAAUTHP\thost-auth");

    var header = MSLiveCookieHelper.parseCookieHeader(cookieJar);

    assertEquals("MSPAuth=parent-auth; PPLState=parent-state; __Host-MSAAUTHP=host-auth", header);
  }

  @Test
  void detectsParentDomainOnlyNetscapeJar() {
    var cookieJar = String.join("\n",
      ".live.com\tTRUE\t/\tTRUE\t1809981143\tMSPAuth\tparent-auth",
      ".live.com\tTRUE\t/\tTRUE\t1809981143\tPPLState\tparent-state");

    var header = MSLiveCookieHelper.parseCookieHeader(cookieJar);

    assertEquals("MSPAuth=parent-auth; PPLState=parent-state", header);
  }

  @Test
  void parsesHttpOnlyCookiesFromNetscapeJar() {
    var cookieJar = String.join("\n",
      "# Netscape HTTP Cookie File",
      "#HttpOnly_.live.com\tTRUE\t/\tTRUE\t1809981143\tMSPProf\tprofile",
      "#HttpOnly_login.live.com\tFALSE\t/\tTRUE\t1809981143\t__Host-MSAAUTHP\thost-auth");

    var header = MSLiveCookieHelper.parseCookieHeader(cookieJar);

    assertEquals("MSPProf=profile; __Host-MSAAUTHP=host-auth", header);
  }

  @Test
  void excludesCookieEditorJsonForSiblingDomains() {
    var cookieJson = """
      [
        {"domain": ".live.com", "name": "MSPAuth", "value": "parent-auth"},
        {"domain": "login.live.com", "name": "__Host-MSAAUTHP", "value": "host-auth"},
        {"domain": "account.live.com", "name": "AccountOnly", "value": "ignored"}
      ]
      """;

    var header = MSLiveCookieHelper.parseCookieHeader(cookieJson);

    assertEquals("MSPAuth=parent-auth; __Host-MSAAUTHP=host-auth", header);
  }
}
