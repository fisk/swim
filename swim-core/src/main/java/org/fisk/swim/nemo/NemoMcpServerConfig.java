package org.fisk.swim.nemo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record NemoMcpServerConfig(
        String name,
        boolean enabled,
        String command,
        List<String> args,
        Map<String, String> env,
        Path cwd,
        int timeoutSeconds) {
    NemoMcpServerConfig {
        name = name == null || name.isBlank() ? "server" : name.trim();
        command = command == null ? "" : command.trim();
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        cwd = cwd == null ? null : cwd.toAbsolutePath().normalize();
        timeoutSeconds = Math.max(1, timeoutSeconds);
    }
}
