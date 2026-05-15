package org.fisk.swim.plugins.email;

final class SecretResolver {
    private SecretResolver() {
    }

    static String resolve(String key) {
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
}
