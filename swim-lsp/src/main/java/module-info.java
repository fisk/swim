module org.fisk.swim.lsp {
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;
    requires org.slf4j;

    exports org.fisk.swim.lsp.shared;
}
