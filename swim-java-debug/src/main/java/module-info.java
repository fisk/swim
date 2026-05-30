module org.fisk.swim.java.debug {
    requires jdk.jdi;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.javadebug.JavaDebugPlugin;
}
