package com.ezam.util;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DBUtilTest {
    private DatabaseConfig database(String query) {
        return DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL",
                "postgresql://fixture_user:fixture_password@localhost:5432/synthetic" + query));
    }

    @Test void buildsBoundedPoolWithoutOpeningConnection() {
        var config = DBUtil.poolConfiguration(database(""), 10, 30_000);
        assertEquals(10, config.getMaximumPoolSize()); assertEquals(0, config.getMinimumIdle());
        assertEquals(30_000, config.getConnectionTimeout()); assertEquals(5_000, config.getValidationTimeout());
        assertTrue(config.isAutoCommit()); assertFalse(config.isReadOnly()); assertFalse(config.isRegisterMbeans());
        assertEquals(1, config.getInitializationFailTimeout());
        assertEquals("jdbc:postgresql://localhost:5432/synthetic", config.getJdbcUrl());
        assertFalse(config.getJdbcUrl().contains("fixture_password"));
        assertEquals("fixture_user", config.getDataSourceProperties().getProperty("user"));
        assertEquals("fixture_password", config.getDataSourceProperties().getProperty("password"));
        assertEquals("5", config.getDataSourceProperties().getProperty("connectTimeout"));
        assertEquals("30", config.getDataSourceProperties().getProperty("socketTimeout"));
    }

    @Test void keepsExplicitTlsAndDriverTimeoutProperties() {
        var config = DBUtil.poolConfiguration(database("?sslmode=require&connectTimeout=7&socketTimeout=19"), 1, 250);
        assertEquals("require", config.getDataSourceProperties().getProperty("sslmode"));
        assertEquals("7", config.getDataSourceProperties().getProperty("connectTimeout"));
        assertEquals("19", config.getDataSourceProperties().getProperty("socketTimeout"));
        assertEquals(250, config.getValidationTimeout());
    }

    @ParameterizedTest @ValueSource(ints = {0, -1})
    void rejectsInvalidMaximum(int maximum) {
        assertThrows(IllegalArgumentException.class, () -> DBUtil.poolConfiguration(database(""), maximum, 1000));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, 249})
    void rejectsUnboundedOrTooShortBorrowTimeout(long timeout) {
        assertThrows(IllegalArgumentException.class, () -> DBUtil.poolConfiguration(database(""), 1, timeout));
    }

    @Test void rejectsMissingDatabaseConfiguration() {
        assertThrows(NullPointerException.class, () -> DBUtil.poolConfiguration(null, 1, 1000));
    }
}
