package com.ezam.service;

import com.ezam.dao.RoomDAO;
import com.ezam.dao.StudentDAO;
import com.ezam.dao.SubmissionDAO;
import com.ezam.model.runtime.RuntimeData.DbState;
import com.ezam.util.runtime.RuntimeStore;
import com.ezam.util.runtime.RuntimeUnavailableException;

/** Scoped fresh DB truth. Call under RuntimeStore's exclusive monitor, before any external row lock. */
public final class RuntimeDatabaseEvidence implements RuntimeStore.Evidence {
    private final TransactionManager transactions;
    public RuntimeDatabaseEvidence(TransactionManager transactions) { this.transactions = transactions; }

    @Override public DbState committed(String code, String studentId) {
        try {
            return transactions.execute(connection -> lockedSnapshot(connection, code, studentId));
        } catch (Exception unavailable) { throw new RuntimeUnavailableException(); }
    }

    /** Same transaction/row locks used by the write coordinator; no separate stale snapshot. */
    public static DbState lockedSnapshot(java.sql.Connection connection, String code, String studentId) throws java.sql.SQLException {
                var room = new RoomDAO().lockByCode(connection, code).orElseThrow(RuntimeUnavailableException::new);
                if (studentId == null) return new DbState(room.getIdPhongThi(), code, room.getIdDeThi(), room.getThoiGianLamBai(), room.getTrangThaiPhong(), null, null, null, null);
                new StudentDAO().lockInRoom(connection, studentId, room.getIdPhongThi()).orElseThrow(RuntimeUnavailableException::new);
                var result = new SubmissionDAO().lockInRoom(connection, studentId, room.getIdPhongThi()).orElseThrow(RuntimeUnavailableException::new);
                return new DbState(room.getIdPhongThi(), code, room.getIdDeThi(), room.getThoiGianLamBai(), room.getTrangThaiPhong(),
                        studentId, result.getIdKq(), result.getTrangThai(), result.getDiemSo());
    }
}
