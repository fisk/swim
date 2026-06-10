package org.fisk.swim.launcher;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SwimJavaCommand {
    private static final String LAUNCHER_MODULE = "org.fisk.swim.launcher/org.fisk.swim.launcher.Main";
    private static final String SESSION_SERVER_MODULE =
            "org.fisk.swim.session/org.fisk.swim.session.server.SwimSessionServerMain";
    private static final String NATIVE_ACCESS_OPTION = "--enable-native-access=org.fisk.swim.session";
    private static final List<String> SESSION_SERVER_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-Xmx4G",
            "-XX:SoftMaxHeapSize=1G");

    private SwimJavaCommand() {
    }

    static List<String> serverCommand(Path socketPath, Path buildRoot) {
        var args = new ArrayList<String>();
        args.add(socketPath.toString());
        args.add(buildRoot.toString());
        return command(SESSION_SERVER_MODULE, args, true);
    }

    static List<String> appCommand(List<String> launchArgs) {
        var args = new ArrayList<String>();
        args.add("--swim-app");
        args.addAll(launchArgs == null ? List.of() : launchArgs);
        return command(LAUNCHER_MODULE, args, false);
    }

    private static List<String> command(String module, List<String> appArgs, boolean nativeAccess) {
        var command = new ArrayList<String>();
        command.add(javaExecutable().toString());
        var jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        command.addAll(nativeAccess ? sessionServerInheritedJvmArgs(jvmArgs) : jvmArgs);
        if (nativeAccess) {
            command.addAll(SESSION_SERVER_JVM_OPTIONS);
        }
        if (nativeAccess && jvmArgs.stream().noneMatch(NATIVE_ACCESS_OPTION::equals)) {
            command.add(NATIVE_ACCESS_OPTION);
        }
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath != null && !modulePath.isBlank()) {
            command.add("--module-path");
            command.add(modulePath);
        }
        command.add("-m");
        command.add(module);
        command.addAll(appArgs);
        return List.copyOf(command);
    }

    private static List<String> sessionServerInheritedJvmArgs(List<String> jvmArgs) {
        return jvmArgs.stream()
                .filter(arg -> !arg.startsWith("-Xmx"))
                .filter(arg -> !arg.startsWith("-XX:SoftMaxHeapSize="))
                .filter(arg -> !isCollectorSelectionOption(arg))
                .toList();
    }

    private static boolean isCollectorSelectionOption(String arg) {
        return arg.startsWith("-XX:+Use") && arg.endsWith("GC");
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
