package org.fisk.swim.plugins.email;

import java.io.IOException;
import java.sql.SQLException;

import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.mail.MailPluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EmailPluginSupport {
    private static final Logger LOG = LoggerFactory.getLogger(EmailPluginSupport.class);
    private static SQLiteMailClient _client;

    private EmailPluginSupport() {
    }

    static synchronized void install(SwimPluginContext context) {
        shutdown();
        try {
            _client = new SQLiteMailClient(EmailPaths.fromUserHome(),
                    new DefaultMailSyncAdapterFactory(),
                    new SmtpMailSupport(EmailPaths.fromUserHome()),
                    false);
            MailPluginRegistry.register(_client);
        } catch (IOException | SQLException e) {
            LOG.error("Failed to initialize mail plugin", e);
            throw new RuntimeException("Failed to initialize mail plugin: " + rootMessage(e), e);
        } catch (RuntimeException e) {
            LOG.error("Failed to initialize mail plugin", e);
            throw new RuntimeException("Failed to initialize mail plugin: " + rootMessage(e), e);
        }
    }

    static synchronized void shutdown() {
        MailPluginRegistry.clear();
        if (_client != null) {
            _client.close();
            _client = null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
