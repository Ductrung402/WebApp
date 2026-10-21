package com.ezam.integration;

import com.ezam.util.*;
import com.ezam.exam.GeminiServiceTest;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/JDBC with an explicitly injected loopback AI stub; live Gemini is a separate probe. */
class PostgresAiIT {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static DBUtil db;
    private static HttpServer stub;
    private static PostgresAuthIT.TestTomcat tomcat;
    private static String token, teacherId;
    private static final AtomicInteger calls = new AtomicInteger();
    private static final Map<String,Object> proof = new LinkedHashMap<>();
    @BeforeAll static void setup() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        URI parsed = URI.create(url == null ? "" : url);
        if (!"127.0.0.1".equals(parsed.getHost()) || !"/ezam_t21_test".equals(parsed.getPath())) throw new IllegalStateException("Use isolated test runner.");
        var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
        db = DBUtil.open(config, 3, 2000);
        try (var c=db.getConnection();var q=c.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");var r=q.executeQuery()) { assertTrue(r.next());assertEquals("EZam isolated auth test T2.1",r.getString(1)); }
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        stub.createContext("/generate", exchange -> {
            calls.incrementAndGet();
            JsonObject body = StrictJson.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            var text = body.getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
            var source = StrictJson.parse(text).getAsJsonObject().get("source_text").getAsString();
            int status=source.startsWith("quota")?429:200;
            String questions="["+GeminiServiceTest.QUESTION+"]";
            if(source.startsWith("second-fails"))questions="["+GeminiServiceTest.QUESTION+","+GeminiServiceTest.QUESTION.replace("Công thức của nước?","fail-second-question")+"]";
            String result=GeminiServiceTest.envelope(source.startsWith("invalid")?"not JSON":questions,"STOP");
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });stub.start();
        try { tomcat=new PostgresAuthIT.TestTomcat(true,true,"http://127.0.0.1:"+stub.getAddress().getPort()+"/generate");tomcat.start();
            String email=UUID.randomUUID()+"@example.com";
            var account=call("POST","/api/auth/register",Map.of("email",email,"mat_khau","synthetic-password","ho_ten","AI teacher"),false);
            assertEquals(201,account.status);teacherId=account.body.getAsJsonObject().get("id_gv").getAsString();
            var login=call("POST","/api/auth/login",Map.of("email",email,"mat_khau","synthetic-password"),false);assertEquals(200,login.status);token=login.body.getAsJsonObject().get("access_token").getAsString();
        }catch(Exception failure){if(tomcat!=null)tomcat.close();stub.stop(0);db.close();throw failure;}
    }
    @AfterAll static void close() throws Exception {try{if(tomcat!=null)tomcat.close();proof.put("tomcatStopped",true);proof.put("ai","HTTP stub, not live Gemini");Files.writeString(Path.of("target/verification/T3_2_preview.json"),JSON.toJson(proof));}finally{if(stub!=null)stub.stop(0);if(db!=null)db.close();}}
    private record Reply(int status,JsonElement body){}
    private static Reply call(String method,String path,Object data,boolean auth)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+tomcat.httpPort+path)).header("Content-Type","application/json").method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.toJson(data),StandardCharsets.UTF_8));
        if(auth)b.header("Authorization","Bearer "+token);
        var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));return new Reply(r.statusCode(),StrictJson.parse(r.body()));
    }
    private static Map<String,Object> payload(String text){return Map.of("tenDeThi","Nước","rawText",text,"numQuestions",1);}
    private static int exams()throws Exception{try(var c=db.getConnection();var q=c.prepareStatement("SELECT count(*) FROM de_thi WHERE id_gv=?")){q.setString(1,teacherId);try(var r=q.executeQuery()){assertTrue(r.next());return r.getInt(1);}}}
    @Test void previewReturnsEditableCamelCaseDraftAndDoesNotInsert()throws Exception{
        int before=exams();var reply=call("POST","/api/exams/preview-ai",payload("Nước có công thức H2O."),true);assertEquals(200,reply.status);
        var draft=reply.body.getAsJsonObject();assertEquals(Set.of("tenDeThi","khoi","kieuTaiLieu","noiDungTaiLieu","cauHoiList"),draft.keySet());
        assertTrue(draft.get("khoi").isJsonNull());assertEquals(1,draft.getAsJsonArray("cauHoiList").size());assertEquals(6,draft.getAsJsonArray("cauHoiList").get(0).getAsJsonObject().size());assertEquals(before,exams());proof.put("previewNoDatabaseWrites",true);
    }
    @Test void providerErrorAndInvalidJsonReturn503Or400AndNoExam()throws Exception{
        int before=exams();assertEquals(400,call("POST","/api/exams/preview-ai",payload("invalid JSON"),true).status);assertEquals(503,call("POST","/api/exams/preview-ai",payload("quota exceeded"),true).status);assertEquals(before,exams());proof.put("previewErrorsNoDatabaseWrites",true);
    }
    @Test void invalidPayloadIs422BeforeAuthAndBeforeProviderCall()throws Exception{
        int before=calls.get();assertEquals(422,call("POST","/api/exams/preview-ai",Map.of(),false).status);assertEquals(before,calls.get());proof.put("invalidPayloadNoProviderCall",true);
    }
    @Test void generateAi201CommitsButAiAndQuestionFailureDoNotCreateJunk()throws Exception{
        int before=exams();var created=call("POST","/api/exams/generate-ai",payload("Nước có công thức H2O."),true);assertEquals(201,created.status);assertEquals(before+1,exams());
        assertEquals(1,created.body.getAsJsonObject().get("soLuongCauHoi").getAsInt());
        assertEquals(400,call("POST","/api/exams/generate-ai",payload("invalid JSON"),true).status);
        assertEquals(503,call("POST","/api/exams/generate-ai",payload("quota"),true).status);
        var fail=new LinkedHashMap<String,Object>(payload("second-fails"));fail.put("numQuestions",2);
        assertEquals(409,call("POST","/api/exams/generate-ai",fail,true).status);assertEquals(before+1,exams());proof.put("generateAtomicRollback",true);
    }
    @Test void realChromeCreationFlowUsesUnmodifiedReactAndPersistsReviewedDraft()throws Exception{
        var process=new ProcessBuilder("node","src/test/probe/browser-creation-flow.mjs").redirectError(java.lang.ProcessBuilder.Redirect.to(Path.of("target/verification/T3_3_browser_error.log").toFile())).redirectOutput(Path.of("target/verification/T3_3_browser_stdout.log").toFile()).start();
        try{
            try(var input=process.getOutputStream()){input.write(JSON.toJson(Map.of("apiBase","http://127.0.0.1:"+tomcat.httpPort)).getBytes(StandardCharsets.UTF_8));}
            assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Browser flow failed; inspect sanitized proof and local logs.");
            var result=StrictJson.parse(Files.readString(Path.of("target/verification/T3_3_browser.json"))).getAsJsonObject();assertEquals("PASS",result.get("status").getAsString());proof.put("realChromeReviewSave",true);
        }finally{if(process.isAlive()){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
    }
}
