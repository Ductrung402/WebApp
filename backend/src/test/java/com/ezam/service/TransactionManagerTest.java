package com.ezam.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionManagerTest {
    private final TransactionManager.ConnectionProvider provider = mock(TransactionManager.ConnectionProvider.class);
    private final Connection connection = mock(Connection.class);
    private final TransactionManager transactions = new TransactionManager(provider);

    private void available() throws SQLException { when(provider.getConnection()).thenReturn(connection); }

    @Test void commitsExactlyOnceAfterAllWorkAndCloses() throws SQLException {
        available();
        Runnable firstDao = mock(Runnable.class);
        Runnable secondDao = mock(Runnable.class);
        assertEquals("detached", transactions.execute(c -> {
            assertSame(connection, c);
            firstDao.run(); secondDao.run(); return "detached";
        }));
        var order = inOrder(provider, connection, firstDao, secondDao);
        order.verify(provider).getConnection(); order.verify(connection).setAutoCommit(false);
        order.verify(firstDao).run(); order.verify(secondDao).run();
        order.verify(connection).commit(); order.verify(connection).close();
        verify(connection, never()).rollback(); verify(connection, never()).setAutoCommit(true);
        verifyNoMoreInteractions(connection, provider);
    }

    @Test void sqlFailureRollsBackAndPreservesSqlState() throws SQLException {
        available(); SQLException original = new SQLException("synthetic", "23502");
        assertSame(original, assertThrows(SQLException.class, () -> transactions.execute(c -> { throw original; })));
        var order = inOrder(connection);
        order.verify(connection).setAutoCommit(false); order.verify(connection).rollback(); order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test void businessFailureRollsBack() throws SQLException {
        available(); var original = new IllegalArgumentException("synthetic validation");
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> transactions.execute(c -> { throw original; })));
        verify(connection).rollback(); verify(connection).close(); verify(connection, never()).commit();
    }

    @Test void errorAlsoRollsBackAndCloses() throws SQLException {
        available(); var original = new AssertionError("synthetic");
        assertSame(original, assertThrows(AssertionError.class, () -> transactions.execute(c -> { throw original; })));
        verify(connection).rollback(); verify(connection).close();
    }

    @Test void rollbackAndCloseFailuresAreSuppressedOnOriginal() throws SQLException {
        available(); var original = new SQLException("work", "23502");
        var rollback = new SQLException("rollback"); var close = new SQLException("close");
        doThrow(rollback).when(connection).rollback(); doThrow(close).when(connection).close();
        assertSame(original, assertThrows(SQLException.class, () -> transactions.execute(c -> { throw original; })));
        assertArrayEquals(new Throwable[] {rollback, close}, original.getSuppressed());
        verify(connection, never()).commit(); verify(connection, never()).setAutoCommit(true);
    }

    @Test void uncheckedRollbackFailureDoesNotMaskBusinessFailure() throws SQLException {
        available(); var original = new IllegalStateException("work"); var rollback = new IllegalStateException("rollback");
        doThrow(rollback).when(connection).rollback();
        assertSame(original, assertThrows(IllegalStateException.class, () -> transactions.execute(c -> { throw original; })));
        assertArrayEquals(new Throwable[] {rollback}, original.getSuppressed()); verify(connection).close();
    }

    @Test void rollbackRethrowingSameFailureDoesNotCauseSelfSuppression() throws SQLException {
        available(); var original = new SQLException("synthetic"); doThrow(original).when(connection).rollback();
        assertSame(original, assertThrows(SQLException.class, () -> transactions.execute(c -> { throw original; })));
        assertEquals(0, original.getSuppressed().length); verify(connection).close();
    }

    @Test void commitFailureAttemptsRollbackButNeverRetriesWork() throws SQLException {
        available(); var original = new SQLException("commit"); doThrow(original).when(connection).commit();
        AtomicInteger calls = new AtomicInteger();
        assertSame(original, assertThrows(SQLException.class, () -> transactions.execute(c -> calls.incrementAndGet())));
        assertEquals(1, calls.get()); verify(connection).commit(); verify(connection).rollback(); verify(connection).close();
    }

    @Test void closeFailureAfterSuccessfulCommitDoesNotRollbackCommittedWork() throws SQLException {
        available(); var close = new SQLException("close"); doThrow(close).when(connection).close();
        assertSame(close, assertThrows(SQLException.class, () -> transactions.execute(c -> "ok")));
        verify(connection).commit(); verify(connection, never()).rollback();
    }

    @Test void autoCommitSetupFailureClosesWithoutRunningWork() throws SQLException {
        available(); var setup = new SQLException("setup"); doThrow(setup).when(connection).setAutoCommit(false);
        AtomicInteger calls = new AtomicInteger();
        assertSame(setup, assertThrows(SQLException.class, () -> transactions.execute(c -> calls.incrementAndGet())));
        assertEquals(0, calls.get()); verify(connection).close();
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void borrowFailureIsNotWrappedOrRetried() throws SQLException {
        var failure = new SQLException("pool unavailable"); when(provider.getConnection()).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> transactions.execute(c -> "unused")));
        verify(provider).getConnection(); verifyNoInteractions(connection);
    }

    @Test void nextInvocationBorrowsItsOwnHandleEvenAfterFailure() throws SQLException {
        Connection next = mock(Connection.class); when(provider.getConnection()).thenReturn(connection, next);
        assertThrows(IllegalStateException.class, () -> transactions.execute(c -> { throw new IllegalStateException(); }));
        assertEquals("second", transactions.execute(c -> { assertSame(next, c); return "second"; }));
        verify(provider, times(2)).getConnection(); verify(connection).rollback(); verify(connection).close();
        verify(next).commit(); verify(next).close();
    }

    @Test void nullResultStillCommits() throws SQLException {
        available(); assertNull(transactions.execute(c -> null)); verify(connection).commit(); verify(connection).close();
    }

    @Test void rejectsMissingDependenciesBeforeBorrowing() {
        assertThrows(NullPointerException.class, () -> new TransactionManager(null));
        assertThrows(NullPointerException.class, () -> transactions.execute(null)); verifyNoInteractions(provider);
    }
}
