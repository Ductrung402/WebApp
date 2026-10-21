package com.ezam.dto;

import com.ezam.model.*;
import com.ezam.util.*;
import com.google.gson.*;
import java.util.*;

/** Teacher-only question DTOs include keys. Never use them for student Socket/HTTP payloads. */
public final class ExamDTO {
    private ExamDTO() { }
    private static final Map<String, String> EXAM_ALIASES = Map.of("ten_de_thi", "tenDeThi", "kieu_tai_lieu", "kieuTaiLieu", "noi_dung_tai_lieu", "noiDungTaiLieu", "cau_hoi_list", "cauHoiList");
    private static final Map<String, String> QUESTION_ALIASES = Map.of("noi_dung", "noiDung", "dap_an_a", "dapAnA", "dap_an_b", "dapAnB", "dap_an_c", "dapAnC", "dap_an_d", "dapAnD", "dap_an_dung", "dapAnDung");
    private static final Set<String> QUESTION_KEYS = Set.of("noiDung", "dapAnA", "dapAnB", "dapAnC", "dapAnD", "dapAnDung");
    public record Question(String noiDung, String dapAnA, String dapAnB, String dapAnC, String dapAnD, String dapAnDung) {
        public Question {
            noiDung = DtoValidator.nonEmpty(noiDung, "noiDung"); dapAnA = DtoValidator.nonEmpty(dapAnA, "dapAnA");
            dapAnB = DtoValidator.nonEmpty(dapAnB, "dapAnB"); dapAnC = DtoValidator.nonEmpty(dapAnC, "dapAnC"); dapAnD = DtoValidator.nonEmpty(dapAnD, "dapAnD");
            if (dapAnDung == null || !Set.of("A", "B", "C", "D").contains(dapAnDung)) throw AuthException.invalid("dapAnDung", "literal_error", "Input should be A, B, C or D");
            if (List.of(dapAnA, dapAnB, dapAnC, dapAnD).stream().map(CaseFold::apply).distinct().count() != 4)
                throw AuthException.invalid("cauHoiList", "value_error", "Four distinct answer options are required.");
        }
        public CauHoi row(String examId) {
            var value = new CauHoi(); value.setIdCauHoi(UUID.randomUUID().toString()); value.setIdDeThi(examId);
            value.setNoiDung(noiDung); value.setDapAnA(dapAnA); value.setDapAnB(dapAnB); value.setDapAnC(dapAnC); value.setDapAnD(dapAnD); value.setDapAnDung(dapAnDung); return value;
        }
    }
    public record Manual(String tenDeThi, String khoi, String kieuTaiLieu, String noiDungTaiLieu, List<Question> cauHoiList) {
        public Manual {
            tenDeThi = DtoValidator.nonEmpty(tenDeThi, "tenDeThi"); if (khoi != null) khoi = DtoValidator.nonEmpty(khoi, "khoi");
            if (kieuTaiLieu == null || !Set.of("TEXT", "FILE").contains(kieuTaiLieu)) throw AuthException.invalid("kieuTaiLieu", "literal_error", "Input should be TEXT or FILE");
            if (cauHoiList == null || cauHoiList.isEmpty()) throw AuthException.invalid("cauHoiList", "too_short", "List should have at least 1 item");
            cauHoiList = List.copyOf(cauHoiList);
        }
    }
    public record AI(String tenDeThi, String khoi, String rawText, int numQuestions) {
        public AI {
            tenDeThi = DtoValidator.nonEmpty(tenDeThi, "tenDeThi"); if (khoi != null) khoi = DtoValidator.nonEmpty(khoi, "khoi");
            DtoValidator.nonEmpty(rawText, "rawText");
            if (numQuestions < 1) throw AuthException.invalid("numQuestions", "greater_than_equal", "Input should be greater than or equal to 1");
        }
    }
    public static AI ai(String json) {
        JsonObject input;
        try { input = StrictJson.parse(json).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw AuthException.invalid("", "json_invalid", "JSON decode error"); }
        var object = DtoValidator.object(DtoValidator.aliases(input, Map.of("ten_de_thi", "tenDeThi", "raw_text", "rawText", "num_questions", "numQuestions")), Set.of("tenDeThi", "khoi", "rawText", "numQuestions"));
        return new AI(DtoValidator.string(object, "tenDeThi", false), DtoValidator.string(object, "khoi", true), DtoValidator.string(object, "rawText", false), object.has("numQuestions") ? DtoValidator.positiveInteger(object, "numQuestions") : 10);
    }
    public static Manual manual(String json) {
        JsonObject original;
        try { original = StrictJson.parse(json).getAsJsonObject(); }
        catch (RuntimeException invalid) { throw AuthException.invalid("", "json_invalid", "JSON decode error"); }
        var object = DtoValidator.object(DtoValidator.aliases(original, EXAM_ALIASES), Set.of("tenDeThi", "khoi", "kieuTaiLieu", "noiDungTaiLieu", "cauHoiList"));
        JsonElement list = DtoValidator.required(object, "cauHoiList");
        if (!list.isJsonArray()) throw AuthException.invalid("cauHoiList", "list_type", "Input should be a valid list");
        List<Question> questions = new ArrayList<>();
        for (var item : list.getAsJsonArray()) {
            if (!item.isJsonObject()) throw AuthException.invalid("cauHoiList", "model_attributes_type", "Input should be an object");
            var q = DtoValidator.object(DtoValidator.aliases(item.getAsJsonObject(), QUESTION_ALIASES), QUESTION_KEYS);
            questions.add(new Question(DtoValidator.string(q, "noiDung", false), DtoValidator.string(q, "dapAnA", false),
                    DtoValidator.string(q, "dapAnB", false), DtoValidator.string(q, "dapAnC", false), DtoValidator.string(q, "dapAnD", false), DtoValidator.string(q, "dapAnDung", false)));
        }
        return new Manual(DtoValidator.string(object, "tenDeThi", false), DtoValidator.string(object, "khoi", true),
                object.has("kieuTaiLieu") ? DtoValidator.string(object, "kieuTaiLieu", false) : "TEXT", DtoValidator.string(object, "noiDungTaiLieu", true), questions);
    }
    public record Summary(String idDeThi, String idGv, String tenDeThi, String khoi, Integer soLuongCauHoi, String kieuTaiLieu, String ngayTao) {
        public Summary {
            if (idDeThi == null || idGv == null || tenDeThi == null || kieuTaiLieu == null) throw new IllegalArgumentException("Invalid exam response.");
        }
    }
    public record QuestionResponse(String noiDung, String dapAnA, String dapAnB, String dapAnC, String dapAnD, String dapAnDung, String idCauHoi, String idDeThi) { }
    public record Detail(String idDeThi, String idGv, String tenDeThi, String khoi, Integer soLuongCauHoi, String kieuTaiLieu, String ngayTao, String noiDungTaiLieu, List<QuestionResponse> cauHoiList) { }
    public record Deleted(String idDeThi, String status) { public Deleted(String id) { this(id, "deleted"); } }
    public static Summary summary(DeThi exam) {
        return new Summary(exam.getIdDeThi(), exam.getIdGv(), exam.getTenDeThi(), exam.getKhoi(), exam.getSoLuongCauHoi(), exam.getKieuTaiLieu(), exam.getNgayTao() == null ? null : exam.getNgayTao().toString());
    }
    public static Detail detail(DeThi exam, List<CauHoi> questions) {
        Summary s = summary(exam); List<QuestionResponse> responses = new ArrayList<>();
        for (var row : questions) {
            try {
                if (row.getIdCauHoi() == null || !exam.getIdDeThi().equals(row.getIdDeThi())) throw new IllegalArgumentException();
                var q = new Question(row.getNoiDung(), row.getDapAnA(), row.getDapAnB(), row.getDapAnC(), row.getDapAnD(), row.getDapAnDung());
                responses.add(new QuestionResponse(q.noiDung(), q.dapAnA(), q.dapAnB(), q.dapAnC(), q.dapAnD(), q.dapAnDung(), row.getIdCauHoi(), row.getIdDeThi()));
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid exam response."); }
        }
        return new Detail(s.idDeThi(), s.idGv(), s.tenDeThi(), s.khoi(), s.soLuongCauHoi(), s.kieuTaiLieu(), s.ngayTao(), exam.getNoiDungTaiLieu(), List.copyOf(responses));
    }
}
