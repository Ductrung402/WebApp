package com.ezam.dto;

import com.ezam.model.TrangThaiBaiLam;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.*;
import com.google.gson.*;
import java.util.*;

/** Strict submission contract. No client score/count/timestamp or answer key is accepted. */
public final class SubmissionDTO {
    private SubmissionDTO() { }
    public record Answer(String idCauHoi, String dapAnChon) {
        public Answer {
            idCauHoi = DtoValidator.nonEmpty(idCauHoi, "idCauHoi");
            if (dapAnChon != null && !Set.of("A", "B", "C", "D").contains(dapAnChon))
                throw invalid("Invalid answer choice.");
        }
        @Override public String toString() { return "Answer[redacted]"; }
    }
    public record Request(String idHs, String idPhongThi, List<Answer> danhSachCauTraLoi) {
        public Request {
            idHs = DtoValidator.nonEmpty(idHs, "idHs");
            idPhongThi = DtoValidator.nonEmpty(idPhongThi, "idPhongThi");
            if (danhSachCauTraLoi == null || danhSachCauTraLoi.stream().anyMatch(Objects::isNull)) throw invalid("Invalid answer list.");
            danhSachCauTraLoi = List.copyOf(danhSachCauTraLoi);
            if (new HashSet<>(danhSachCauTraLoi.stream().map(Answer::idCauHoi).toList()).size() != danhSachCauTraLoi.size())
                throw invalid("Question IDs must not be duplicated.");
        }
        /** Null and omitted answers both count as wrong; private snapshots contain A-D only. */
        public Map<String, String> selectedAnswers() {
            var selected = new LinkedHashMap<String, String>();
            for (Answer answer : danhSachCauTraLoi) if (answer.dapAnChon() != null) selected.put(answer.idCauHoi(), answer.dapAnChon());
            return Map.copyOf(selected);
        }
        @Override public String toString() { return "SubmissionRequest[redacted]"; }
    }
    /** Prepared for the next task's notification adapter; contains neither key nor private answers. */
    public record Submitted(String idHs, double diemSo, int soCauDung, int tongSoCau, TrangThaiBaiLam trangThai,
            String idPhongThi, String maCodePhongThi, String hoVaTen) { }
    public record Outcome(Grade result, Receipt receipt, Submitted event) { }

    public static Request parse(String body) {
        var aliases = Map.of("id_hs", "idHs", "id_phong_thi", "idPhongThi", "danh_sach_cau_tra_loi", "danhSachCauTraLoi");
        var allowed = Set.of("idHs", "idPhongThi", "danhSachCauTraLoi", "id_hs", "id_phong_thi", "danh_sach_cau_tra_loi");
        var object = DtoValidator.aliases(DtoValidator.object(body, allowed), aliases);
        var list = DtoValidator.required(object, "danhSachCauTraLoi");
        if (!list.isJsonArray()) throw invalid("Answers must be an array.");
        var answers = new ArrayList<Answer>();
        for (JsonElement item : list.getAsJsonArray()) {
            var answer = DtoValidator.aliases(DtoValidator.object(item, Set.of("idCauHoi", "dapAnChon", "id_cau_hoi", "dap_an_chon")),
                    Map.of("id_cau_hoi", "idCauHoi", "dap_an_chon", "dapAnChon"));
            DtoValidator.required(answer, "dapAnChon"); // Required but nullable, like the Pydantic baseline.
            answers.add(new Answer(DtoValidator.string(answer, "idCauHoi", false), DtoValidator.string(answer, "dapAnChon", true)));
        }
        return new Request(DtoValidator.string(object, "idHs", false), DtoValidator.string(object, "idPhongThi", false), answers);
    }
    private static AuthException invalid(String message) { return new AuthException(422, message); }
}
