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
package com.soulfiremc.mod.mixin.soulfire.botfixes;

import com.soulfiremc.mod.util.SFConstants;
import com.soulfiremc.server.settings.instance.BotSettings;
import com.soulfiremc.server.util.netty.NettyHelper;
import com.soulfiremc.server.util.netty.SFTrafficHandler;
import io.netty.channel.Channel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Inject actions to run after VFP
@Mixin(targets = "net.minecraft.network.Connection$1", priority = 500)
public class MixinConnection$1 {
  @Inject(method = "initChannel", at = @At("HEAD"))
  private void earlyInject(Channel channel, CallbackInfo ci) {
    var botConnection = channel.attr(SFConstants.NETTY_BOT_CONNECTION).get();
    NettyHelper.addRunnableWrapper("first_", botConnection.runnableWrapper(), channel);

    var proxyData = botConnection.transportProxy();
    if (proxyData != null) {
      var isBedrock = BedrockProtocolVersion.BEDROCK_LATEST.equals(botConnection.currentProtocolVersion());
      NettyHelper.addProxy(proxyData, channel, isBedrock);
    }

    channel.pipeline().addLast("write_timeout", new WriteTimeoutHandler(botConnection.settingsSource().get(BotSettings.WRITE_TIMEOUT)));
  }

  @Inject(method = "initChannel", at = @At("RETURN"))
  private void setReadTimeout(Channel channel, CallbackInfo ci) {
    var botConnection = channel.attr(SFConstants.NETTY_BOT_CONNECTION).get();
    channel.pipeline().replace("timeout", "timeout", new ReadTimeoutHandler(botConnection.settingsSource().get(BotSettings.READ_TIMEOUT)));
    channel.pipeline().addFirst("sf_traffic", new SFTrafficHandler());
    NettyHelper.addRunnableWrapper("last_", botConnection.runnableWrapper(), channel);
    botConnection.trackNetworkChannel(channel);
  }
}
