module org.fisk.swim.email {
    requires com.google.gson;
    requires jakarta.mail;
    requires java.net.http;
    requires java.sql;
    requires java.xml;
    requires jdk.httpserver;
    requires org.eclipse.angus.mail;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;
    requires org.slf4j;
    requires org.xerial.sqlitejdbc;

    opens org.fisk.swim.plugins.email to com.google.gson;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.email.EmailPlugin;
}
