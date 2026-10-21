package com.ezam.integration;

import com.ezam.service.TransactionManager;
import com.ezam.util.DBUtil;
import com.ezam.util.DatabaseConfig;
import com.google.gson.GsonBuilder;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL tests. No fallback to DATABASE_URL or the application's six tables. */
class PostgresTransactionIT {
    private static DatabaseConfig database;
    private static final String APPLICATION = "ezam-t12-pool";
    private static final Map<String, Object> proof = new LinkedHashMap<>();

    @BeforeAll static void requireIsolatedDatabase() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        try {
            URI endpoint = new URI(url == null ? "" : url);
            if (!"postgresql".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                    || !"/ezam_t12_test".equals(endpoint.getPath()) || endpoint.getPort() < 1) {
                throw new IllegalArgumentException();
            }
            database = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
        } catch (Exception invalid) {
            throw new IllegalStateException("Use the isolated PostgreSQL test runner; no application DB fallback.");
        }
        try (Connection control = observer()) {
            assertEquals("ezam_t12_test", scalar(control, "SELECT current_database()"));
            assertEquals("EZam isolated transaction test T1.2", scalar(control,
                    "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = current_database()"));
            assertEquals(0, number(control,
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                    + "AND table_name IN ('giao_vien','de_thi','cau_hoi','phong_thi','hoc_sinh','ket_qua_thi')"));
            proof.put("databaseIsolation", "dedicated loopback cluster, marker verified, no application tables");
            proof.put("serverVersion", scalar(control, "SHOW server_version_num"));
        }
    }

    private static Connection observer() throws SQLException {
        var properties = database.toConnectionProperties();
        properties.setProperty("ApplicationName", "ezam-t12-observer");
        return DriverManager.getConnection(database.getJdbcUrl(), properties);
    }

    private DBUtil pool(int maximum) {
        return DBUtil.open(database, maximum, 1_000);
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }

    private static int number(Connection connection, String sql) throws SQLException {
        return Integer.parseInt(scalar(connection, sql));
    }

    // Test fixture operations stand in for DAO calls; they close statements, never the supplied Connection.
    private static void insertParent(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO t12_parent(id, title) VALUES (?, ?)")) {
            statement.setString(1, id); statement.setString(2, "Synthetic parent"); assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertChild(Connection connection, String parent, String text) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO t12_child(id, parent_id, content) VALUES (?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, parent);
            statement.setString(3, text); assertEquals(1, statement.executeUpdate());
        }
    }

    private static int count(Connection connection, String table, String column, String id) throws SQLException {
        // Table/column identifiers are fixture constants only; values always use parameters.
        if (!(table.equals("t12_parent") && column.equals("id")
                || table.equals("t12_child") && column.equals("parent_id"))) throw new IllegalArgumentException();
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getInt(1); }
        }
    }

    @Test void allWritesCommitTogetherOnOneConnection() throws Exception {
        String id = UUID.randomUUID().toString();
        try (DBUtil pool = pool(1); Connection observer = observer()) {
            int pid = new TransactionManager(pool::getConnection).execute(connection -> {
                int firstPid = number(connection, "SELECT pg_backend_pid()");
                insertParent(connection, id);
                for (int i = 0; i < 3; i++) insertChild(connection, id, "Synthetic child " + i);
                assertEquals(firstPid, number(connection, "SELECT pg_backend_pid()"));
                assertEquals(1, count(connection, "t12_parent", "id", id));
                assertEquals(3, count(connection, "t12_child", "parent_id", id));
                assertEquals(0, count(observer, "t12_parent", "id", id));
                return firstPid;
            });
            assertEquals(1, count(observer, "t12_parent", "id", id));
            assertEquals(3, count(observer, "t12_child", "parent_id", id));
            try (Connection returned = pool.getConnection()) {
                assertTrue(returned.getAutoCommit()); assertEquals(pid, number(returned, "SELECT pg_backend_pid()"));
            }
            proof.put("commit", Map.of("parentRows", 1, "childRows", 3, "samePhysicalConnectionReused", true,
                    "uncommittedWritesInvisibleToObserver", true));
        }
    }

    @Test void notNullFailureAfterTwoWritesRollsBackEverything() throws Exception {
        String id = UUID.randomUUID().toString();
        try (DBUtil pool = pool(1); Connection observer = observer()) {
            var transactions = new TransactionManager(pool::getConnection);
            SQLException failure = assertThrows(SQLException.class, () -> transactions.execute(connection -> {
                insertParent(connection, id); insertChild(connection, id, "Valid first child");
                assertEquals(1, count(connection, "t12_child", "parent_id", id));
                insertChild(connection, id, null); return null;
            }));
            assertEquals("23502", failure.getSQLState());
            assertEquals(0, count(observer, "t12_parent", "id", id));
            assertEquals(0, count(observer, "t12_child", "parent_id", id));
            int nextResult = transactions.execute(connection -> number(connection, "SELECT 1"));
            assertEquals(1, nextResult);
            proof.put("sqlRollback", Map.of("sqlState", failure.getSQLState(), "parentRows", 0, "childRows", 0,
                    "nextTransactionSucceeds", true));
        }
    }

    @Test void businessValidationFailureAlsoRollsBackRealWrites() throws Exception {
        String id = UUID.randomUUID().toString();
        try (DBUtil pool = pool(1); Connection observer = observer()) {
            assertThrows(IllegalStateException.class, () -> new TransactionManager(pool::getConnection).execute(connection -> {
                insertParent(connection, id); insertChild(connection, id, "Valid child");
                throw new IllegalStateException("Synthetic response validation failed");
            }));
            assertEquals(0, count(observer, "t12_parent", "id", id));
            assertEquals(0, count(observer, "t12_child", "parent_id", id));
            proof.put("businessRollback", Map.of("parentRows", 0, "childRows", 0));
        }
    }

    @Test void poolReturnsAndResetsConnectionAfterRollback() throws Exception {
        try (DBUtil pool = pool(1)) {
            int pid;
            try (Connection handle = pool.getConnection()) { pid = number(handle, "SELECT pg_backend_pid()"); }
            assertThrows(IllegalStateException.class, () -> new TransactionManager(pool::getConnection).execute(connection -> {
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setReadOnly(true); number(connection, "SELECT 1");
                throw new IllegalStateException("Synthetic failure");
            }));
            try (Connection handle = pool.getConnection()) {
                assertEquals(pid, number(handle, "SELECT pg_backend_pid()"));
                assertTrue(handle.getAutoCommit()); assertFalse(handle.isReadOnly());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, handle.getTransactionIsolation());
            }
            proof.put("poolReset", Map.of("samePhysicalConnection", true, "autoCommit", true, "readOnly", false,
                    "isolation", "READ_COMMITTED"));
        }
    }

    @Test void concurrentUnitsOfWorkNeverSharePhysicalConnection() throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2); CountDownLatch release = new CountDownLatch(1);
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try (DBUtil pool = pool(2); Connection observer = observer()) {
            var transactions = new TransactionManager(pool::getConnection);
            java.util.concurrent.Callable<Integer> a = () -> transactions.execute(c -> heldWork(c, first, ready, release));
            java.util.concurrent.Callable<Integer> b = () -> transactions.execute(c -> heldWork(c, second, ready, release));
            var left = workers.submit(a); var right = workers.submit(b);
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                assertEquals(0, count(observer, "t12_parent", "id", first));
                assertEquals(0, count(observer, "t12_parent", "id", second));
            } finally { release.countDown(); }
            assertNotEquals(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
            assertEquals(1, count(observer, "t12_parent", "id", first));
            assertEquals(1, count(observer, "t12_parent", "id", second));
            proof.put("concurrency", Map.of("workers", 2, "distinctPhysicalConnections", true, "committedParents", 2));
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static int heldWork(Connection connection, String id, CountDownLatch ready, CountDownLatch release)
            throws SQLException {
        insertParent(connection, id); int pid = number(connection, "SELECT pg_backend_pid()"); ready.countDown();
        try {
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic barrier timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Test interrupted", interrupted);
        }
        return pid;
    }

    @Test void exhaustedPoolTimesOutThenReturnsAndShutsDownCleanly() throws Exception {
        DBUtil pool = pool(1);
        try {
            try (Connection held = pool.getConnection()) {
                assertThrows(SQLException.class, pool::getConnection);
                assertFalse(held.isClosed());
            }
            try (Connection returned = pool.getConnection()) { assertEquals(1, number(returned, "SELECT 1")); }
            assertEquals("DBUtil[redacted]", pool.toString());
        } finally { pool.close(); }
        pool.close(); // shutdown must be idempotent
        assertTrue(pool.isClosed()); assertThrows(SQLException.class, pool::getConnection);
        try (Connection observer = observer()) {
            assertEquals(0, number(observer, "SELECT count(*) FROM pg_stat_activity WHERE application_name = '" + APPLICATION + "'"));
        }
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hikariThreadAlive() && System.nanoTime() < limit) Thread.sleep(20);
        assertFalse(hikariThreadAlive());
        proof.put("shutdown", Map.of("closed", true, "remainingPoolConnections", 0,
                "remainingHikariThreads", 0, "borrowAfterCloseRejected", true, "exhaustionTimeout", true));
    }

    private static boolean hikariThreadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("EZamDatabase"));
    }

    @AfterAll static void writeEvidence() throws Exception {
        Path directory = Path.of("target", "verification"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("T1_2_postgres.json"), new GsonBuilder().setPrettyPrinting().create().toJson(proof));
    }
}
