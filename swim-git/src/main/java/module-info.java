module org.fisk.swim.git {
    requires org.fisk.swim.launcher;
    requires org.eclipse.jgit;
    requires com.google.gson;
    requires java.net.http;

    exports org.fisk.swim.plugins.git;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.git.GitPlugin;
}
