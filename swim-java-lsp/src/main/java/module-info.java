module org.fisk.swim.java.lsp {
    requires com.google.gson;
    requires com.googlecode.lanterna;
    requires java.compiler;
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;
    requires org.fisk.swim.lsp;
    requires org.slf4j;

    opens org.fisk.swim.lsp.java to org.eclipse.lsp4j.jsonrpc;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.javalsp.JavaLspPlugin;
}
