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
package com.soulfiremc.server.user;

import com.soulfiremc.server.SoulFireServer;
import com.soulfiremc.server.settings.server.ServerSettings;
import com.soulfiremc.server.util.SFHelpers;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.extern.slf4j.Slf4j;

import java.util.Properties;

@Slf4j
public record SmtpEmailSender(SoulFireServer soulFireServer) implements EmailSender {
  public SmtpEmailSender {
    log.info("Using smtp email sender");
  }

  @Override
  public void sendLoginCode(String recipient, String username, String code) {
    sendEmail(recipient, "Soulfire login code for %s".formatted(username), SFHelpers.getResourceAsString("email/code.html")
      .replace("{username}", username)
      .replace("{code}", code)
    );
  }

  private void sendEmail(String recipient, String subject, String body) {
    try {
      var settings = soulFireServer.settingsSource();
      var props = new Properties();
      props.put("mail.smtp.host", settings.get(ServerSettings.SMTP_HOST));
      props.put("mail.smtp.port", String.valueOf(settings.get(ServerSettings.SMTP_PORT)));
      props.put("mail.smtp.auth", "true");
      props.put("mail.smtp.ssl.trust", settings.get(ServerSettings.SMTP_HOST));

      var smtpType = settings.get(ServerSettings.SMTP_TYPE, ServerSettings.SmtpType.class);
      SFHelpers.mustSupply(() -> switch (smtpType) {
        case STARTTLS -> () -> props.put("mail.smtp.starttls.enable", "true");
        case SSL_TLS -> () -> {
          props.put("mail.smtp.socketFactory.port", String.valueOf(settings.get(ServerSettings.SMTP_PORT)));
          props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        };
        case NONE -> () -> {
        };
      });

      var session = Session.getInstance(props, new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
          return new PasswordAuthentication(
            settings.get(ServerSettings.SMTP_USERNAME),
            settings.get(ServerSettings.SMTP_PASSWORD)
          );
        }
      });

      Message message = new MimeMessage(session);
      message.setFrom(new InternetAddress(settings.get(ServerSettings.SMTP_FROM)));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));

      var mimeBodyPart = new MimeBodyPart();
      mimeBodyPart.setContent(body, "text/html; charset=utf-8");

      Multipart multipart = new MimeMultipart();
      multipart.addBodyPart(mimeBodyPart);

      message.setSubject(subject);
      message.setContent(multipart);

      Transport.send(message);

      log.info("Email sent to {}", recipient);
    } catch (MessagingException e) {
      log.error("Failed to send email", e);
    }
  }
}
