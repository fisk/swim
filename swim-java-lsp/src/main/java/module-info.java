module org.fisk.swim.java.lsp {
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.javalsp.JavaLspPlugin;
}
