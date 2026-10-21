package com.ezam.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Service transaction boundary. Stateless; each invocation borrows its own connection. */
public final class TransactionManager {
    private final ConnectionProvider connections;

    public TransactionManager(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "Connection provider is required.");
    }

    /**
     * Work runs synchronously on the calling thread and passes this same connection to every DAO.
     * Work must not commit, rollback, close, retain or hand the connection to another thread.
     * Return detached data, never a Connection, Statement or ResultSet. Nested transactions are
     * not supported: compose DAO calls inside one execute, not another execute inside its callback.
     * Broadcast only after this method returns successfully; no automatic retry on commit failure.
     */
    public <T> T execute(Work<T> work) throws SQLException {
        Objects.requireNonNull(work, "Transaction work is required.");
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error original) {
                try {
                    connection.rollback();
                } catch (SQLException | RuntimeException | Error rollbackFailure) {
                    if (rollbackFailure != original) original.addSuppressed(rollbackFailure);
                }
                throw original;
            }
            // Do not set autoCommit(true) here: JDBC may commit pending work on that transition.
            // Hikari resets connection state on close; try-with-resources suppresses close errors.
        }
    }

    @FunctionalInterface
    public interface ConnectionProvider {
        Connection getConnection() throws SQLException;
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }
}
