package org.fisk.swim.api;

public interface SwimNemoTool {
    String getName();
    String getDescription();
    String getInputSchemaJson();

    default boolean availableInReadOnly() {
        return false;
    }

    default boolean requiresApproval() {
        return true;
    }

    String execute(SwimNemoToolInvocation invocation) throws Exception;
}
