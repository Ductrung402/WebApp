package com.ezam.model.runtime;

import com.ezam.model.TrangThaiBaiLam;
import com.ezam.model.TrangThaiPhongThi;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable private sidecars matching legacy camelCase JSON. Never serialize an entire Submission. */
public final class RuntimeData {
    private RuntimeData() { }
    public enum Kind { RUN, RECOVERY, GRADE }

    private static void valid(boolean value) { if (!value) throw new IllegalArgumentException("Invalid runtime data."); }
    private static void text(String... values) { for (String v : values) valid(v != null && !v.isBlank() && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(v)); }
    public static void code(String code) { valid(code != null && code.matches("[A-Z0-9]{6}")); }

    public record Question(String idCauHoi, String noiDung, String dapAnA, String dapAnB, String dapAnC, String dapAnD) {
        public Question { text(idCauHoi, noiDung, dapAnA, dapAnB, dapAnC, dapAnD); }
    }

    public record Run(String runId, String idPhongThi, String idDeThi, String maCodePhongThi, String tenDeThi,
            String tenLop, int thoiGianLamBai, String trangThaiPhong, long startsAt, long endsAt, long serverTime,
            List<Question> cauHoiList) {
        public Run {
            text(runId, idPhongThi, idDeThi, tenDeThi, tenLop); code(maCodePhongThi);
            valid(thoiGianLamBai > 0 && startsAt >= 0 && serverTime >= 0 && "DANG_THI".equals(trangThaiPhong));
            valid(endsAt == Math.addExact(startsAt, Math.multiplyExact((long) thoiGianLamBai, 60_000)));
            cauHoiList = List.copyOf(cauHoiList); valid(!cauHoiList.isEmpty());
            valid(new HashSet<>(cauHoiList.stream().map(Question::idCauHoi).toList()).size() == cauHoiList.size());
        }
    }

    public record Receipt(String submissionId, String runId, String idHs, long receivedAt, boolean receivedLate, String reason, boolean duplicate) {
        public Receipt { text(submissionId, runId, idHs); valid(receivedAt >= 0 && ("manual".equals(reason) || "timeout".equals(reason))); }
        @Override public String toString() { return "Receipt[redacted]"; }
    }

    public record Submission(String submissionId, String runId, String idHs, long receivedAt, boolean receivedLate,
            String reason, boolean duplicate, Map<String, String> answers) {
        public Submission {
            new Receipt(submissionId, runId, idHs, receivedAt, receivedLate, reason, duplicate);
            answers = Map.copyOf(answers);
            for (var answer : answers.entrySet()) { text(answer.getKey()); valid(List.of("A", "B", "C", "D").contains(answer.getValue())); }
        }
        public Receipt receipt(boolean retry) { return new Receipt(submissionId, runId, idHs, receivedAt, receivedLate, reason, retry); }
        @Override public String toString() { return "Submission[redacted]"; }
    }

    public record Recovery(String idHs, String hoVaTen, String maCodePhongThi, String idPhongThi, TrangThaiBaiLam trangThai,
            long stateVersion, long stateChangedAt, Long disconnectedAt, Long frozenRemainingSeconds) {
        public Recovery {
            text(idHs, hoVaTen, idPhongThi); code(maCodePhongThi); Objects.requireNonNull(trangThai);
            valid(stateVersion >= 0 && stateChangedAt >= 0 && (disconnectedAt == null || disconnectedAt >= 0)
                    && (frozenRemainingSeconds == null || frozenRemainingSeconds >= 0));
        }
    }

    public record Grade(String idHs, double diemSo, int soCauDung, int tongSoCau, TrangThaiBaiLam trangThai) {
        public Grade {
            text(idHs); valid(Double.isFinite(diemSo) && diemSo >= 0 && diemSo <= 10);
            valid(tongSoCau > 0 && soCauDung >= 0 && soCauDung <= tongSoCau && trangThai == TrangThaiBaiLam.HOAN_THANH);
        }
    }

    public record StoredGrade(String idPhongThi, String runId, Grade result) {
        public StoredGrade { text(idPhongThi, runId); Objects.requireNonNull(result); }
    }

    /** Fresh, scoped PostgreSQL evidence, including result PK; never user-supplied proof. */
    public record DbState(String idPhongThi, String maCodePhongThi, String idDeThi, int thoiGianLamBai,
            TrangThaiPhongThi roomState, String idHs, String idKq, TrangThaiBaiLam resultState, Double diemSo) {
        public DbState {
            text(idPhongThi, idDeThi); code(maCodePhongThi); valid(thoiGianLamBai > 0); Objects.requireNonNull(roomState);
            if (idHs == null) valid(idKq == null && resultState == null && diemSo == null);
            else { text(idHs, idKq); Objects.requireNonNull(resultState); valid(diemSo != null && Double.isFinite(diemSo)); }
        }
        public boolean sameContext(DbState other) {
            return other != null && idPhongThi.equals(other.idPhongThi) && maCodePhongThi.equals(other.maCodePhongThi)
                    && idDeThi.equals(other.idDeThi) && thoiGianLamBai == other.thoiGianLamBai
                    && Objects.equals(idHs, other.idHs) && Objects.equals(idKq, other.idKq);
        }
        public boolean matches(Kind kind, DbState other) {
            return sameContext(other) && (kind == Kind.RUN ? roomState == other.roomState
                    : resultState == other.resultState && Objects.equals(diemSo, other.diemSo));
        }
    }
}
