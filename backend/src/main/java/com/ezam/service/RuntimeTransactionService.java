package com.ezam.service;

import com.ezam.util.runtime.RuntimeStore;
import com.ezam.model.runtime.RuntimeData.DbState;
import java.sql.Connection;
import java.util.Objects;

/** Prepare files -> JDBC commit -> publish/reconcile; no claim of joint file/DB ACID. */
public final class RuntimeTransactionService {
    private final RuntimeStore store;
    private final TransactionManager transactions;
    public RuntimeTransactionService(RuntimeStore store, TransactionManager transactions) {
        this.store = Objects.requireNonNull(store); this.transactions = Objects.requireNonNull(transactions);
    }
    @FunctionalInterface public interface Work<T> { T run(Connection connection, RuntimeStore.Unit runtime, DbState before) throws java.sql.SQLException; }

    public <T> T execute(String code, String studentId, Work<T> work) throws Exception {
        return store.exclusive(unit -> {
            T response = transactions.execute(connection -> {
                DbState before = RuntimeDatabaseEvidence.lockedSnapshot(connection, code, studentId);
                unit.bindBefore(before);
                return work.run(connection, unit, before);
            });
            // A failure here leaves durable pending metadata. Never rollback a committed transaction.
            unit.finishCommitted();
            return response;
        });
    }
}
