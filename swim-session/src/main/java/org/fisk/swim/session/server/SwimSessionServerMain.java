package org.fisk.swim.session.server;

import java.nio.file.Path;

public final class SwimSessionServerMain {
    private SwimSessionServerMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("swim-session: requires socket and working directory");
            System.exit(2);
        }
        SwimSessionServer.run(Path.of(args[0]), Path.of(args[1]));
    }
}
