package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class PluginArchitectureTest {
    private static final Set<String> ROOT_MODULES = Set.of("swim-session", "swim-launcher", "swim-lsp", "swim-core");

    @Test
    void pluginModulesBranchFromCoreWithoutReverseOrSiblingDependencies() throws Exception {
        Path root = findBuildRoot();
        Set<String> modules = moduleNames(root.resolve("pom.xml"));
        Set<String> pluginModules = modules.stream()
                .filter(module -> !ROOT_MODULES.contains(module))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertFalse(pluginModules.isEmpty(), "Expected plugin modules in root pom");
        for (String rootModule : ROOT_MODULES) {
            if (modules.contains(rootModule)) {
                assertNoPluginDependencies(root.resolve(rootModule).resolve("pom.xml"), pluginModules);
            }
        }
        for (String pluginModule : pluginModules) {
            Set<String> siblingPlugins = new LinkedHashSet<>(pluginModules);
            siblingPlugins.remove(pluginModule);
            assertNoPluginDependencies(root.resolve(pluginModule).resolve("pom.xml"), siblingPlugins);
        }
    }

    private static void assertNoPluginDependencies(Path pom, Set<String> forbiddenArtifactIds) throws Exception {
        Set<String> dependencies = dependencyArtifactIds(pom);
        Set<String> forbiddenDependencies = dependencies.stream()
                .filter(forbiddenArtifactIds::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(forbiddenDependencies.isEmpty(),
                () -> pom + " must not depend on plugin module(s): " + forbiddenDependencies);
    }

    private static Set<String> moduleNames(Path pom) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var nodes = document.getElementsByTagName("module");
        var modules = new LinkedHashSet<String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            modules.add(nodes.item(i).getTextContent().trim());
        }
        return modules;
    }

    private static Set<String> dependencyArtifactIds(Path pom) throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var nodes = document.getElementsByTagName("dependency");
        var artifactIds = new LinkedHashSet<String>();
        for (int i = 0; i < nodes.getLength(); i++) {
            var dependency = (Element) nodes.item(i);
            var artifacts = dependency.getElementsByTagName("artifactId");
            if (artifacts.getLength() > 0) {
                artifactIds.add(artifacts.item(0).getTextContent().trim());
            }
        }
        return artifactIds;
    }

    private static Path findBuildRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isDirectory(current.resolve("swim-core"))
                    && Files.isDirectory(current.resolve("swim-launcher"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate swim build root");
    }
}
