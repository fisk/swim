module org.fisk.swim.core {
    requires langchain4j.core;
    requires langchain4j.community.zhipu.ai;
    requires langchain4j.http.client.jdk;
    requires langchain4j.open.ai;
    requires com.google.gson;
    requires com.googlecode.lanterna;
    requires com.h2database;
    requires static java.compiler;
    requires java.management;
    requires java.net.http;
    requires java.sql;
    requires java.xml;
    requires static jdk.httpserver;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j.slf4j2.impl;
    requires org.eclipse.jgit;
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires org.fisk.swim.launcher;
    requires org.fisk.swim.session;
    requires org.slf4j;

    exports org.fisk.swim;
    exports org.fisk.swim.event;
    exports org.fisk.swim.fileindex;
    exports org.fisk.swim.lsp;
    exports org.fisk.swim.mail;
    exports org.fisk.swim.slack;
    exports org.fisk.swim.debug;
    exports org.fisk.swim.text;
    exports org.fisk.swim.ui;
    exports org.fisk.swim.utils;
    opens org.fisk.swim.config to com.google.gson;

    provides org.fisk.swim.api.SwimApp with org.fisk.swim.SwimAppImpl;
}
