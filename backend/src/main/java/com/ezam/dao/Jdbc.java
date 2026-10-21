package com.ezam.dao;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Internal resource handling only; never acquires, commits, rolls back or closes a Connection. */
final class Jdbc {
    private Jdbc() { }

    @FunctionalInterface interface Mapper<T> { T map(ResultSet row) throws SQLException; }

    static void requireTransaction(Connection connection) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("DAO write/lock requires a Service transaction.", "25000");
    }

    static <T> List<T> query(Connection connection, String sql, Mapper<T> mapper, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rows.next()) result.add(mapper.map(rows));
                return result;
            }
        }
    }

    static <T> Optional<T> one(Connection connection, String sql, Mapper<T> mapper, Object... values) throws SQLException {
        List<T> result = query(connection, sql, mapper, values);
        if (result.size() > 1) throw new SQLDataException("Expected one scoped row; found ambiguous data.", "21000");
        return result.stream().findFirst();
    }

    static int update(Connection connection, String sql, Object... values) throws SQLException {
        requireTransaction(connection);
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement.executeUpdate();
        }
    }
}
