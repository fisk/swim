package org.fisk.swim.plugins.treesitter;

import java.util.List;

import org.fisk.swim.api.SwimHelpChapter;
import org.fisk.swim.api.SwimHelpSection;
import org.fisk.swim.api.SwimPlugin;
import org.fisk.swim.api.SwimPluginContext;
import org.fisk.swim.api.SwimPluginPreloadContext;
import org.fisk.swim.treesitter.TreeSitterPluginSupport;

/** Phase-one Java-only Tree-sitter grammar exploration plugin. */
public final class TreeSitterPlugin implements SwimPlugin {
    @Override
    public String getId() {
        return TreeSitterPluginSupport.PLUGIN_ID;
    }

    @Override
    public boolean loadOnStartup() {
        return true;
    }

    @Override
    public void preload(SwimPluginPreloadContext context) {
        context.registerHelpChapter(new SwimHelpChapter("tree-sitter", "Tree-sitter grammar explorer",
                "Inspect generated Tree-sitter grammar.json files without loading native parser libraries.",
                List.of(new SwimHelpSection("Portable grammar snapshots",
                        List.of("The tree-sitter plugin currently reads grammar.json, the structured grammar emitted by tree-sitter generate. It analyzes immutable snapshots on plugin-owned workers and only publishes the newest result. grammar.js evaluation, parser.c loading, query execution, and external scanners are later phases."),
                        "Nemo tool: inspect_tree_sitter_grammar { path: \"vendor/tree-sitter-cpp/src/grammar.json\" }"))));
    }

    @Override
    public void load(SwimPluginContext context) {
        TreeSitterPluginSupport.install(context);
    }

    @Override
    public void close() {
        TreeSitterPluginSupport.shutdown();
    }
}
