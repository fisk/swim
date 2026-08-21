package org.fisk.swim.treesitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finds conventional generated grammar and query assets beneath a project-local root. */
public final class TreeSitterGrammarAssetDiscovery {
    private TreeSitterGrammarAssetDiscovery() {
    }

    public static List<TreeSitterGrammarAssets> discover(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return List.of();
        }
        Path root = projectRoot.toRealPath();
        var assets = new LinkedHashMap<Path, MutableAssets>();
        try (var paths = Files.walk(root, 6)) {
            paths.filter(Files::isRegularFile).forEach(path -> collect(root, path, assets));
        }
        return assets.values().stream()
                .filter(asset -> asset.grammarJson != null || asset.highlightsQuery != null || asset.injectionsQuery != null)
                .map(MutableAssets::freeze)
                .sorted(Comparator.comparing(asset -> asset.root().toString()))
                .toList();
    }

    private static void collect(Path projectRoot, Path path, Map<Path, MutableAssets> assets) {
        String name = path.getFileName().toString();
        Path assetRoot;
        if ("grammar.json".equals(name)) {
            assetRoot = path.getParent();
            if (assetRoot != null && "src".equals(assetRoot.getFileName().toString())) {
                assetRoot = assetRoot.getParent();
            }
            if (assetRoot == null) return;
            assets.computeIfAbsent(assetRoot, MutableAssets::new).grammarJson = path;
        } else if (("highlights.scm".equals(name) || "injections.scm".equals(name))
                && path.getParent() != null && "queries".equals(path.getParent().getFileName().toString())) {
            assetRoot = path.getParent().getParent();
            if (assetRoot == null || !assetRoot.startsWith(projectRoot)) return;
            var asset = assets.computeIfAbsent(assetRoot, MutableAssets::new);
            if ("highlights.scm".equals(name)) asset.highlightsQuery = path;
            else asset.injectionsQuery = path;
        }
    }

    private static final class MutableAssets {
        private final Path root;
        private Path grammarJson;
        private Path highlightsQuery;
        private Path injectionsQuery;

        private MutableAssets(Path root) {
            this.root = root;
        }

        private TreeSitterGrammarAssets freeze() {
            return new TreeSitterGrammarAssets(root, grammarJson, highlightsQuery, injectionsQuery);
        }
    }
}
