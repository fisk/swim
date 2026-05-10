package org.fisk.swim.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class OracleJavaExtensionInstaller {
    private static final URI VSIX_URI = URI.create(
            "https://marketplace.visualstudio.com/_apis/public/gallery/publishers/Oracle/vsextensions/oracle-java/latest/vspackage");

    private final HttpClient _httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public static void main(String[] args) throws Exception {
        Path buildRoot = resolveBuildRoot(args);
        new OracleJavaExtensionInstaller().install(buildRoot);
    }

    private static Path resolveBuildRoot(String[] args) {
        if (args.length > 0 && args[0] != null && !args[0].isBlank()) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path discovered = Main.findBuildRoot(cwd);
        if (discovered != null) {
            return discovered;
        }
        return cwd;
    }

    void install(Path buildRoot) throws Exception {
        if (Boolean.getBoolean("swim.install.oracle.java.skip")) {
            return;
        }

        Path depsDir = buildRoot.resolve("deps");
        Path installRoot = depsDir.resolve("oracle.oracle-java");
        if (isInstalled(installRoot) && !Boolean.getBoolean("swim.install.oracle.java.force")) {
            return;
        }

        Files.createDirectories(depsDir);
        Path archive = depsDir.resolve("oracle.oracle-java.vsix");
        Path tempArchive = depsDir.resolve("oracle.oracle-java.vsix.download");
        Path normalizedArchive = depsDir.resolve("oracle.oracle-java.vsix.payload");
        Path tempInstall = depsDir.resolve("oracle.oracle-java.tmp");

        deleteRecursively(tempArchive);
        deleteRecursively(normalizedArchive);
        deleteRecursively(tempInstall);

        downloadVsix(tempArchive);
        normalizeArchive(tempArchive, normalizedArchive);
        extractVsix(normalizedArchive, tempInstall);

        deleteRecursively(archive);
        Files.move(tempArchive, archive, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursively(installRoot);
        Files.move(tempInstall, installRoot, StandardCopyOption.REPLACE_EXISTING);
        deleteRecursively(normalizedArchive);
    }

    private void downloadVsix(Path destination) throws Exception {
        var request = HttpRequest.newBuilder(VSIX_URI)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        var response = _httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Failed to download Oracle Java VSIX: HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void normalizeArchive(Path downloadedArchive, Path normalizedArchive) throws IOException {
        try (InputStream input = Files.newInputStream(downloadedArchive)) {
            int first = input.read();
            int second = input.read();
            if (first == 0x1f && second == 0x8b) {
                try (InputStream gzip = new GZIPInputStream(Files.newInputStream(downloadedArchive))) {
                    Files.copy(gzip, normalizedArchive, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.copy(downloadedArchive, normalizedArchive, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void extractVsix(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String rawName = entry.getName();
                String name = rawName.startsWith("extension/") ? rawName.substring("extension/".length()) : rawName;
                if (name.isBlank()) {
                    continue;
                }
                Path path = destination.resolve(name).normalize();
                if (!path.startsWith(destination)) {
                    throw new IOException("VSIX entry escapes install root: " + rawName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private boolean isInstalled(Path installRoot) {
        return Files.isRegularFile(installRoot.resolve("nbcode").resolve("bin").resolve("nbcode.sh"))
                || Files.isRegularFile(installRoot.resolve("nbcode").resolve("bin").resolve("nbcode64.exe"))
                || Files.isRegularFile(installRoot.resolve("nbcode").resolve("bin").resolve("nbcode.exe"));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }
}
