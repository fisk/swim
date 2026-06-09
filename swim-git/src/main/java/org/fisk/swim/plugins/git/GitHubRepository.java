package org.fisk.swim.plugins.git;

record GitHubRepository(String remoteName, String owner, String name) {
    String apiBase() {
        return "https://api.github.com/repos/" + owner + "/" + name;
    }

    String label() {
        return remoteName + "  " + owner + "/" + name;
    }

    String slug() {
        return owner + "/" + name;
    }
}
