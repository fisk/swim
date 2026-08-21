package org.fisk.swim.treesitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.api.SwimNemoTool;
import org.fisk.swim.api.SwimNemoToolInvocation;
import org.fisk.swim.api.SwimPluginContext;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TreeSitterPluginSupport {
    public static final String PLUGIN_ID = "tree-sitter";
    private static volatile TreeSitterGrammarAnalysisService _analysis;
    private static volatile AutoCloseable _toolRegistration;

    private TreeSitterPluginSupport() {
    }

    public static synchronized void install(SwimPluginContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Plugin context is required");
        }
        shutdown();
        _analysis = new TreeSitterGrammarAnalysisService(context.workers());
        _toolRegistration = context.registerNemoTool(new GrammarInspectorTool());
    }

    public static synchronized void shutdown() {
        close(_toolRegistration);
        _toolRegistration = null;
        if (_analysis != null) {
            _analysis.close();
            _analysis = null;
        }
    }

    private static void close(AutoCloseable value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception e) {
        }
    }

    private static final class GrammarInspectorTool implements SwimNemoTool {
        @Override
        public String getName() {
            return "inspect_tree_sitter_grammar";
        }

        @Override
        public String getDescription() {
            return "Inspect a project-local Tree-sitter grammar.json using immutable background snapshots.";
        }

        @Override
        public String getInputSchemaJson() {
            return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Project-relative grammar.json path\"}},\"required\":[\"path\"],\"additionalProperties\":false}";
        }

        @Override
        public boolean availableInReadOnly() {
            return true;
        }

        @Override
        public boolean requiresApproval() {
            return false;
        }

        @Override
        public String execute(SwimNemoToolInvocation invocation) throws Exception {
            Path path = resolveProjectPath(invocation);
            String text = Files.readString(path);
            TreeSitterGrammarAnalysisService analysis = _analysis;
            if (analysis == null) {
                throw new IllegalStateException("Tree-sitter plugin is not active");
            }
            var response = new AtomicReference<TreeSitterGrammarAnalysisService.Result>();
            var completed = new CountDownLatch(1);
            analysis.inspect(new TreeSitterGrammarSnapshot(path, 1, text), result -> {
                response.set(result);
                completed.countDown();
            });
            if (!completed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out inspecting Tree-sitter grammar");
            }
            var result = response.get();
            if (result == null) {
                throw new IllegalStateException("Tree-sitter grammar inspection was superseded");
            }
            if (!result.succeeded()) {
                throw new IllegalArgumentException("Invalid Tree-sitter grammar.json: " + result.failure().getMessage(), result.failure());
            }
            return new com.google.gson.Gson().toJson(result.inspection());
        }

        private static Path resolveProjectPath(SwimNemoToolInvocation invocation) throws Exception {
            JsonObject arguments = JsonParser.parseString(invocation.argumentsJson()).getAsJsonObject();
            String rawPath = arguments.has("path") ? arguments.get("path").getAsString() : "";
            if (rawPath.isBlank()) {
                throw new IllegalArgumentException("A grammar.json path is required");
            }
            Path root = invocation.workspaceRoot();
            if (root == null) {
                throw new IllegalArgumentException("A project workspace is required");
            }
            Path projectRoot = root.toRealPath();
            Path target = projectRoot.resolve(rawPath).normalize();
            if (!target.startsWith(projectRoot) || !Files.isRegularFile(target)) {
                throw new IllegalArgumentException("Grammar path must name a file inside the project workspace");
            }
            Path resolved = target.toRealPath();
            if (!resolved.startsWith(projectRoot)) {
                throw new IllegalArgumentException("Grammar path must not leave the project workspace");
            }
            return resolved;
        }
    }
}
