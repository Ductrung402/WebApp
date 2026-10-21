package com.ezam.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

/** One application-owned pool. Borrow per unit of work; close once at application shutdown. */
public final class DBUtil implements AutoCloseable {
    private final HikariDataSource pool;

    private DBUtil(HikariConfig configuration) {
        pool = new HikariDataSource(configuration);
    }

    public static DBUtil open(DatabaseConfig database) {
        return open(database, 10, 30_000);
    }

    /** Explicit pool limits, also usable by an isolated integration-test lifecycle. */
    public static DBUtil open(DatabaseConfig database, int maximumPoolSize, long connectionTimeoutMillis) {
        return new DBUtil(poolConfiguration(database, maximumPoolSize, connectionTimeoutMillis));
    }

    static HikariConfig poolConfiguration(DatabaseConfig database, int maximumPoolSize,
            long connectionTimeoutMillis) {
        Objects.requireNonNull(database, "Database configuration is required.");
        if (maximumPoolSize < 1 || connectionTimeoutMillis < 250) {
            throw new IllegalArgumentException("Invalid pool limits.");
        }
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName("EZamDatabase");
        configuration.setJdbcUrl(database.getJdbcUrl());
        Properties properties = database.toConnectionProperties();
        // Bounded physical connection establishment; explicit deployment options take precedence.
        properties.putIfAbsent("connectTimeout", "5");
        properties.putIfAbsent("socketTimeout", "30");
        configuration.setDataSourceProperties(properties);
        configuration.setMaximumPoolSize(maximumPoolSize);
        configuration.setMinimumIdle(0);
        configuration.setConnectionTimeout(connectionTimeoutMillis);
        configuration.setValidationTimeout(Math.min(5_000, connectionTimeoutMillis));
        configuration.setInitializationFailTimeout(1);
        configuration.setAutoCommit(true);
        configuration.setReadOnly(false);
        configuration.setRegisterMbeans(false);
        return configuration;
    }

    /** Caller owns the returned handle; close returns it to Hikari, not to a global shared field. */
    public Connection getConnection() throws SQLException {
        return pool.getConnection();
    }

    public boolean isClosed() {
        return pool.isClosed();
    }

    @Override
    public void close() {
        pool.close();
    }

    @Override
    public String toString() {
        return "DBUtil[redacted]";
    }
}
