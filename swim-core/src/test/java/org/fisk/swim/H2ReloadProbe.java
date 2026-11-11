package org.fisk.swim;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Runs inside the throwaway runtime loader, matching JDBC's caller visibility rules. */
final class H2ReloadProbe {
    static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url);
    }
}
