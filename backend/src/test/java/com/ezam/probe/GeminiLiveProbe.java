package com.ezam.probe;

import com.ezam.service.GeminiService;
import com.ezam.util.GeminiException;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit one-request live probe, not an automatic unit test. Never logs keys/provider errors. */
public final class GeminiLiveProbe {
    public static void main(String[] args) throws Exception {
        var result = new LinkedHashMap<String, Object>(); result.put("live", true);
        long started = System.nanoTime();
        try (var service = GeminiService.fromEnvironment()) {
            var questions = service.generateQuizFromText("Nước có công thức hóa học H2O. Nước sôi ở 100 độ C ở áp suất tiêu chuẩn.", 1);
            result.put("status", "PASS"); result.put("count", questions.size());
            result.put("questions", questions.stream().map(q -> Map.of("noi_dung", q.noiDung(), "dap_an_a", q.dapAnA(), "dap_an_b", q.dapAnB(), "dap_an_c", q.dapAnC(), "dap_an_d", q.dapAnD(), "dap_an_dung", q.dapAnDung())).toList());
        } catch (GeminiException error) { result.put("status", "NOT_VERIFIED"); result.put("statusCode", error.status()); result.put("detail", error.getMessage()); }
        result.put("elapsedMillis", (System.nanoTime()-started)/1_000_000);
        Path target = Path.of("target/verification/T3_2_live.json"); Files.createDirectories(target.getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        System.out.println("Gemini live probe: " + result.get("status"));
        if (!"PASS".equals(result.get("status"))) System.exit(1);
    }
}
