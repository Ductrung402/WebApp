package com.ezam.service;

import com.ezam.dto.SubmissionDTO;
import com.ezam.dto.SubmissionDTO.*;
import com.ezam.model.TrangThaiBaiLam;
import com.ezam.util.AuthException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GradingServiceTest {
    @Test void matchesActualPythonBinaryDoubleRoundingVectors() throws Exception {
        int checked = 0;
        try (var in = getClass().getResourceAsStream("/grading/python-round.csv")) {
            assertNotNull(in);
            try (var lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line : lines.lines().skip(1).toList()) {
                    var values = line.split(","); int correct = Integer.parseInt(values[0]), total = Integer.parseInt(values[1]);
                    assertEquals(Double.parseDouble(values[2]), GradingService.score(correct, total), line); checked++;
                }
            }
        }
        assertTrue(checked > 900);
        // Decimal ties differ from BigDecimal.valueOf(double): Python round(1.625,2) is 1.62.
        assertEquals(1.62, GradingService.score(13, 80));
        assertEquals(7.0, GradingService.score(7, 10)); assertEquals(3.33, GradingService.score(1, 3));
    }
    @Test void noCorrectEmptyAndNullAreWrong() {
        var request = new Request("s", "r", List.of(new Answer("q1", null), new Answer("q2", "D")));
        var grade = GradingService.calculate("s", Map.of("q1", "A", "q2", "B", "q3", "C"), request.selectedAnswers());
        assertEquals(0.0, grade.diemSo()); assertEquals(0, grade.soCauDung()); assertEquals(3, grade.tongSoCau());
        assertEquals(TrangThaiBaiLam.HOAN_THANH, grade.trangThai());
    }
    @Test void emptyCorruptKeysAndForeignPrivateSnapshotFailSafely() {
        assertEquals(503, assertThrows(AuthException.class, () -> GradingService.calculate("s", Map.of(), Map.of())).status());
        assertEquals(503, assertThrows(AuthException.class, () -> GradingService.calculate("s", Map.of("q", "X"), Map.of())).status());
        var nullKey = new HashMap<String, String>(); nullKey.put("q", null);
        assertThrows(AuthException.class, () -> GradingService.calculate("s", nullKey, Map.of()));
        assertThrows(AuthException.class, () -> GradingService.calculate("s", Map.of("q", "A"), Map.of("foreign", "A")));
    }
    @Test void invalidCountsDoNotDivideByZero() {
        assertThrows(IllegalArgumentException.class, () -> GradingService.score(0, 0));
        assertThrows(IllegalArgumentException.class, () -> GradingService.score(2, 1));
        assertThrows(IllegalArgumentException.class, () -> GradingService.score(-1, 3));
    }
    @Test void acceptsBothDocumentedAliasesAndRequiredNull() {
        var a = SubmissionDTO.parse("{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\",\"dapAnChon\":null}]}");
        var b = SubmissionDTO.parse("{\"id_hs\":\"s\",\"id_phong_thi\":\"r\",\"danh_sach_cau_tra_loi\":[{\"id_cau_hoi\":\"q\",\"dap_an_chon\":null}]}");
        assertEquals(a, b); assertTrue(a.selectedAnswers().isEmpty());
        assertTrue(SubmissionDTO.parse("{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[]}").selectedAnswers().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {
        "{}", "null", "[]",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":null}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[],\"diemSo\":10}",
        "{\"idHs\":\"s\",\"idHs\":\"other\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[]}",
        "{\"idHs\":\"s\",\"id_hs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[]}",
        "{\"idHs\":123,\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[]}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\"}]}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\",\"dapAnChon\":\"a\"}]}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\",\"dapAnChon\":true}]}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\",\"dapAnChon\":\"A\",\"dapAnDung\":\"A\"}]}",
        "{\"idHs\":\"s\",\"idPhongThi\":\"r\",\"danhSachCauTraLoi\":[{\"idCauHoi\":\"q\",\"dapAnChon\":\"A\"},{\"idCauHoi\":\"q\",\"dapAnChon\":null}]}"
    }) void rejectsMalformedPayloadBeforeSql(String body) {
        assertEquals(422, assertThrows(AuthException.class, () -> SubmissionDTO.parse(body)).status());
    }
    @Test void requestListAndSelectedAnswersAreImmutableAndRedacted() {
        var list = new ArrayList<>(List.of(new Answer("q", "A"))); var request = new Request("s", "r", list); list.clear();
        assertEquals(1, request.danhSachCauTraLoi().size());
        assertThrows(UnsupportedOperationException.class, () -> request.selectedAnswers().put("q", "B"));
        assertEquals("SubmissionRequest[redacted]", request.toString());
    }
}
