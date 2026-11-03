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
package com.soulfiremc.server.settings.server;

import com.soulfiremc.grpc.generated.StringSetting;
import com.soulfiremc.server.settings.lib.SettingsObject;
import com.soulfiremc.server.settings.lib.SettingsSource;
import com.soulfiremc.server.settings.property.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ServerSettings implements SettingsObject {
  private static final String NAMESPACE = "server";
  public static final StringProperty<SettingsSource.Server> PUBLIC_ADDRESS =
    ImmutableStringProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("public-address")
      .uiName("Public address")
      .description("The address clients on the internet use to connect to this SoulFire server.\nUsed for links in E-Mails and WebDAV.")
      .defaultValue("http://127.0.0.1:38765")
      .build();
  public static final BooleanProperty<SettingsSource.Server> ALLOW_CREATING_INSTANCES =
    ImmutableBooleanProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-creating-instances")
      .uiName("Allow creating projects")
      .description("Allow (non-admin) users to create projects.")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Server> ALLOW_UPDATING_SELF_USERNAME =
    ImmutableBooleanProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-updating-self-username")
      .uiName("Allow updating self username")
      .description("Allow (non-admin) users to change their username.")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Server> ALLOW_UPDATING_SELF_EMAIL =
    ImmutableBooleanProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-updating-self-email")
      .uiName("Allow updating self email")
      .description("Allow (non-admin) users to change their email.")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Server> ALLOW_DELETING_INSTANCES =
    ImmutableBooleanProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-deleting-instances")
      .uiName("Allow deleting projects")
      .description("Allow the owner of a project to delete it.")
      .defaultValue(true)
      .build();
  public static final BooleanProperty<SettingsSource.Server> ALLOW_CHANGING_INSTANCE_META =
    ImmutableBooleanProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("allow-changing-instance-meta")
      .uiName("Allow changing project details")
      .description("Allow the owner of a project to change the project name and icon.")
      .defaultValue(true)
      .build();
  public static final ComboProperty<SettingsSource.Server> EMAIL_TYPE =
    ImmutableComboProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("email-type")
      .uiName("Email Type")
      .description("SoulFire uses this method to deliver emails.")
      .defaultValue(EmailType.CONSOLE.name())
      .addOptions(ComboProperty.optionsFromEnum(EmailType.values(), EmailType::toString, e -> switch (e) {
        case CONSOLE -> "logs";
        case SMTP -> "mails";
      }))
      .build();
  public static final StringProperty<SettingsSource.Server> SMTP_HOST =
    ImmutableStringProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-host")
      .uiName("SMTP Host")
      .description("SMTP server host to use for sending emails.")
      .defaultValue("smtp.gmail.com")
      .build();
  public static final IntProperty<SettingsSource.Server> SMTP_PORT =
    ImmutableIntProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-port")
      .uiName("SMTP Port")
      .description("SMTP server port to use for sending emails.")
      .defaultValue(587)
      .minValue(1)
      .maxValue(65535)
      .thousandSeparator(false)
      .build();
  public static final StringProperty<SettingsSource.Server> SMTP_USERNAME =
    ImmutableStringProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-username")
      .uiName("SMTP Username")
      .description("Username to use for SMTP authentication.")
      .defaultValue("")
      .build();
  public static final StringProperty<SettingsSource.Server> SMTP_PASSWORD =
    ImmutableStringProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-password")
      .uiName("SMTP Password")
      .description("Password to use for SMTP authentication.")
      .defaultValue("")
      .type(StringSetting.InputType.PASSWORD)
      .build();
  public static final ComboProperty<SettingsSource.Server> SMTP_TYPE =
    ImmutableComboProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-type")
      .uiName("SMTP Type")
      .description("Type of encryption to use for SMTP.")
      .defaultValue(SmtpType.STARTTLS.name())
      .addOptions(ComboProperty.optionsFromEnum(SmtpType.values(), SmtpType::toString, e -> switch (e) {
        case STARTTLS -> "shield-check";
        case SSL_TLS -> "shield";
        case NONE -> "shield-off";
      }))
      .build();
  public static final StringProperty<SettingsSource.Server> SMTP_FROM =
    ImmutableStringProperty.<SettingsSource.Server>builder()
      .sourceType(SettingsSource.Server.INSTANCE)
      .namespace(NAMESPACE)
      .key("smtp-from")
      .uiName("SMTP From")
      .description("Email address to use as sender for emails.")
      .defaultValue("soulfire@gmail.com")
      .build();

  @RequiredArgsConstructor
  public enum EmailType {
    CONSOLE("Console"),
    SMTP("SMTP");

    private final String uiName;

    @Override
    public String toString() {
      return uiName;
    }
  }

  @RequiredArgsConstructor
  public enum SmtpType {
    STARTTLS("STARTTLS"),
    SSL_TLS("SSL/TLS"),
    NONE("None");

    private final String uiName;

    @Override
    public String toString() {
      return uiName;
    }
  }
}
