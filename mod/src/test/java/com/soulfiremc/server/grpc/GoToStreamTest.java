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

import com.soulfiremc.grpc.generated.BlockPosition;
import com.soulfiremc.grpc.generated.BotTask;
import com.soulfiremc.grpc.generated.BotTaskFailure;
import com.soulfiremc.grpc.generated.BotTaskStatus;
import com.soulfiremc.grpc.generated.GoToRequest;
import com.soulfiremc.grpc.generated.PathfindGoal;
import com.soulfiremc.grpc.generated.PathfindProgress;
import com.soulfiremc.server.InstanceManager;
import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.account.MinecraftAccount;
import com.soulfiremc.server.bot.BotConnection;
import com.soulfiremc.server.bot.BotControlLeaseManager;
import com.soulfiremc.server.settings.lib.InstanceSettingsDelegate;
import com.soulfiremc.server.task.BotTaskManager;
import com.soulfiremc.server.user.SoulFireUser;
import com.soulfiremc.test.utils.TestBootstrap;
import io.grpc.Context;
import io.grpc.stub.ServerCallStreamObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoToStreamTest {
  @BeforeAll
  static void bootstrapMinecraft() {
    TestBootstrap.bootstrapForTest();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enrichesTheInitialSnapshotEvenWhenTheTaskHasAlreadyFailed(boolean failed) throws Exception {
    var server = mock(SoulFireServer.class);
    var instance = mock(InstanceManager.class);
    var bot = mock(BotConnection.class);
    var minecraft = mock(Minecraft.class);
    minecraft.level = mock(ClientLevel.class);
    minecraft.player = mock(LocalPlayer.class);
    when(minecraft.level.dimension()).thenReturn(Level.OVERWORLD);
    when(minecraft.player.position()).thenReturn(Vec3.ZERO);
    when(bot.minecraft()).thenReturn(minecraft);
    var settings = mock(InstanceSettingsDelegate.class);
    var instanceId = UUID.randomUUID();
    var botId = UUID.randomUUID();
    var taskId = UUID.randomUUID();
    when(settings.accounts()).thenReturn(Map.of(botId, mock(MinecraftAccount.class)));
    when(instance.settingsSource()).thenReturn(settings);
    when(instance.botConnections()).thenReturn(Map.of(botId, bot));
    when(instance.botControlLeaseManager()).thenReturn(new BotControlLeaseManager());
    when(server.getInstance(instanceId)).thenReturn(Optional.of(instance));
    var manager = mock(BotTaskManager.class);
    when(server.botTaskManager()).thenReturn(manager);
    var user = mock(SoulFireUser.class);
    var initial = BotTask.newBuilder().setTaskId(taskId.toString()).setRevision(1)
      .setStatus(failed ? BotTaskStatus.BOT_TASK_STATUS_FAILED : BotTaskStatus.BOT_TASK_STATUS_RUNNING);
    if (failed) initial.setFailure(BotTaskFailure.newBuilder().setMessage(UUID.randomUUID().toString()));
    var task = initial.build();
    when(manager.start(any(), eq(user))).thenReturn(task);
    when(manager.get(taskId)).thenReturn(failed ? task : task.toBuilder().setRevision(2)
      .setStatus(BotTaskStatus.BOT_TASK_STATUS_COMPLETED).build());
    var subscription = mock(AutoCloseable.class);
    when(manager.subscribe(any())).thenReturn(subscription);
    ServerCallStreamObserver<PathfindProgress> observer = mock();
    var request = GoToRequest.newBuilder().setInstanceId(instanceId.toString()).setBotId(botId.toString())
      .setGoal(PathfindGoal.newBuilder().setBlock(PathfindGoal.BlockGoal.newBuilder()
        .setPosition(BlockPosition.newBuilder().setX(1)))).build();
    Context.current().withValue(ServerRPCConstants.USER_CONTEXT_KEY, user).run(() ->
      new BotLiveServiceImpl(server).goTo(request, observer));
    var frames = ArgumentCaptor.forClass(PathfindProgress.class);
    verify(observer, atLeastOnce()).onNext(frames.capture());
    var first = frames.getAllValues().getFirst();
    assertEquals(taskId.toString(), first.getActionId());
    if (failed) assertEquals(task.getFailure().getMessage(), first.getError());
    assertTrue(first.hasPosition());
    assertTrue(first.hasDistanceRemaining());
    verify(observer).onCompleted();
    verify(subscription).close();
  }
}
