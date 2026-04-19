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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.settings.server.ServerSettings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/// Serves an OpenAPI document for SoulFire's HTTP-accessible unary RPC surface.
///
/// The document is derived from the same Armeria gRPC metadata that powers the DocService,
/// but trimmed to what OpenAPI can faithfully describe: unary JSON endpoints.
@Slf4j
public final class OpenApiService implements HttpService {
  private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
    .defaultPropertyInclusion(JsonInclude.Value.ALL_NON_NULL)
    .build();

  private final SoulFireServer soulFireServer;

  @Nullable
  private Server server;
  @Getter
  private volatile byte[] openApiDocument = "{}".getBytes(StandardCharsets.UTF_8);
  @Nullable
  private volatile Throwable generationFailure;

  public OpenApiService(SoulFireServer soulFireServer) {
    this.soulFireServer = soulFireServer;
  }

  @Override
  public void serviceAdded(ServiceConfig cfg) throws Exception {
    if (server != null) {
      if (server != cfg.server()) {
        throw new IllegalStateException("cannot be added to more than one server");
      }

      return;
    }

    server = cfg.server();

    var config = server.config();
    var virtualHosts = config.findVirtualHosts(this);
    var services = config.serviceConfigs().stream()
      .filter(serviceConfig -> virtualHosts.contains(serviceConfig.virtualHost()))
      .toList();

    try {
      var specification =
        OpenApiSpecGenerator.generate(
          services,
          soulFireServer.settingsSource().get(ServerSettings.PUBLIC_ADDRESS)
        );
      openApiDocument = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(specification);
      generationFailure = null;
    } catch (Throwable t) {
      generationFailure = t;
      log.warn("Failed to generate OpenAPI specification", t);
    }
  }

  @Override
  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
    return switch (ctx.method()) {
      case GET -> serveDocument();
      case HEAD -> HttpResponse.of(documentHeaders());
      default -> HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    };
  }

  private HttpResponse serveDocument() {
    if (generationFailure != null) {
      return HttpResponse.of(
        HttpStatus.INTERNAL_SERVER_ERROR,
        MediaType.PLAIN_TEXT_UTF_8,
        "Failed to generate OpenAPI specification: %s".formatted(generationFailure.getMessage())
      );
    }

    return HttpResponse.of(documentHeaders(), HttpData.wrap(openApiDocument));
  }

  private static ResponseHeaders documentHeaders() {
    return ResponseHeaders.builder(HttpStatus.OK)
      .contentType(MediaType.JSON_UTF_8)
      .set(HttpHeaderNames.CACHE_CONTROL, ServerCacheControl.REVALIDATED.asHeaderValue())
      .build();
  }
}
