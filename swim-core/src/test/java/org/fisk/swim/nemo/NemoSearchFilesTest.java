package org.fisk.swim.nemo;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NemoSearchFilesTest {
  @TempDir Path temporary;

  @Test
  void searchesLongLinesWithoutFollowingSymlinksOrEnteringGit() throws Exception {
    Path project = Files.createDirectory(temporary.resolve("project"));
    Path file = project.resolve("generated.txt");
    Files.writeString(file, "first\n" + "x".repeat(20000) + "needle\r\nlast");
    Path git = Files.createDirectory(project.resolve(".git"));
    Files.writeString(git.resolve("hidden.txt"), "needle git-marker");
    Path outside = temporary.resolve("outside.txt");
    Files.writeString(outside, "needle outside-marker");
    Files.createSymbolicLink(project.resolve("linked.txt"), outside);

    String output = search(project, "needle", 50, 4000);

    assertTrue(output.contains("generated.txt:2: …"));
    assertTrue(output.contains("needle"));
    assertFalse(output.contains("git-marker"));
    assertFalse(output.contains("outside-marker"));
    assertTrue(output.length() < 600);
  }

  @Test
  void limitsResultsAndBoundsOutputEvenWithAnEnormousRequestedResultCount() throws Exception {
    Path project = Files.createDirectory(temporary.resolve("project"));
    Path file = project.resolve("matches.txt");
    Files.writeString(file, ("needle " + "x".repeat(600) + "\n").repeat(10000));

    String one = search(project, "needle", 1, 4000);
    String bounded = search(project, "needle", Integer.MAX_VALUE, 1024);

    assertTrue(one.startsWith("matches.txt:1: needle"));
    assertFalse(one.contains("matches.txt:2:"));
    assertTrue(bounded.length() <= 1027);
    assertTrue(bounded.endsWith("..."));
  }

  private static String search(Path project, String query, int results, int outputChars) throws Exception {
    var configuration = NemoClient.Configuration.builder()
        .workspaceRoot(project).toolPermissionMode("read-only")
        .toolMaxOutputChars(outputChars).build();
    var arguments = new JsonObject();
    arguments.addProperty("query", query);
    arguments.addProperty("max_results", results);
    return NemoClient.executeTool(configuration,
        new BufferContext(Rect.create(0, 0, 80, 20), project.resolve("generated.txt")),
        new NemoClient.ToolCall("search", "search_files", arguments));
  }
}
