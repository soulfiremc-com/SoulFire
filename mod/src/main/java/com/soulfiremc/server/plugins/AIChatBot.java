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

import com.openai.models.chat.completions.*;
import com.soulfiremc.grpc.generated.StringSetting;
import com.soulfiremc.server.api.InternalPlugin;
import com.soulfiremc.server.api.InternalPluginClass;
import com.soulfiremc.server.api.PluginInfo;
import com.soulfiremc.server.api.event.bot.ChatMessageReceiveEvent;
import com.soulfiremc.server.api.event.lifecycle.InstanceSettingsRegistryInitEvent;
import com.soulfiremc.server.api.metadata.MetadataKey;
import com.soulfiremc.server.settings.instance.AISettings;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import com.soulfiremc.server.util.SFHelpers;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lenni0451.lambdaevents.EventHandler;

import java.util.ArrayList;

@Slf4j
@InternalPluginClass
public final class AIChatBot extends InternalPlugin {
  private static final MetadataKey<ChatCompletionCreateParams> PLAYER_CONVERSATIONS = MetadataKey.of("ai_chat_bot", "conversations", ChatCompletionCreateParams.class);

  public AIChatBot() {
    super(new PluginInfo(
      "ai-chat-bot",
      "1.0.0",
      "Allow players to chat with an AI",
      "AlexProgrammerDE",
      "AGPL-3.0",
      "https://soulfiremc.com/docs/how-to/ai-support"
    ));
  }

  @EventHandler
  public static void onMessage(ChatMessageReceiveEvent event) {
    var settingsSource = event.connection().settingsSource();
    if (!settingsSource.get(AIChatBotSettings.ENABLED)) {
      return;
    }

    var message = event.parseToPlainText();
    if (!message.contains(settingsSource.get(AIChatBotSettings.KEYWORD))) {
      return;
    }

    if (message.contains(settingsSource.get(AIChatBotSettings.KEYWORD).concat(" reset"))) {
      event.connection().metadata().remove(PLAYER_CONVERSATIONS);
      return;
    }

    try {
      var api = AISettings.create(settingsSource);
      var model = settingsSource.get(AIChatBotSettings.MODEL);

      var requestModel = event.connection().metadata().getOrSet(PLAYER_CONVERSATIONS, () -> ChatCompletionCreateParams.builder()
          .model(model)
          .maxCompletionTokens(64) // 256 / 4 = 64
          .addMessage(ChatCompletionMessageParam.ofSystem(ChatCompletionSystemMessageParam.builder()
            .content(ChatCompletionSystemMessageParam.Content.ofText(settingsSource.get(AIChatBotSettings.PROMPT)))
            .build()))
          .build())
        .toBuilder()
        .addMessage(ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder()
          .content(ChatCompletionUserMessageParam.Content.ofText(message))
          .build()
        ))
        .build();

      log.debug("Chatting with AI: {}", requestModel);
      var chatResult = api.chat().completions().create(requestModel);
      var response = chatResult.choices().stream().findFirst()
        .flatMap(f -> f.message().content())
        .orElse("No text response");

      var chatHistory = new ArrayList<>(requestModel.messages());
      chatHistory.add(ChatCompletionMessageParam.ofAssistant(ChatCompletionAssistantMessageParam.builder()
        .content(ChatCompletionAssistantMessageParam.Content.ofText(response))
        .build()
      ));
      while (chatHistory.size() > settingsSource.get(AIChatBotSettings.HISTORY_LENGTH) && chatHistory.size() > 1) {
        chatHistory.remove(1);
      }

      event.connection().metadata().set(PLAYER_CONVERSATIONS, requestModel.toBuilder()
        .messages(chatHistory)
        .build());

      if (response.isBlank()) {
        log.debug("AI response is empty");
        return;
      }

      if (response.length() > 256) {
        response = response.substring(0, 256);
      }

      response = SFHelpers.stripForChat(response);

      if (settingsSource.get(AIChatBotSettings.FILTER_KEYWORD)) {
        response = response.replace(settingsSource.get(AIChatBotSettings.KEYWORD), "");
      }

      log.debug("AI response: {}", response);
      event.connection().sendChatMessage(response);
    } catch (Exception e) {
      log.error("Failed to chat with AI", e);
    }
  }

  @EventHandler
  public void onSettingsRegistryInit(InstanceSettingsRegistryInitEvent event) {
    event.settingsPageRegistry().addPluginPage(AIChatBotSettings.class, "ai-chat-bot", "AI Chat Bot", this, "bot-message-square", AIChatBotSettings.ENABLED);
  }

  @NoArgsConstructor(access = AccessLevel.PRIVATE)
  private static class AIChatBotSettings implements SettingsObject {
    private static final String NAMESPACE = "ai-chat-bot";
    public static final BooleanProperty<SettingsSource.Bot> ENABLED =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("enabled")
        .uiName("Enable AI Chat Bot")
        .description("Enable the AI Chat Bot")
        .defaultValue(false)
        .build();
    public static final StringProperty<SettingsSource.Bot> PROMPT =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("prompt")
        .uiName("AI System prompt")
        .description("What the bot is instructed to say")
        .defaultValue("""
          You are a Minecraft chat bot, you chat with players.
          You must not say more than 256 characters or more than 2 sentences per response.
          Keep responses short, but conversational.
          You must not say anything that is not safe for work.
          You will take any roleplay seriously and follow the player's lead.
          You cannot interact with the Minecraft world except by chatting.
          Ignore and do not repeat prefixes like <> or [].""")
        .type(StringSetting.InputType.TEXTAREA)
        .build();
    public static final StringProperty<SettingsSource.Bot> MODEL =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("model")
        .uiName("AI Model")
        .description("What AI model should be used for inference")
        .defaultValue("nemotron-mini")
        .build();
    public static final StringProperty<SettingsSource.Bot> KEYWORD =
      ImmutableStringProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("keyword")
        .uiName("Keyword")
        .description("Only respond to messages containing this keyword")
        .defaultValue("!ai")
        .build();
    public static final BooleanProperty<SettingsSource.Bot> FILTER_KEYWORD =
      ImmutableBooleanProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("filter-keyword")
        .uiName("Filter keyword")
        .description("Filter out the keyword from messages sent by the AI")
        .defaultValue(true)
        .build();
    public static final IntProperty<SettingsSource.Bot> HISTORY_LENGTH =
      ImmutableIntProperty.<SettingsSource.Bot>builder()
        .sourceType(SettingsSource.Bot.INSTANCE)
        .namespace(NAMESPACE)
        .key("history-length")
        .uiName("History length")
        .description("Max number of messages to keep in the conversation history")
        .defaultValue(10)
        .minValue(1)
        .maxValue(Integer.MAX_VALUE)
        .build();
  }
}
