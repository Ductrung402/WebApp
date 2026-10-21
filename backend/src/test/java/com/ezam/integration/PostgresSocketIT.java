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

/** Real Tomcat lifecycle + Netty 2.0.13 + installed JS client + PostgreSQL. */
class PostgresSocketIT {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static PostgresAuthIT.TestTomcat tomcat;private static Path runtime;private static int socketPort;
    @BeforeAll static void setup()throws Exception{
        var u=URI.create(Objects.toString(System.getenv("TEST_DATABASE_URL"),""));if(!"127.0.0.1".equals(u.getHost())||!"/ezam_t21_test".equals(u.getPath()))throw new IllegalStateException("Isolated runner required");
        runtime=Files.createTempDirectory(Path.of("target/verification"),"t42-runtime-").toAbsolutePath();start();
    }
    private static void start()throws Exception{try(var s=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){socketPort=s.getLocalPort();}tomcat=new PostgresAuthIT.TestTomcat(true,true,null,Map.of("SOCKET_HOST","127.0.0.1","SOCKET_PORT",Integer.toString(socketPort),"RUNTIME_DIRECTORY",runtime.toString()));tomcat.start();}
    @AfterAll static void close()throws Exception{if(tomcat!=null)tomcat.close();try(var s=new java.net.Socket()){assertThrows(java.io.IOException.class,()->s.connect(new InetSocketAddress("127.0.0.1",socketPort),500));}}
    private static void node(String script,Map<String,Object> args,String proof)throws Exception{
        var process=new ProcessBuilder("node","src/test/probe/"+script).redirectError(Path.of("target/verification/"+proof+"_error.log").toFile()).redirectOutput(Path.of("target/verification/"+proof+"_stdout.log").toFile()).start();
        try{try(var in=process.getOutputStream()){in.write(JSON.toJson(args).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(100,java.util.concurrent.TimeUnit.SECONDS),"Socket probe timeout");assertEquals(0,process.exitValue(),"Inspect sanitized socket/browser proof");assertEquals("PASS",StrictJson.parse(Files.readString(Path.of("target/verification/"+proof+".json"))).getAsJsonObject().get("status").getAsString());}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
    }
    private static Map<String,Object> args(String mode){return new LinkedHashMap<>(Map.of("apiBase","http://127.0.0.1:"+tomcat.httpPort,"socketBase","http://127.0.0.1:"+socketPort,"mode",mode));}
    @Test void installedSocketClientVerifiesOwnershipIsolationMembershipAndImmutableStart()throws Exception{
        // Real JDBC provider records every borrow's thread; protocol itself uses the installed JS SDK.
        var names=new java.util.concurrent.CopyOnWriteArrayList<String>();
        try(var pool=DBUtil.open(DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL",System.getenv("TEST_DATABASE_URL"))),3,2000)){
            var tx=new com.ezam.service.TransactionManager(()->{names.add(Thread.currentThread().getName());return pool.getConnection();});
            var jwt=JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY","synthetic-jwt-test-key-at-least-32-bytes"),java.time.Clock.systemUTC());
            int port;try(var s=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=s.getLocalPort();}
            try(var store=new com.ezam.util.runtime.RuntimeStore(Files.createTempDirectory(Path.of("target/verification"),"t42-worker-runtime-"),new com.ezam.service.RuntimeDatabaseEvidence(tx));var manager=new SocketServerManager("127.0.0.1",port,new com.ezam.service.SocketFlowService(tx,store,jwt,java.time.Clock.systemUTC()),jwt)){
                manager.start();var config=args("flow");config.put("socketBase","http://127.0.0.1:"+port);node("socket-flow.mjs",config,"T4_2_socket");
            }
            assertFalse(names.isEmpty());assertTrue(names.stream().allMatch("ezam-socket-worker"::equals));
            assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("ezam-socket-worker")));
            Files.writeString(Path.of("target/verification/T4_2_jdbc_worker.json"),JSON.toJson(Map.of("status","PASS","allJdbcBorrowsOnWorker",true,"borrowCount",names.size())));
        }
    }
    @Test void realChromeWaitingToTakingAndSynchronizedCountdown()throws Exception{node("browser-exam-flow.mjs",args("flow"),"T4_2_browser");}
    @Test void realClientLastDisconnectDbCommitPrivateNotificationAndRecovery()throws Exception{node("socket-recovery.mjs",args("recovery"),"T4_3_socket");}
    @Test void realChromeOfflineFreezeReconnectAndF5KeepAnswers()throws Exception{node("browser-exam-flow.mjs",args("recovery"),"T4_3_browser");}
    @Test void restartKeepsDeadlineAndMissingRunFails503()throws Exception{
        node("socket-flow.mjs",args("seed-restart"),"T4_2_seed");var publicData=StrictJson.parse(Files.readString(Path.of("target/verification/T4_2_seed.json"))).getAsJsonObject();
        tomcat.close();tomcat=null;start();var resume=args("resume");resume.put("room",JSON.fromJson(publicData.get("room"),Object.class));resume.put("student",JSON.fromJson(publicData.get("student"),Object.class));resume.put("run",JSON.fromJson(publicData.get("run"),Object.class));node("socket-flow.mjs",resume,"T4_2_restart");
        Files.delete(runtime.resolve("run-"+publicData.getAsJsonObject("room").get("maCodePhongThi").getAsString()+".json"));resume.put("mode","missing");node("socket-flow.mjs",resume,"T4_2_missing");
    }
}
