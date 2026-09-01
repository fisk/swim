package org.fisk.swim.api;

/** A colon command contributed by a plugin. */
public interface SwimCommand {
    String name();
    String description();

    /**
     * Explicit opt-in for this command when it is invoked through Nemo editor
     * control. Commands remain blocked unless their plugin approves this
     * particular invocation.
     */
    default boolean allowEditorDrive(SwimCommandInvocation invocation) {
        return false;
    }

    String execute(SwimCommandInvocation invocation) throws Exception;
}
