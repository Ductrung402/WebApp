package com.ezam.integration;

import com.ezam.util.StrictJson;
import com.google.gson.Gson;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit qa-integration profile only: real Gemini, no stub or application DB. */
class PostgresQaIT {
    @Test void eightStepsChromePostgresSocketAndRealGemini()throws Exception{
        int port;try(var s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=s.getLocalPort();}
        var runtime=Files.createTempDirectory(Path.of("target/verification"),"qa-runtime-").toAbsolutePath();
        try(var server=new PostgresAuthIT.TestTomcat(true,true,null,Map.of("EZAM_TEST_LIVE_GEMINI","true","SOCKET_HOST","127.0.0.1","SOCKET_PORT",Integer.toString(port),"RUNTIME_DIRECTORY",runtime.toString()))){
            server.start();var process=new ProcessBuilder("node","src/test/probe/full-e2e.mjs").redirectErrorStream(true).redirectOutput(Path.of("target/verification/T6_2_e2e.log").toFile()).start();
            try{try(var in=process.getOutputStream()){in.write(new Gson().toJson(Map.of("apiUrl","http://127.0.0.1:"+server.httpPort,"socketUrl","http://127.0.0.1:"+port)).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(150,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Inspect sanitized full E2E report");var proof=StrictJson.parse(Files.readString(Path.of("target/verification/qa/e2e_results.json"))).getAsJsonObject();assertEquals(8,proof.getAsJsonArray("steps").size());assertTrue(proof.getAsJsonArray("steps").asList().stream().allMatch(p->"PASS".equals(p.getAsJsonObject().get("status").getAsString())));assertTrue(proof.getAsJsonArray("browserErrors").isEmpty());}
            finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
        }
    }
}
