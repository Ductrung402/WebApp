package com.ezam.dao;

import com.ezam.model.GiaoVien;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaoResourceTest {
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final ResultSet rows = mock(ResultSet.class);

    private void query() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
    }

    @Test void readClosesRowsAndStatementButNeverConnectionOrTransaction() throws SQLException {
        query(); assertTrue(new TeacherDAO().findById(connection, "missing").isEmpty());
        verify(rows).close(); verify(statement).close();
        verify(connection, never()).close(); verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void queryFailurePropagatesAndStillClosesStatement() throws SQLException {
        query(); var original = new SQLException("synthetic", "08006"); when(statement.executeQuery()).thenThrow(original);
        assertSame(original, assertThrows(SQLException.class, () -> new TeacherDAO().findById(connection, "id")));
        verify(statement).close(); verify(connection, never()).close(); verify(connection, never()).rollback();
    }

    @Test void mapperFailureAlsoClosesRowsAndStatement() throws SQLException {
        query(); when(rows.next()).thenReturn(true);
        var original = new SQLException("synthetic mapping"); when(rows.getString("id_gv")).thenThrow(original);
        assertSame(original, assertThrows(SQLException.class, () -> new TeacherDAO().findById(connection, "id")));
        verify(rows).close(); verify(statement).close(); verify(connection, never()).close();
    }

    @Test void writeFailureIsNotChangedToFalseAndDoesNotCommitOrRollback() throws SQLException {
        query(); var original = new SQLException("synthetic", "23502"); when(statement.executeUpdate()).thenThrow(original);
        assertSame(original, assertThrows(SQLException.class, () -> new TeacherDAO().insert(connection, new GiaoVien())));
        verify(statement).close(); verify(connection, never()).commit(); verify(connection, never()).rollback(); verify(connection, never()).close();
    }

    @Test void cleanupErrorsAreSuppressedOnSqlFailure() throws SQLException {
        query(); var original = new SQLException("synthetic"); var close = new SQLException("close");
        when(statement.executeQuery()).thenThrow(original); doThrow(close).when(statement).close();
        assertSame(original, assertThrows(SQLException.class, () -> new TeacherDAO().findById(connection, "id")));
        assertArrayEquals(new Throwable[] {close}, original.getSuppressed());
    }

    @Test void writeAndLockRejectAutoCommitBeforeSql() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(true);
        assertEquals("25000", assertThrows(SQLException.class, () -> new TeacherDAO().insert(connection, new GiaoVien())).getSQLState());
        assertThrows(SQLException.class, () -> new RoomDAO().lockByCode(connection, "AB12CD"));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void userInputIsBoundAsDataAndCannotChangeSqlPredicate() throws SQLException {
        query(); String injection = "' OR 1=1 --";
        new ExamDAO().findOwned(connection, injection, injection);
        var sql = ArgumentCaptor.forClass(String.class); verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains(injection));
        verify(statement).setObject(1, injection); verify(statement).setObject(2, injection);
    }

    @Test void publicQuestionQueryNeverSelectsOrReadsAnswerKey() throws SQLException {
        query(); when(rows.next()).thenReturn(true, false);
        assertEquals(1, new ExamDAO().listPublicQuestions(connection, "exam").size());
        var sql = ArgumentCaptor.forClass(String.class); verify(connection).prepareStatement(sql.capture());
        assertFalse(sql.getValue().contains("dap_an_dung")); assertFalse(sql.getValue().contains("*"));
        verify(rows, never()).getString("dap_an_dung"); verify(statement).close(); verify(rows).close();
        assertEquals(6, ExamDAO.PublicQuestion.class.getRecordComponents().length);
    }

    @Test void sqlTextWithoutServerConstraintMetadataIsNotAConflictMatch() {
        var fake = new SQLException("giao_vien_email_key phong_thi_ma_code_phong_thi_key", "23505");
        assertFalse(SqlErrors.isEmailConflict(fake)); assertFalse(SqlErrors.isRoomCodeConflict(fake));
        assertFalse(SqlErrors.isEmailConflict(null));
    }
}
