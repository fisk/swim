package org.fisk.swim.plugins.email;

import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailPluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class EmailPluginSupport {
    private static final Logger LOG = LoggerFactory.getLogger(EmailPluginSupport.class);
    private static MailClient _client;

    private EmailPluginSupport() {
    }

    static synchronized void install(SwimPluginContext context) {
        shutdown();
        try {
            EmailPaths paths = EmailPaths.fromUserHome();
            _client = new BackgroundMailClient(paths.emailHome(), () -> new H2MailClient(paths,
                    new DefaultMailSyncAdapterFactory(), new SmtpMailSupport(paths), false, context.workers()), context.workers());
            MailPluginRegistry.register(_client);
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
