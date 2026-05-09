module org.fisk.swim.launcher {
    requires java.compiler;
    requires java.desktop;
    requires java.management;
    requires java.net.http;
    requires java.rmi;
    requires java.scripting;
    requires java.xml;
    exports org.fisk.swim.api;
    exports org.fisk.swim.launcher;

    uses org.fisk.swim.api.SwimApp;
}
