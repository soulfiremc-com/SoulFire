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

import com.soulfiremc.grpc.generated.ClientDataRequest;
import com.soulfiremc.grpc.generated.ClientDataResponse;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.database.UserRole;
import com.soulfiremc.server.settings.lib.ServerSettingsDelegate;
import com.soulfiremc.server.settings.lib.ServerSettingsImpl;
import com.soulfiremc.server.user.AuthSystem;
import com.soulfiremc.server.user.SoulFireUser;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ClientInstallationIdTest {
  @Test
  void exposesTheInstallationWithoutChangingTheAuthenticatedUserId() throws Exception {
    var installationId = UUID.randomUUID();
    var server = mock(SoulFireServer.class);
    when(server.installationId()).thenReturn(installationId);
    when(server.settingsSource()).thenReturn(new ServerSettingsDelegate(() -> new ServerSettingsImpl(ServerSettingsImpl.Stem.EMPTY)));
    var user = mock(SoulFireUser.class);
    when(user.getUniqueId()).thenReturn(AuthSystem.ROOT_USER_ID);
    when(user.getUsername()).thenReturn("root");
    when(user.getEmail()).thenReturn("root@example.com");
    when(user.getRole()).thenReturn(UserRole.ADMIN);
    var response = new CompletableFuture<ClientDataResponse>();
    Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, user).run(() ->
      new ClientServiceImpl(server).getClientData(ClientDataRequest.getDefaultInstance(), new StreamObserver<>() {
        @Override
        public void onNext(ClientDataResponse value) {
          response.complete(value);
        }

        @Override
        public void onError(Throwable error) {
          response.completeExceptionally(error);
        }

        @Override
        public void onCompleted() {
        }
      }));
    var result = response.get(10, TimeUnit.SECONDS);
    assertEquals(installationId.toString(), result.getServerInfo().getInstallationId());
    assertEquals(AuthSystem.ROOT_USER_ID.toString(), result.getId());
  }
}
