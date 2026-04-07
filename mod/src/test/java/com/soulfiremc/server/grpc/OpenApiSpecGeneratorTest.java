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
package com.soulfiremc.server.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OpenApiSpecGeneratorTest {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  @Test
  void literalPathItemStoresSlashSeparatedPathsAsSingleOpenApiKeys() {
    var paths = JSON_MAPPER.createObjectNode();

    var pathItem = OpenApiSpecGenerator.literalPathItem(
      paths,
      "/soulfire.v1.BotService/ClickContainerButton"
    );
    pathItem.putObject("post").put("operationId", "test-operation");

    assertTrue(
      paths.has("/soulfire.v1.BotService/ClickContainerButton"),
      "The full HTTP path should be stored as one OpenAPI path key"
    );
    assertFalse(
      paths.has("soulfire.v1.BotService"),
      "Slash-separated paths must not become nested objects"
    );
    assertEquals(
      "test-operation",
      paths.path("/soulfire.v1.BotService/ClickContainerButton")
        .path("post")
        .path("operationId")
        .asText()
    );
  }
}
