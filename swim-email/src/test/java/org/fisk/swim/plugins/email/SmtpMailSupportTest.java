package org.fisk.swim.plugins.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailDraft;
import org.junit.jupiter.api.Test;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

class SmtpMailSupportTest {
    @Test
    void oauthSendUsesSmtpDefaultsAndAccessToken() throws Exception {
        AtomicReference<String> hostRef = new AtomicReference<>();
        AtomicReference<Integer> portRef = new AtomicReference<>();
        AtomicReference<String> usernameRef = new AtomicReference<>();
        AtomicReference<String> secretRef = new AtomicReference<>();
        AtomicReference<MimeMessage> messageRef = new AtomicReference<>();

        var support = new SmtpMailSupport(
                (account, protocol) -> MicrosoftOAuth2Client.AcquireResult.success("access-token"),
                (host, port, username, secret, message) -> {
                    hostRef.set(host);
                    portRef.set(port);
                    usernameRef.set(username);
                    secretRef.set(secret);
                    messageRef.set(message);
                });

        var result = support.send(new EmailAccountConfig(
                "oracle",
                "Oracle Mail",
                "IMAP",
                "outlook.office365.com",
                993,
                null,
                null,
                null,
                "you@example.com",
                "IGNORED",
                "INBOX",
                null,
                null,
                "OAUTH2",
                "organizations",
                "client-id",
                null),
                new MailDraft("oracle", "boss@example.com", "Status", "Looks good"));

        assertTrue(result.success());
        assertEquals("smtp.office365.com", hostRef.get());
        assertEquals(587, portRef.get());
        assertEquals("you@example.com", usernameRef.get());
        assertEquals("access-token", secretRef.get());
        assertEquals("Status", messageRef.get().getSubject());
        assertEquals("Looks good", messageRef.get().getContent().toString().trim());
        var recipients = messageRef.get().getRecipients(jakarta.mail.Message.RecipientType.TO);
        assertEquals("boss@example.com", ((InternetAddress) recipients[0]).getAddress());
    }
}
