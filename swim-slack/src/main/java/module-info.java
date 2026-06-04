module org.fisk.swim.slack {
    requires com.google.gson;
    requires java.net.http;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;
    requires org.slf4j;

    opens org.fisk.swim.plugins.slack to com.google.gson;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.slack.SlackPlugin;
}
