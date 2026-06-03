package org.fisk.swim.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class SwimHomeFixture {
    private final Path _home;
    private final Path _swimHome;

    private SwimHomeFixture(Path home) throws IOException {
        _home = home;
        _swimHome = home.resolve(".swim");
        Files.createDirectories(_swimHome);
    }

    public static SwimHomeFixture create(Path root) throws IOException {
        return new SwimHomeFixture(InstalledSwimDriver.createHome(root));
    }

    public Path home() {
        return _home;
    }

    public Path swimHome() {
        return _swimHome;
    }

    public Path emailHome() throws IOException {
        Path email = _swimHome.resolve("email");
        Files.createDirectories(email);
        return email;
    }

    public Path nemoHome() throws IOException {
        Path nemo = _swimHome.resolve("nemo");
        Files.createDirectories(nemo);
        return nemo;
    }

    public Path writeEmailAccounts(String json) throws IOException {
        Path path = emailHome().resolve("accounts.json");
        Files.writeString(path, json);
        return path;
    }

    public Path writeEmailTagRules(String json) throws IOException {
        Path path = emailHome().resolve("tag-rules.json");
        Files.writeString(path, json);
        return path;
    }

    public Path writeEmailOAuthTokens(String json) throws IOException {
        Path path = emailHome().resolve("oauth-tokens.json");
        Files.writeString(path, json);
        return path;
    }

    public Path emailDatabasePath() throws IOException {
        return emailHome().resolve("mail.mv.db");
    }

    public Path writeNemoConfig(String text) throws IOException {
        Path path = _swimHome.resolve("nemo.conf");
        Files.writeString(path, text);
        return path;
    }

    public Path nemoSessionsPath() throws IOException {
        return nemoHome().resolve("sessions.json");
    }

    public Path swimDepsHome() throws IOException {
        Path deps = _swimHome.resolve("deps");
        Files.createDirectories(deps);
        return deps;
    }

    public Path copyOracleJavaExtensionFrom(Path source) throws IOException {
        Path destination = swimDepsHome().resolve("oracle.oracle-java");
        copyDirectory(source, destination);
        return destination;
    }

    public void runSqlite(String sql) throws Exception {
        Path db = emailDatabasePath();
        var process = new ProcessBuilder("/usr/bin/sqlite3", db.toString(), sql)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("sqlite3 failed: " + output);
        }
    }

    public void runH2(String sql) throws Exception {
        Path jar = findH2Jar();
        Path base = emailHome().resolve("mail");
        var process = new ProcessBuilder(
                "java",
                "-cp",
                jar.toString(),
                "org.h2.tools.Shell",
                "-url",
                "jdbc:h2:file:" + base,
                "-sql",
                sql)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException("H2 shell failed: " + output);
        }
    }

    private static Path findH2Jar() throws IOException {
        Path repo = Path.of(System.getProperty("user.home"), ".m2", "repository", "com", "h2database", "h2");
        try (Stream<Path> stream = Files.walk(repo)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().startsWith("h2-")
                            && path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .findFirst()
                    .orElseThrow(() -> new IOException("Unable to locate H2 jar under " + repo));
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Source directory does not exist: " + source);
        }
        if (Files.exists(destination)) {
            try (var stream = Files.walk(destination)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
