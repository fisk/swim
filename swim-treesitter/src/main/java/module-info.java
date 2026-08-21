module org.fisk.swim.treesitter {
    requires com.google.gson;
    requires com.googlecode.lanterna;
    requires org.fisk.swim.core;
    requires org.fisk.swim.launcher;

    exports org.fisk.swim.treesitter;

    provides org.fisk.swim.api.SwimPlugin with org.fisk.swim.plugins.treesitter.TreeSitterPlugin;
}
