package com.ezam.integration;

import com.ezam.util.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP/SQL and isolated failure triggers, not DAO mocks. */
class PostgresRoomIT {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static DBUtil db;private static PostgresAuthIT.TestTomcat tomcat;private static String token,examId,teacherId;
    private static final Map<String,Object> proof=new LinkedHashMap<>();
    @BeforeAll static void setup()throws Exception{
        String url=System.getenv("TEST_DATABASE_URL");var uri=URI.create(url==null?"":url);if(!"127.0.0.1".equals(uri.getHost())||!"/ezam_t21_test".equals(uri.getPath()))throw new IllegalStateException("Use isolated runner");
        db=DBUtil.open(DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL",url)),3,2000);
        try(var c=db.getConnection();var s=c.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");var r=s.executeQuery()){assertTrue(r.next());assertEquals("EZam isolated auth test T2.1",r.getString(1));}
        try{tomcat=new PostgresAuthIT.TestTomcat(true,true);tomcat.start();String email=UUID.randomUUID()+"@example.com";
            var reg=call("POST","/api/auth/register",Map.of("email",email,"mat_khau","synthetic-password","ho_ten","Room teacher"),null);assertEquals(201,reg.status);teacherId=reg.json.getAsJsonObject().get("id_gv").getAsString();
            token=call("POST","/api/auth/login",Map.of("email",email,"mat_khau","synthetic-password"),null).json.getAsJsonObject().get("access_token").getAsString();
            var exam=call("POST","/api/exams",Map.of("tenDeThi","Room exam","cauHoiList",List.of(Map.of("noiDung","Water?","dapAnA","H2O","dapAnB","CO2","dapAnC","O2","dapAnD","N2","dapAnDung","A"))),token);assertEquals(201,exam.status);examId=exam.json.getAsJsonObject().get("idDeThi").getAsString();
        }catch(Exception e){if(tomcat!=null)tomcat.close();db.close();throw e;}
    }
    @AfterAll static void close()throws Exception{try{if(tomcat!=null)tomcat.close();proof.put("tomcatStopped",true);Files.writeString(Path.of("target/verification/T4_1_room.json"),JSON.toJson(proof));}finally{if(db!=null)db.close();}}
    private record Reply(int status,JsonElement json){}
    private static Reply call(String method,String path,Object body,String auth)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+tomcat.httpPort+path)).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body instanceof String s?s:JSON.toJson(body),StandardCharsets.UTF_8));if(auth!=null)b.header("Authorization","Bearer "+auth);
        var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));return new Reply(r.statusCode(),StrictJson.parse(r.body()));
    }
    private static JsonObject room(String name)throws Exception{var r=call("POST","/api/rooms",Map.of("idDeThi",examId,"tenLop",name,"thoiGianLamBai",10),token);assertEquals(201,r.status);return r.json.getAsJsonObject();}
    private static Reply join(JsonObject room,String name)throws Exception{return call("POST","/api/rooms/join",Map.of("hoVaTen",name,"maCodePhongThi",room.get("maCodePhongThi").getAsString()),null);}
    private static void sql(String query,String... params)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement(query)){for(int i=0;i<params.length;i++)s.setString(i+1,params[i]);s.executeUpdate();}}
    private static int count(String query,String value)throws Exception{try(var c=db.getConnection();var s=c.prepareStatement(query)){s.setString(1,value);try(var r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}}}
    @Test void createOwnerAndRosterContractsNoAnswerLeak()throws Exception{
        var p=room("6A");assertEquals(Set.of("idPhongThi","maCodePhongThi","tenLop","thoiGianLamBai","trangThaiPhong","ngayTaoPhong"),p.keySet());assertEquals("DANG_CHO",p.get("trangThaiPhong").getAsString());assertTrue(p.get("maCodePhongThi").getAsString().matches("[A-Z0-9]{6}"));
        var joined=join(p," An ");assertEquals(201,joined.status);String student=joined.json.getAsJsonObject().get("idHs").getAsString();assertEquals("An",joined.json.getAsJsonObject().get("hoVaTen").getAsString());
        var r=call("GET","/api/rooms/"+p.get("idPhongThi").getAsString(),null,token);assertEquals(200,r.status);var detail=r.json.getAsJsonObject();assertEquals(1,detail.getAsJsonArray("hocSinhList").size());var k=detail.getAsJsonArray("ketQuaList").get(0).getAsJsonObject();assertTrue(k.get("diemSo").isJsonNull());assertEquals("DANG_THI",k.get("trangThai").getAsString());assertFalse(r.json.toString().contains("dapAn"));assertFalse(detail.has("idDeThi"));
        assertEquals(1,count("SELECT count(*) FROM ket_qua_thi WHERE id_hs=? AND diem_so=0 AND trang_thai='DANG_THI'",student));proof.put("createAndAdmission",true);
    }
    @Test void invalidRequestIs422BeforeSqlOrAuth()throws Exception{
        assertEquals(422,call("POST","/api/rooms",Map.of(),null).status);assertEquals(422,call("POST","/api/rooms/join",Map.of("hoVaTen","An","maCodePhongThi","abc123"),null).status);assertEquals(401,call("POST","/api/rooms",Map.of("idDeThi",examId,"tenLop","x","thoiGianLamBai",10),null).status);
        assertEquals(404,call("POST","/api/rooms",Map.of("idDeThi","missing","tenLop","x","thoiGianLamBai",10),token).status);proof.put("validationAndOwnership",true);
    }
    @Test void nonOwnerCannotReadOrUseExam()throws Exception{
        String email=UUID.randomUUID()+"@example.com";call("POST","/api/auth/register",Map.of("email",email,"mat_khau","synthetic-password","ho_ten","Other"),null);String other=call("POST","/api/auth/login",Map.of("email",email,"mat_khau","synthetic-password"),null).json.getAsJsonObject().get("access_token").getAsString();var p=room("Private");assertEquals(404,call("GET","/api/rooms/"+p.get("idPhongThi").getAsString(),null,other).status);assertEquals(404,call("POST","/api/rooms",Map.of("idDeThi",examId,"tenLop","Other","thoiGianLamBai",1),other).status);
    }
    @Test void unknownClosedAndActiveAdmissions()throws Exception{
        assertEquals(404,call("POST","/api/rooms/join",Map.of("hoVaTen","An","maCodePhongThi","ZZZZZZ"),null).status);var p=room("States");String id=p.get("idPhongThi").getAsString();sql("UPDATE phong_thi SET trang_thai_phong='DA_DONG' WHERE id_phong_thi=?",id);assertEquals(409,join(p,"Closed").status);sql("UPDATE phong_thi SET trang_thai_phong='DANG_THI' WHERE id_phong_thi=?",id);assertEquals(201,join(p,"Active").status);
    }
    @Test void actualUniqueCollisionRetriesWholeTransactionOnlyForCode()throws Exception{
        var existing=room("Existing");sql("UPDATE phong_thi SET ma_code_phong_thi='COLLID' WHERE id_phong_thi=?",existing.get("idPhongThi").getAsString());var p=room("force-collision-once");assertNotEquals("COLLID",p.get("maCodePhongThi").getAsString());assertEquals(1,count("SELECT count(*) FROM phong_thi WHERE ten_lop=?","force-collision-once"));proof.put("actualUniqueCollisionRetry",true);
    }
    @Test void resultInsertAndCommitFailuresRollbackStudentAndResult()throws Exception{
        var p=room("Rollback");for(String name:List.of("fail-result","fail-admission-commit")){assertEquals(503,join(p,name).status);assertEquals(0,count("SELECT count(*) FROM hoc_sinh WHERE ho_va_ten=?",name));}assertEquals(0,count("SELECT count(*) FROM ket_qua_thi WHERE id_phong_thi=?",p.get("idPhongThi").getAsString()));proof.put("insertAndCommitAdmissionRollback",true);
    }
    @Test void completedScoreOnly()throws Exception{
        var p=room("Score");String id=join(p,"Score student").json.getAsJsonObject().get("idHs").getAsString();sql("UPDATE ket_qua_thi SET trang_thai='HOAN_THANH',diem_so=8.5 WHERE id_hs=?",id);var r=call("GET","/api/rooms/"+p.get("idPhongThi").getAsString(),null,token);assertEquals(8.5,r.json.getAsJsonObject().getAsJsonArray("ketQuaList").get(0).getAsJsonObject().get("diemSo").getAsDouble());
    }
    @Test void realChromeTeacherCreatesAndMobileStudentJoinsAndReloadsSession()throws Exception{
        var process=new ProcessBuilder("node","src/test/probe/browser-room-flow.mjs").redirectError(Path.of("target/verification/T4_1_browser_error.log").toFile()).redirectOutput(Path.of("target/verification/T4_1_browser_stdout.log").toFile()).start();
        try{try(var in=process.getOutputStream()){in.write(JSON.toJson(Map.of("apiBase","http://127.0.0.1:"+tomcat.httpPort)).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(90,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());assertEquals("PASS",StrictJson.parse(Files.readString(Path.of("target/verification/T4_1_browser.json"))).getAsJsonObject().get("status").getAsString());}finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
    }
}
