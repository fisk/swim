package org.fisk.swim;

/** Owns H2's JVM-wide JDBC registration for a reloadable core runtime. */
final class H2Runtime {
    private H2Runtime() {
    }

    static void start() {
        // Also re-register when the launcher restores this runtime after a
        // replacement fails to start. Class.forName would not run H2's initializer again.
        org.h2.Driver.load();
    }

    static void release() {
        ClassLoader owner = H2Runtime.class.getClassLoader();
        if (owner != ClassLoader.getSystemClassLoader()
                && org.h2.Driver.class.getClassLoader() == owner) {
            // DriverManager lives in the JDK and otherwise retains this entire
            // class loader, including the mail full-text caches, after :reload.
            // Never unregister a parent/shared application's driver.
            org.h2.Driver.unload();
        }
    }
}
