package com.ezam.dao;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;

/** Classifies exact server metadata, never parses a localized message or retries a transaction. */
public final class SqlErrors {
    private SqlErrors() { }

    public static boolean isEmailConflict(SQLException error) {
        return unique(error, "giao_vien", "giao_vien_email_key");
    }

    public static boolean isRoomCodeConflict(SQLException error) {
        return unique(error, "phong_thi", "phong_thi_ma_code_phong_thi_key");
    }

    private static boolean unique(SQLException error, String table, String constraint) {
        Set<SQLException> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SQLException next = error; next != null && seen.add(next); next = next.getNextException()) {
            if (next instanceof PSQLException postgres && "23505".equals(next.getSQLState())) {
                var detail = postgres.getServerErrorMessage();
                if (detail != null && table.equals(detail.getTable()) && constraint.equals(detail.getConstraint())) return true;
            }
        }
        return false;
    }
}
