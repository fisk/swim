module org.fisk.swim.launcher {
    requires java.compiler;
    requires java.desktop;
    requires java.management;
    requires java.net.http;
    requires java.rmi;
    requires java.scripting;
    requires java.sql;
    requires java.sql.rowset;
    requires java.xml;
    requires jdk.httpserver;
    exports org.fisk.swim.api;
    exports org.fisk.swim.launcher;

    uses org.fisk.swim.api.SwimApp;
    uses org.fisk.swim.api.SwimPlugin;
}
