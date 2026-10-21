package com.ezam.integration;

import com.ezam.util.StrictJson;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Same WAR owns JDBC, RuntimeStore, REST and Socket; actual installed React/SDK clients. */
class PostgresSubmissionIT {
    private static PostgresAuthIT.TestTomcat tomcat;private static Path runtime;private static int socketPort;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    @BeforeAll static void setup()throws Exception{
        var uri=URI.create(Objects.toString(System.getenv("TEST_DATABASE_URL"),""));if(!"127.0.0.1".equals(uri.getHost())||!"/ezam_t21_test".equals(uri.getPath()))throw new IllegalStateException("Isolated runner required");
        runtime=Files.createTempDirectory(Path.of("target/verification"),"t52-runtime-").toAbsolutePath();start();
    }
    private static void start()throws Exception{try(var s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){socketPort=s.getLocalPort();}tomcat=new PostgresAuthIT.TestTomcat(true,true,null,Map.of("SOCKET_HOST","127.0.0.1","SOCKET_PORT",Integer.toString(socketPort),"RUNTIME_DIRECTORY",runtime.toString()));tomcat.start();}
    @AfterAll static void stop()throws Exception{if(tomcat!=null)tomcat.close();}
    private static void probe(String script,String mode,String proof,Map<String,Object> extra)throws Exception{
        var config=new LinkedHashMap<String,Object>(Map.of("apiBase","http://127.0.0.1:"+tomcat.httpPort,"socketBase","http://127.0.0.1:"+socketPort,"mode",mode));config.putAll(extra);
        var process=new ProcessBuilder("node","src/test/probe/"+script).redirectError(Path.of("target/verification/"+proof+"_error.log").toFile()).redirectOutput(Path.of("target/verification/"+proof+"_stdout.log").toFile()).start();
        try{try(var input=process.getOutputStream()){input.write(JSON.toJson(config).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(100,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Inspect sanitized submission probe");assertEquals("PASS",StrictJson.parse(Files.readString(Path.of("target/verification/"+proof+".json"))).getAsJsonObject().get("status").getAsString());}
        finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
    }
    @Test void actualPublicRestAndSocketShareFirstResultAndPrivateNotifications()throws Exception{probe("socket-submission.mjs","contracts","T5_2_contracts",Map.of());}
    @Test void realChromeSubmitResultReloadAndTeacherLiveScore()throws Exception{probe("browser-exam-flow.mjs","submission","T5_2_browser",Map.of());}
    @Test void actualCloseOwnershipRollbackIsolationAndPreservedGrade()throws Exception{probe("socket-submission.mjs","close","T5_3_close",Map.of());}
    @Test void realChromeCloseZeroScoresCsvAndUnfinishedFormLock()throws Exception{probe("browser-exam-flow.mjs","close","T5_3_browser",Map.of());}
    @Test void completedResultSurvivesActualTomcatRestart()throws Exception{
        probe("socket-submission.mjs","seed","T5_2_seed",Map.of());var seed=JSON.fromJson(Files.readString(Path.of("target/verification/T5_2_seed.json")),JsonObject.class);
        tomcat.close();tomcat=null;start();probe("socket-submission.mjs","resume","T5_2_restart",Map.of("student",JSON.fromJson(seed.get("student"),Object.class),"grade",JSON.fromJson(seed.get("grade"),Object.class)));
    }
}
