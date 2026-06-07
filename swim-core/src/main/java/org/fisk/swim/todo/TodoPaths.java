package org.fisk.swim.todo;

import java.nio.file.Path;

record TodoPaths(Path swimHome, Path todoHome, Path databasePath) {
    Path databaseBasePath() {
        String fileName = databasePath.getFileName().toString();
        if (fileName.endsWith(".mv.db")) {
            return databasePath.resolveSibling(fileName.substring(0, fileName.length() - ".mv.db".length()));
        }
        return databasePath;
    }

    Path databaseFilePath() {
        String fileName = databasePath.getFileName().toString();
        if (fileName.endsWith(".mv.db")) {
            return databasePath;
        }
        return databasePath.resolveSibling(fileName + ".mv.db");
    }

    String databaseJdbcUrl() {
        return "jdbc:h2:file:" + databaseBasePath().toAbsolutePath() + ";AUTO_SERVER=FALSE";
    }

    static TodoPaths fromUserHome() {
        Path swimHome = Path.of(System.getProperty("user.home"), ".swim");
        Path todoHome = swimHome.resolve("todo");
        return new TodoPaths(swimHome, todoHome, todoHome.resolve("todos.mv.db"));
    }
}
