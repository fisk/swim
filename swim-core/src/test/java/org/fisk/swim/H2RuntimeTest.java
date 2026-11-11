package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class H2RuntimeTest {
    @Test
    void retiredMailDatabaseRuntimeCanBeCollectedAcrossRepeatedReloads() throws Exception {
        // Keep the shared application's driver registered while retiring three
        // independent runtime loaders with synthetic full-text databases.
        org.h2.Driver.load();
        for (int reload = 0; reload < 3; reload++) {
            WeakReference<ClassLoader> retired = retireRuntime(reload);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (retired.get() != null) {
                    System.gc();
                    Thread.sleep(20);
                }
            });
        }
        assertTrue(DriverManager.drivers().anyMatch(driver -> driver == org.h2.Driver.load()));
    }

    @Test
    void cleanupDoesNotUnregisterTheSharedApplicationsDriver() throws Exception {
        Driver shared = org.h2.Driver.load();
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:shared-driver-fixture")) {
            H2Runtime.release();
            assertTrue(DriverManager.drivers().anyMatch(driver -> driver == shared));
            assertFalse(connection.isClosed());
        }
    }

    private static WeakReference<ClassLoader> retireRuntime(int id) throws Exception {
        URL core = H2Runtime.class.getProtectionDomain().getCodeSource().getLocation();
        URL h2 = org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation();
        URL tests = H2RuntimeTest.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new URLClassLoader(new URL[] {core, tests, h2}, ClassLoader.getPlatformClassLoader())) {
            Class<?> runtime = Class.forName(H2Runtime.class.getName(), true, loader);
            var start = runtime.getDeclaredMethod("start");
            var release = runtime.getDeclaredMethod("release");
            start.setAccessible(true);
            release.setAccessible(true);
            start.invoke(null);
            Class<?> driverClass = Class.forName("org.h2.Driver", true, loader);
            Driver driver = (Driver) driverClass.getMethod("load").invoke(null);
            var connect = Class.forName("org.fisk.swim.H2ReloadProbe", true, loader)
                    .getDeclaredMethod("connect", String.class);
            connect.setAccessible(true);
            try {
                try (Connection connection = driver.connect("jdbc:h2:mem:reload-fixture-" + id, new Properties())) {
                    Class<?> fullText = Class.forName("org.h2.fulltext.FullText", true, loader);
                    fullText.getMethod("init", Connection.class).invoke(null, connection);
                    try (var statement = connection.createStatement()) {
                        statement.execute("create table fixture(id int primary key, body varchar)");
                    }
                    fullText.getMethod("createIndex", Connection.class, String.class, String.class, String.class)
                            .invoke(null, connection, "PUBLIC", "FIXTURE", "BODY");
                    try (var statement = connection.createStatement()) {
                        statement.execute("insert into fixture values (1, 'Synthetic cached text')");
                    }
                }
                release.invoke(null);
                var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> connect.invoke(null, "jdbc:h2:mem:unregistered-fixture-" + id));
                assertInstanceOf(java.sql.SQLException.class, rejected.getCause());
                // A failed replacement can restore the previous core. Its driver
                // must register again even though the class was already initialized.
                start.invoke(null);
                try (Connection connection = (Connection) connect.invoke(null, "jdbc:h2:mem:restored-fixture-" + id)) {
                    assertFalse(connection.isClosed());
                }
            } finally {
                release.invoke(null);
            }
            return new WeakReference<>(loader);
        }
    }

}
