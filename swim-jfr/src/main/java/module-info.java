module org.fisk.swim.jfr {
    requires transitive org.fisk.swim.launcher;
    exports org.fisk.swim.plugins.jfrmetrics;
    requires jdk.jfr;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.jfrmetrics.JfrPlugin;
}
