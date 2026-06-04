package org.fisk.swim.plugins.slack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SlackTokenResolver {
    private SlackTokenResolver() {
    }

    static String resolve(SlackWorkspaceConfig config) throws IOException {
        if (config == null) {
            return null;
        }
        if (config.token() != null && !config.token().isBlank()) {
            return config.token().trim();
        }
        String env = resolveProperty(config.tokenEnv());
        if (env != null) {
            return env;
        }
        if (config.tokenCommand() == null || config.tokenCommand().isBlank()) {
            return null;
        }
        Process process;
        try {
            process = new ProcessBuilder("/bin/sh", "-lc", config.tokenCommand()).start();
        } catch (IOException e) {
            throw new IOException("Failed to run tokenCommand for " + safe(config.id(), "workspace"), e);
        }
        try {
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("tokenCommand failed for " + safe(config.id(), "workspace")
                        + (stderr.isBlank() ? "" : ": " + stderr));
            }
            return stdout.isBlank() ? null : stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving Slack token for " + safe(config.id(), "workspace"), e);
        }
    }

    private static String resolveProperty(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String envValue = System.getenv(key.trim());
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }
        String propertyValue = System.getProperty(key.trim());
        return propertyValue == null || propertyValue.isBlank() ? null : propertyValue;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
