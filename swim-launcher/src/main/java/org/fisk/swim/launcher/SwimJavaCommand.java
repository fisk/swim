package org.fisk.swim.launcher;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class SwimJavaCommand {
    private static final String LAUNCHER_MODULE = "org.fisk.swim.launcher/org.fisk.swim.launcher.Main";
    private static final String SESSION_SERVER_MODULE =
            "org.fisk.swim.session/org.fisk.swim.session.server.SwimSessionServerMain";
    private static final String NATIVE_ACCESS_OPTION = "--enable-native-access=org.fisk.swim.session";
    private static final String FINAL_FIELD_MUTATION_OPTION = "--enable-final-field-mutation=ALL-UNNAMED";
    private static final String ILLEGAL_FINAL_FIELD_MUTATION_OPTION = "--illegal-final-field-mutation=allow";
    private static final String GC_LOG_OPTION = "-Xlog:gc*:file=%s:time,uptime,level,tags:filesize=10M,filecount=2";
    private static final List<String> APP_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+ZAdaptiveHeapSizing",
            "-XX:+UseStringDeduplication",
            "--sun-misc-unsafe-memory-access=allow");
    private static final List<String> SESSION_SERVER_JVM_OPTIONS = List.of(
            "-XX:+UseZGC",
            "-XX:+IgnoreUnrecognizedVMOptions",
            "-XX:+ZAdaptiveHeapSizing");

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
        Path gcLogDirectory = Path.of(System.getProperty("user.home"), ".swim", "logs");
        try {
            Files.createDirectories(gcLogDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create SWIM GC log directory", e);
        }
        command.add(String.format(GC_LOG_OPTION, gcLogDirectory.resolve("gclog-%%p.log").toString()));
        var jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        if (nativeAccess) {
            command.addAll(inheritedNonMemoryPolicyJvmArgs(jvmArgs));
            command.addAll(SESSION_SERVER_JVM_OPTIONS);
        } else {
            command.addAll(inheritedNonMemoryPolicyJvmArgs(jvmArgs));
            command.addAll(appJvmOptions());
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

    private static List<String> inheritedNonMemoryPolicyJvmArgs(List<String> jvmArgs) {
        return jvmArgs.stream()
                .filter(arg -> !arg.startsWith("-Xmx"))
                .filter(arg -> !arg.startsWith("-XX:SoftMaxHeapSize="))
                .filter(arg -> !arg.startsWith("--sun-misc-unsafe-memory-access="))
                .filter(arg -> !arg.startsWith("--enable-final-field-mutation="))
                .filter(arg -> !arg.startsWith("--illegal-final-field-mutation="))
                .filter(arg -> !"-XX:+ZAdaptiveHeapSizing".equals(arg))
                .filter(arg -> !isCollectorSelectionOption(arg))
                .toList();
    }

    static List<String> appJvmOptions(int javaFeatureVersion) {
        var options = new ArrayList<String>();
        options.addAll(APP_JVM_OPTIONS);
        if (javaFeatureVersion >= 26) {
            options.add(FINAL_FIELD_MUTATION_OPTION);
            options.add(ILLEGAL_FINAL_FIELD_MUTATION_OPTION);
        }
        return List.copyOf(options);
    }

    private static List<String> appJvmOptions() {
        return appJvmOptions(Runtime.version().feature());
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
