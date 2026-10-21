package com.ezam.exam;

import com.ezam.dto.ExamDTO;
import com.ezam.filter.ExamValidationFilter;
import com.ezam.model.*;
import com.ezam.util.*;
import com.google.gson.*;
import java.io.*;
import java.time.LocalDate;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExamApiTest {
    static final String QUESTION = "{\"noiDung\":\"Question?\",\"dapAnA\":\"A text\",\"dapAnB\":\"B text\",\"dapAnC\":\"C text\",\"dapAnD\":\"D text\",\"dapAnDung\":\"A\"}";
    static final String BODY = "{\"tenDeThi\":\"  Test  \",\"cauHoiList\":["+QUESTION+"]}";
    @Test void camelCaseSnakeCaseMixedAliasesAndNullAreAccepted() {
        assertEquals("Test", ExamDTO.manual(BODY).tenDeThi()); assertEquals("TEXT", ExamDTO.manual(BODY).kieuTaiLieu());
        assertNull(ExamDTO.manual(BODY).khoi()); assertNull(ExamDTO.manual(BODY).noiDungTaiLieu());
        String snake = BODY.replace("tenDeThi", "ten_de_thi").replace("cauHoiList", "cau_hoi_list").replace("noiDung", "noi_dung")
                .replace("dapAnA", "dap_an_a").replace("dapAnB", "dap_an_b").replace("dapAnC", "dap_an_c").replace("dapAnD\"", "dap_an_d\"").replace("dapAnDung", "dap_an_dung");
        assertEquals(ExamDTO.manual(BODY), ExamDTO.manual(snake));
    }
    @ParameterizedTest @ValueSource(strings={"{}", "null", "[]", "{", "{\"tenDeThi\":\"T\",\"cauHoiList\":[]}",
            "{\"tenDeThi\":\"T\",\"cauHoiList\":null}", "{\"tenDeThi\":1,\"cauHoiList\":[]}"})
    void malformedMissingAndEmptyAre422(String body) { assertEquals(422, assertThrows(AuthException.class, () -> ExamDTO.manual(body)).status()); }
    @ParameterizedTest @ValueSource(strings={"idGv", "id_gv", "idDeThi", "soLuongCauHoi", "so_luong_cau_hoi", "unexpected"})
    void ownerIdsAndClientCountsAreForbidden(String key) {
        var json = StrictJson.parse(BODY).getAsJsonObject(); json.addProperty(key, "spoof");
        assertEquals(422, assertThrows(AuthException.class, () -> ExamDTO.manual(json.toString())).status());
    }
    @Test void duplicateKeysAndBothAliasesAreRejected() {
        assertThrows(AuthException.class, () -> ExamDTO.manual(BODY.replace("\"tenDeThi\":", "\"ten_de_thi\":\"Other\",\"tenDeThi\":")));
        assertThrows(AuthException.class, () -> ExamDTO.manual(BODY.replace("\"tenDeThi\":", "\"tenDeThi\":\"Other\",\"tenDeThi\":")));
    }
    @ParameterizedTest @ValueSource(strings={"noiDung", "dapAnA", "dapAnB", "dapAnC", "dapAnD", "dapAnDung"})
    void eachQuestionFieldIsRequired(String key) {
        var body = StrictJson.parse(BODY).getAsJsonObject(); body.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().remove(key);
        assertThrows(AuthException.class, () -> ExamDTO.manual(body.toString()));
    }
    @ParameterizedTest @CsvSource({"Straße,STRASSE", "ς,σ", "ﬃ,FFI", "İ,i̇"})
    void unicodeCasefoldMatchesLegacyAndRejectsEquivalentOptions(String a, String b) {
        assertEquals(CaseFold.apply(a), CaseFold.apply(b));
        assertThrows(AuthException.class, () -> new ExamDTO.Question("Q", a, b, "Third", "Fourth", "A"));
    }
    @Test void casefoldDoesNotOverNormalizeDotlessIOrAccents() {
        assertNotEquals(CaseFold.apply("ı"), CaseFold.apply("i")); assertNotEquals(CaseFold.apply("é"), CaseFold.apply("é"));
        assertNotNull(new ExamDTO.Question("Q", "ı", "i", "é", "é", "A"));
    }
    @Test void optionsTrimAndLiteralAnswerTypeAreChecked() {
        assertThrows(AuthException.class, () -> new ExamDTO.Question("Q", " a ", "A", "c", "d", "A"));
        assertThrows(AuthException.class, () -> new ExamDTO.Question("Q", "a", "b", "c", "d", " A "));
        var body = StrictJson.parse(BODY).getAsJsonObject(); body.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().addProperty("idCauHoi", "spoof");
        assertThrows(AuthException.class, () -> ExamDTO.manual(body.toString()));
    }
    @Test void responseMatchesAllEightColumnsAndNullableSummaryDate() {
        var exam = new DeThi(); exam.setIdDeThi("exam"); exam.setIdGv("owner"); exam.setTenDeThi("Title"); exam.setKieuTaiLieu("TEXT");
        var json = new GsonBuilder().serializeNulls().create();
        JsonObject summary = JsonParser.parseString(json.toJson(ExamDTO.summary(exam))).getAsJsonObject();
        assertEquals(Set.of("idDeThi", "idGv", "tenDeThi", "khoi", "soLuongCauHoi", "kieuTaiLieu", "ngayTao"), summary.keySet());
        assertTrue(summary.get("ngayTao").isJsonNull()); assertTrue(summary.get("soLuongCauHoi").isJsonNull());
        exam.setNgayTao(LocalDate.of(2026, 10, 18));
        assertEquals("2026-10-18", ExamDTO.summary(exam).ngayTao());
        var q = ExamDTO.manual(BODY).cauHoiList().get(0).row("exam");
        var detail = JsonParser.parseString(json.toJson(ExamDTO.detail(exam, List.of(q)))).getAsJsonObject();
        assertTrue(detail.has("noiDungTaiLieu")); assertEquals(8, detail.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().size());
        q.setDapAnDung("Z"); assertThrows(IllegalArgumentException.class, () -> ExamDTO.detail(exam, List.of(q)));
    }
    @Test void invalidPayloadDoesNotReachAuthFilterAndValidBodyCanBeReadAgain() throws Exception {
        for (boolean valid : List.of(false, true)) {
            var request = mock(HttpServletRequest.class); var response = mock(HttpServletResponse.class); var chain = mock(FilterChain.class); var output = new StringWriter();
            when(response.getWriter()).thenReturn(new PrintWriter(output)); when(request.getMethod()).thenReturn("POST"); when(request.getServletPath()).thenReturn("/api/exams");
            when(request.getReader()).thenReturn(new BufferedReader(new StringReader(valid ? BODY : "{}")));
            new ExamValidationFilter().doFilter(request, response, chain);
            if (!valid) { verifyNoInteractions(chain); verify(response).setStatus(422); }
            else {
                var capture = org.mockito.ArgumentCaptor.forClass(ServletRequest.class); verify(chain).doFilter(capture.capture(), eq(response));
                assertEquals(BODY, JsonUtil.readBody((HttpServletRequest) capture.getValue()));
                assertEquals(BODY, JsonUtil.readBody((HttpServletRequest) capture.getValue()));
            }
        }
    }
}
