package com.ezam.exam;

import com.ezam.service.GeminiService;
import com.ezam.util.*;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

public class GeminiServiceTest {
    public static final String QUESTION = "{\"noi_dung\":\"Công thức của nước?\",\"dap_an_a\":\"H2O\",\"dap_an_b\":\"CO2\",\"dap_an_c\":\"O2\",\"dap_an_d\":\"NaCl\",\"dap_an_dung\":\"A\"}";
    public static String envelope(String text, String finish) {
        return new Gson().toJson(Map.of("candidates", List.of(Map.of("finishReason", finish, "content", Map.of("parts", List.of(Map.of("text", text)))))));
    }
    @Test void actualHttpRequestHasStructuredSchemaPromptSourceAndKeyInHeaderOnly() throws Exception {
        var received = new AtomicReference<JsonObject>(); var key = new AtomicReference<String>(); var count = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            count.incrementAndGet(); received.set(StrictJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            assertNull(exchange.getRequestURI().getQuery()); byte[] body = envelope("["+QUESTION+"]", "STOP").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var service = stub(server, Duration.ofSeconds(1))) {
            assertEquals(1, service.generateQuizFromText("Source text", 1).size());
            assertEquals("synthetic-gemini-key", key.get()); assertEquals(1, count.get());
            var config = received.get().getAsJsonObject("generationConfig"); assertEquals("application/json", config.get("responseMimeType").getAsString());
            assertEquals(1, config.getAsJsonObject("responseJsonSchema").get("minItems").getAsInt());
            assertEquals(6, config.getAsJsonObject("responseJsonSchema").getAsJsonObject("items").getAsJsonArray("required").size());
            assertFalse(received.get().toString().contains("synthetic-gemini-key")); assertEquals("GeminiService[redacted]", service.toString());
        } finally { server.stop(0); }
    }
    private static GeminiService stub(HttpServer server, Duration timeout) { return new GeminiService("synthetic-gemini-key", "fixture-model", URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/generate"), timeout); }
    @ParameterizedTest @ValueSource(strings={"{}", "[]", "null", "not JSON", "```json\n[]\n```", "[{\"noi_dung\":\"Q\"}]"})
    void malformedWrongCountAndMissingFieldsAre400(String json) { assertEquals(400, assertThrows(GeminiException.class, () -> GeminiService.validateQuizJson(json, 1)).status()); }
    @ParameterizedTest @ValueSource(strings={"extra", "duplicate", "null", "boolean", "wrong-answer", "same-options", "empty"})
    void invalidQuestionRejectsEntireArray(String variant) {
        String q = switch (variant) {
            case "extra" -> QUESTION.replace("{", "{\"id\":\"bad\",");
            case "duplicate" -> QUESTION.replace("{", "{\"noi_dung\":\"duplicate\",");
            case "null" -> QUESTION.replace("\"H2O\"", "null");
            case "boolean" -> QUESTION.replace("\"H2O\"", "true");
            case "wrong-answer" -> QUESTION.replace("\"A\"", "\"Z\"");
            case "same-options" -> QUESTION.replace("\"CO2\"", "\" h2o \"");
            default -> QUESTION.replace("\"H2O\"", "\" \"");
        };
        assertEquals(400, assertThrows(GeminiException.class, () -> GeminiService.validateQuizJson("["+QUESTION+","+q+"]", 2)).status());
    }
    @ParameterizedTest @ValueSource(ints={400,401,408,429,500,504})
    void providerErrorsAreSafe503AndNeverRetried(int status) throws Exception {
        var calls = new AtomicInteger(); HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", e -> { calls.incrementAndGet(); byte[] body="private-provider-error".getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(status, body.length); e.getResponseBody().write(body); e.close(); }); server.start();
        try (var service = stub(server, Duration.ofSeconds(1))) {
            var error = assertThrows(GeminiException.class, () -> service.generateQuizFromText("Text", 1));
            assertEquals(503, error.status()); assertEquals(1, calls.get()); assertFalse(error.getMessage().contains("private-provider")); assertNull(error.getCause());
        } finally { server.stop(0); }
    }
    @Test void realFifteenSecondDeadlineIncludesStalledBodyAndCancelsRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(r -> { var t = new Thread(r); t.setDaemon(true); return t; }); server.setExecutor(executor);
        var cancelled = new CountDownLatch(1);
        server.createContext("/generate", e -> {
            try {
                e.sendResponseHeaders(200, 0); e.getResponseBody().write('{'); e.getResponseBody().flush();
                Thread.sleep(15_300);
                for (int i=0;i<1000;i++) { e.getResponseBody().write(new byte[65536]); e.getResponseBody().flush(); }
            } catch (java.io.IOException aborted) { cancelled.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { e.close(); }
        }); server.start(); long start = System.nanoTime();
        try (var service = stub(server, GeminiService.TIMEOUT)) {
            var error = assertThrows(GeminiException.class, () -> service.generateQuizFromText("Text", 1));
            long elapsed = (System.nanoTime()-start)/1_000_000;
            assertEquals(503, error.status()); assertTrue(error.getMessage().contains("15-second"));
            assertTrue(elapsed >= 14_500 && elapsed < 18_000, "Full request deadline must be fifteen seconds.");
            assertTrue(cancelled.await(4, TimeUnit.SECONDS), "Server must observe the cancelled response stream.");
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void missingConfigAndNetworkFailuresDoNotCreateQuestions() throws Exception {
        try (var service = GeminiService.fromEnvironment(Map.of())) { assertEquals(503, assertThrows(GeminiException.class, () -> service.generateQuizFromText("Text", 1)).status()); }
        try (var service = new GeminiService("synthetic", "fixture", URI.create("http://127.0.0.1:1/generate"), Duration.ofMillis(300))) {
            assertEquals(503, assertThrows(GeminiException.class, () -> service.generateQuizFromText("Text", 1)).status());
        }
    }
    @Test void aiRequestStrictTypesAliasesDefaultAndSourcePreservation() {
        assertEquals(10, com.ezam.dto.ExamDTO.ai("{\"ten_de_thi\":\"Title\",\"raw_text\":\" Source \"}").numQuestions());
        assertEquals(" Source ", com.ezam.dto.ExamDTO.ai("{\"tenDeThi\":\"Title\",\"rawText\":\" Source \"}").rawText());
        for (String value : List.of("true", "1.0", "\"1\"", "null", "0")) assertThrows(AuthException.class, () -> com.ezam.dto.ExamDTO.ai("{\"tenDeThi\":\"Title\",\"rawText\":\"Text\",\"numQuestions\":"+value+"}"));
    }
}
