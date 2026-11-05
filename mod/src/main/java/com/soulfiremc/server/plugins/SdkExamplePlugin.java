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
package com.soulfiremc.server.plugins;

import com.soulfiremc.grpc.generated.plugin.example.v1.*;
import com.soulfiremc.server.api.*;
import com.soulfiremc.server.bot.ControlResource;
import com.soulfiremc.server.bot.ControlTask;
import com.soulfiremc.server.task.BotTaskContext;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Opt-in reference plugin for the SDK plugin RPC and code-generation workflow.
@InternalPluginClass
public final class SdkExamplePlugin extends InternalPlugin {
  public SdkExamplePlugin() {
    super(new PluginInfo(
      "example",
      "1.0.0",
      "Reference implementation for Soulfire plugin-defined RPCs",
      "SoulFireMC",
      "AGPL-3.0",
      "https://soulfiremc.com/docs/sdk/plugins"
    ));
  }

  @Override
  public boolean isAvailable() {
    return Boolean.getBoolean("sf.sdk.example-plugin");
  }

  @Override
  protected void onLoad(PluginContext context) {
    var readPermission = context.permissions().register(PluginPermission.instance(
      "read",
      "Use the example API",
      "Calls the unary and server-streaming methods of the SDK example plugin.",
      PluginPermission.Risk.READ
    ));
    context.sdk().register(new PluginSdkMetadata(
      "*",
      1,
      PluginSdkMetadata.Stability.EXPERIMENTAL,
      Optional.of("@soulfiremc/plugin-example"),
      Optional.of("soulfire-plugin-example"),
      Optional.empty(),
      Optional.of("https://soulfiremc.com/docs/sdk/plugins"),
      Optional.of("https://github.com/AlexProgrammerDE/SoulFire"),
      List.of(),
      List.of()
    ));
    var tickEvents = context.events().register(
      Tick.getDefaultInstance(),
      readPermission
    );
    context.tasks().register(new EchoTaskProvider(), readPermission);
    context.rpc().register(new ExampleService(tickEvents));
  }

  private static final class EchoTaskProvider
    implements PluginBotTaskProvider<EchoRequest, EchoResponse> {
    @Override
    public EchoRequest inputPrototype() {
      return EchoRequest.getDefaultInstance();
    }

    @Override
    public EchoResponse resultPrototype() {
      return EchoResponse.getDefaultInstance();
    }

    @Override
    public Set<ControlResource> resources(EchoRequest input) {
      return Set.of(ControlResource.CHAT);
    }

    @Override
    public BotTaskExecution start(BotTaskContext context, EchoRequest input) {
      return new BotTaskExecution(
        ControlTask.once(() -> {}),
        CompletableFuture.completedFuture(
          EchoResponse.newBuilder().setMessage(input.getMessage()).build()
        )
      );
    }
  }

  private static final class ExampleService extends ExamplePluginServiceGrpc.ExamplePluginServiceImplBase {
    private final PluginEventRegistration<Tick> tickEvents;

    private ExampleService(PluginEventRegistration<Tick> tickEvents) {
      this.tickEvents = tickEvents;
    }

    @Override
    public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
      responseObserver.onNext(EchoResponse.newBuilder().setMessage(request.getMessage()).build());
      responseObserver.onCompleted();
    }

    @Override
    public void watchTicks(WatchTicksRequest request, StreamObserver<Tick> responseObserver) {
      for (var sequence = 1; sequence <= request.getCount(); sequence++) {
        var tick = Tick.newBuilder().setSequence(sequence).build();
        responseObserver.onNext(tick);
        tickEvents.publish(
          PluginEventTarget.instance(UUID.fromString(request.getInstanceId())),
          tick
        );
      }
      responseObserver.onCompleted();
    }
  }
}
