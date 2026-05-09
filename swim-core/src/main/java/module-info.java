module org.fisk.swim.core {
    requires com.google.gson;
    requires com.googlecode.lanterna;
    requires static java.compiler;
    requires java.management;
    requires java.net.http;
    requires static jdk.httpserver;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;
    requires org.apache.logging.log4j.slf4j2.impl;
    requires org.eclipse.jgit;
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires org.fisk.swim.launcher;
    requires org.slf4j;

    provides org.fisk.swim.api.SwimApp with org.fisk.swim.SwimAppImpl;
}
