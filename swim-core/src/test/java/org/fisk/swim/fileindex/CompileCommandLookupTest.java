package org.fisk.swim.fileindex;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileCommandLookupTest {
    @TempDir Path tempDir;

    @Test
    void resolvesArgumentsForTheRequestedTranslationUnit() throws Exception {
        Path source = tempDir.resolve("src/main.cpp");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "int main() {}");
        Path database = tempDir.resolve("compile_commands.json");
        Files.writeString(database, """
                [{"directory":"%s","file":"src/main.cpp","arguments":["clang++","-DNAME=a b","-c","src/main.cpp"]}]
                """.formatted(tempDir));

        var entry = CompileCommandLookup.find(database, source);

        assertEquals(tempDir, entry.directory());
        assertEquals("'clang++' '-DNAME=a b' '-c' 'src/main.cpp'", entry.command());
    }
}
