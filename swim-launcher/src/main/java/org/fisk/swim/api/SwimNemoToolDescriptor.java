package org.fisk.swim.api;

public record SwimNemoToolDescriptor(
        String exposedName,
        String pluginId,
        String toolName,
        String description,
        String inputSchemaJson,
        boolean availableInReadOnly,
        boolean requiresApproval) {
}
