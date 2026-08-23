module org.fisk.swim.clangd.lsp {
    requires com.google.gson;
    requires org.eclipse.lsp4j;
    requires org.eclipse.lsp4j.jsonrpc;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;
    requires org.fisk.swim.lsp;
    requires org.fisk.swim.treesitter;
    requires org.slf4j;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.clangdlsp.ClangdLspPlugin;
}
