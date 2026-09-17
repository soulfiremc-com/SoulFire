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

import com.linecorp.armeria.server.Server;
import com.soulfiremc.grpc.generated.PovInputRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PovInputChannelTest {
  @Test
  void aSocketCannotSendInputWithoutAnAuthenticatedWatchCapability() throws Exception {
    try (var server = Server.builder().http(0).service("/pov/input", new PovServiceImpl(null).inputChannel()).build();
         var client = HttpClient.newHttpClient()) {
      server.start().get(5, TimeUnit.SECONDS);
      var ended = new CompletableFuture<Void>();
      var acknowledgements = new AtomicInteger();
      var socket = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + server.activeLocalPort() + "/pov/input"),
        new WebSocket.Listener() {
          @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
          @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            acknowledgements.incrementAndGet(); webSocket.request(1); return null;
          }
          @Override public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            ended.complete(null); return null;
          }
          @Override public void onError(WebSocket webSocket, Throwable error) { ended.complete(null); }
        }).get(5, TimeUnit.SECONDS);
      try {
        var input = PovInputRequest.newBuilder().setSessionId(UUID.randomUUID().toString()).setInputToken(UUID.randomUUID().toString())
          .setWidth(1280).setHeight(720).setSequence(1).setCaptured(true).build();
        socket.sendBinary(ByteBuffer.wrap(input.toByteArray()), true).get(5, TimeUnit.SECONDS);
        ended.get(5, TimeUnit.SECONDS);
        assertEquals(0, acknowledgements.get());
      } finally { socket.abort(); }
    }
  }
}
