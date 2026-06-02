package org.fisk.swim.plugins.email;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;

import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailSendResult;

import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

final class SmtpMailSupport implements MailDeliveryService {
    private final AccessTokenProvider _accessTokenProvider;
    private final SmtpTransport _transport;

    SmtpMailSupport(EmailPaths paths) {
        this((account, protocol) -> new MicrosoftOAuth2Client(paths).acquireToken(account, protocol), new JakartaMailTransport());
    }

    SmtpMailSupport(AccessTokenProvider accessTokenProvider, SmtpTransport transport) {
        _accessTokenProvider = accessTokenProvider;
        _transport = transport;
    }

    @Override
    public MailSendResult send(EmailAccountConfig account, MailDraft draft) throws Exception {
        if (allRecipientsBlank(draft)) {
            return MailSendResult.failure("At least one recipient is required");
        }
        String smtpHost = account.effectiveSmtpHost();
        if (smtpHost == null || smtpHost.isBlank()) {
            return MailSendResult.failure("SMTP host is required");
        }
        String smtpUsername = account.effectiveSmtpUsername();
        if (smtpUsername == null || smtpUsername.isBlank()) {
            return MailSendResult.failure("SMTP username is required");
        }

        String secret;
        Properties properties = baseSmtpProperties(account.effectiveSmtpPort());
        if (account.usesOAuth2()) {
            MicrosoftOAuth2Client.AcquireResult tokenResult = _accessTokenProvider.acquire(account, "SMTP");
            if (!tokenResult.hasToken()) {
                return MailSendResult.failure(tokenResult.statusMessage());
            }
            configureOAuth2(properties, "smtp");
            secret = tokenResult.accessToken();
        } else {
            secret = SecretResolver.resolve(account.passwordEnv());
            if (secret == null || secret.isBlank()) {
                return MailSendResult.failure("Missing password env '" + account.passwordEnv() + "'");
            }
        }

        Session session = Session.getInstance(properties);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(account.username()));
        setRecipients(message, jakarta.mail.Message.RecipientType.TO, draft.to());
        setRecipients(message, jakarta.mail.Message.RecipientType.CC, draft.cc());
        setRecipients(message, jakarta.mail.Message.RecipientType.BCC, draft.bcc());
        message.setSubject(draft.subject() == null ? "" : draft.subject(), StandardCharsets.UTF_8.name());
        message.setText(draft.body() == null ? "" : draft.body(), StandardCharsets.UTF_8.name());
        message.setSentDate(Date.from(Instant.now()));
        if (draft.inReplyToMessageId() != null && !draft.inReplyToMessageId().isBlank()) {
            message.setHeader("In-Reply-To", draft.inReplyToMessageId());
            message.setHeader("References", draft.inReplyToMessageId());
        }

        _transport.send(smtpHost, account.effectiveSmtpPort(), smtpUsername, secret, message);
        return MailSendResult.success("Sent mail");
    }

    static Properties baseSmtpProperties(int port) {
        Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "10000");
        properties.setProperty("mail.smtp.timeout", "20000");
        properties.setProperty("mail.smtp.writetimeout", "20000");
        properties.setProperty("mail.smtp.starttls.enable", String.valueOf(port == 587));
        properties.setProperty("mail.smtp.ssl.enable", String.valueOf(port == 465));
        return properties;
    }

    static void configureOAuth2(Properties properties, String protocol) {
        String prefix = "mail." + protocol;
        properties.setProperty(prefix + ".auth.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + ".sasl.enable", "true");
        properties.setProperty(prefix + ".sasl.mechanisms", "XOAUTH2");
        properties.setProperty(prefix + ".auth.login.disable", "true");
        properties.setProperty(prefix + ".auth.plain.disable", "true");
    }

    private static boolean allRecipientsBlank(MailDraft draft) {
        return isBlank(draft.to()) && isBlank(draft.cc()) && isBlank(draft.bcc());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void setRecipients(MimeMessage message, jakarta.mail.Message.RecipientType type, String value)
            throws Exception {
        if (isBlank(value)) {
            return;
        }
        message.setRecipients(type, InternetAddress.parse(value));
    }

    interface SmtpTransport {
        void send(String host, int port, String username, String secret, MimeMessage message) throws Exception;
    }

    interface AccessTokenProvider {
        MicrosoftOAuth2Client.AcquireResult acquire(EmailAccountConfig account, String protocol) throws Exception;
    }

    private static final class JakartaMailTransport implements SmtpTransport {
        @Override
        public void send(String host, int port, String username, String secret, MimeMessage message) throws Exception {
            try (Transport transport = message.getSession().getTransport("smtp")) {
                transport.connect(host, port, username, secret);
                transport.sendMessage(message, message.getAllRecipients());
            }
        }
    }
}
