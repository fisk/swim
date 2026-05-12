module org.fisk.swim.clangd.lsp {
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.clangdlsp.ClangdLspPlugin;
}
