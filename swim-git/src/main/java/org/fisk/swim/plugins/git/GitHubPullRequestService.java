package org.fisk.swim.plugins.git;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.RefSpec;

final class GitHubPullRequestService {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private GitHubPullRequestService() {
    }

    static List<GitHubRepository> repositories(Path repositoryRoot) throws IOException {
        if (repositoryRoot == null) {
            return List.of();
        }
        var repositories = new ArrayList<GitHubRepository>();
        try (var git = Git.open(repositoryRoot.toFile())) {
            var config = git.getRepository().getConfig();
            for (String remote : config.getSubsections("remote")) {
                GitHubRepository repository = parseRepository(remote, config.getString("remote", remote, "url"));
                if (repository != null) {
                    repositories.add(repository);
                }
            }
            return List.copyOf(repositories);
        }
    }

    static List<GitHubPullRequest> listPullRequests(GitHubRepository repository, String filter)
            throws IOException, InterruptedException {
        if (repository == null) {
            throw new IOException("No GitHub remote found");
        }
        String body = get(repository.apiBase() + "/pulls?state=open&per_page=50");
        var pullRequests = new ArrayList<GitHubPullRequest>();
        for (JsonElement element : JsonParser.parseString(body).getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            JsonObject user = object.getAsJsonObject("user");
            JsonObject head = object.getAsJsonObject("head");
            JsonObject base = object.getAsJsonObject("base");
            pullRequests.add(new GitHubPullRequest(
                    number(object),
                    string(object, "title"),
                    string(user, "login"),
                    string(head, "ref"),
                    string(base, "ref"),
                    string(head, "sha"),
                    string(base, "sha"),
                    string(object, "html_url"),
                    string(object, "updated_at")));
        }
        return filterPullRequests(pullRequests, filter);
    }

    static List<GitHubPullRequestFile> files(GitHubRepository repository, GitHubPullRequest pullRequest)
            throws IOException, InterruptedException {
        if (repository == null) {
            throw new IOException("No GitHub remote found");
        }
        String body = get(repository.apiBase() + "/pulls/" + pullRequest.number() + "/files?per_page=100");
        var files = new ArrayList<GitHubPullRequestFile>();
        for (JsonElement element : JsonParser.parseString(body).getAsJsonArray()) {
            JsonObject object = element.getAsJsonObject();
            files.add(new GitHubPullRequestFile(
                    string(object, "filename"),
                    string(object, "status"),
                    number(object, "additions"),
                    number(object, "deletions"),
                    splitPatchLines(string(object, "patch"))));
        }
        return List.copyOf(files);
    }

    static String fetch(Path repositoryRoot, GitHubRepository repository, GitHubPullRequest pullRequest)
            throws IOException, GitAPIException {
        if (repository == null) {
            throw new IOException("No GitHub remote selected");
        }
        try (var git = Git.open(repositoryRoot.toFile())) {
            String localRef = "refs/remotes/" + repository.remoteName() + "/pr/" + pullRequest.number();
            git.fetch()
                    .setRemote(repository.remoteName())
                    .setRefSpecs(new RefSpec("+refs/pull/" + pullRequest.number() + "/head:" + localRef))
                    .call();
            return "Fetched PR #" + pullRequest.number() + " to "
                    + repository.remoteName() + "/pr/" + pullRequest.number();
        }
    }

    static GitHubRepository parseRepository(String remoteName, String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String value = remoteUrl.strip();
        String marker = "github.com";
        int index = value.indexOf(marker);
        if (index < 0) {
            return null;
        }
        String suffix = value.substring(index + marker.length());
        if (suffix.startsWith(":")) {
            suffix = suffix.substring(1);
        } else if (suffix.startsWith("/")) {
            suffix = suffix.substring(1);
        } else {
            return null;
        }
        if (suffix.endsWith(".git")) {
            suffix = suffix.substring(0, suffix.length() - ".git".length());
        }
        String[] parts = suffix.split("/");
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new GitHubRepository(remoteName == null || remoteName.isBlank() ? "origin" : remoteName, parts[0], parts[1]);
    }

    static List<GitHubPullRequest> filterPullRequests(List<GitHubPullRequest> pullRequests, String filter) {
        if (filter == null || filter.isBlank()) {
            return pullRequests == null ? List.of() : List.copyOf(pullRequests);
        }
        String needle = filter.toLowerCase(Locale.ROOT);
        return pullRequests.stream()
                .filter(pr -> searchable(pr).contains(needle))
                .toList();
    }

    private static String get(String url) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "swim-git");
        String token = firstNonBlank(System.getenv("GITHUB_TOKEN"), System.getenv("GH_TOKEN"));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = HTTP.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("GitHub request failed: HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static String searchable(GitHubPullRequest pullRequest) {
        return ("#" + pullRequest.number() + " " + pullRequest.title() + " " + pullRequest.author() + " "
                + pullRequest.headRef() + " " + pullRequest.baseRef()).toLowerCase(Locale.ROOT);
    }

    private static List<String> splitPatchLines(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of("(patch unavailable from GitHub API)");
        }
        return List.of(patch.split("\\R", -1));
    }

    private static int number(JsonObject object) {
        return number(object, "number");
    }

    private static int number(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        return object.get(key).getAsInt();
    }

    private static String string(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
