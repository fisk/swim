package org.fisk.swim.help;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ManPageInstaller {
    private static final Path MAN_PAGE_TARGET = Path.of("share", "man", "man1", "swim.1");

    private ManPageInstaller() {
    }

    public static void main(String[] args) throws IOException {
        Path swimHome = args.length > 0 && args[0] != null && !args[0].isBlank()
                ? Path.of(args[0])
                : Path.of(System.getProperty("user.dir"));
        install(swimHome);
    }

    static Path install(Path swimHome) throws IOException {
        Path target = swimHome.resolve(MAN_PAGE_TARGET);
        Files.createDirectories(target.getParent());
        Files.writeString(target, ManPageDocument.render(), StandardCharsets.UTF_8);
        return target;
    }
}
