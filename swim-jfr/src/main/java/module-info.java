module org.fisk.swim.jfr {
    requires org.fisk.swim.launcher;
    requires jdk.jfr;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.jfrmetrics.JfrPlugin;
}
