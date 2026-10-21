package com.ezam.integration;

import com.ezam.util.StrictJson;
import com.google.gson.Gson;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PostgresLauncherIT {
    @Test void productionPowerShellLauncherRunsJavaWithViteLoginDeepLinkAndSocket()throws Exception{
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        var runtime=Files.createTempDirectory(Path.of("target/verification"),"launcher-runtime-").toAbsolutePath();
        try(var server=new PostgresAuthIT.TestTomcat(true,true,null,Map.of("EZAM_TEST_LOCAL_LAUNCHER","true","SOCKET_HOST","127.0.0.1","SOCKET_PORT",Integer.toString(port),"RUNTIME_DIRECTORY",runtime.toString()))){
            server.start();String api="http://127.0.0.1:"+server.httpPort;
            var reply=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(api+"/api/health")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,reply.statusCode());assertEquals("Quiz AI & Exam System",StrictJson.parse(reply.body()).getAsJsonObject().get("app").getAsString());
            var args=Map.of("apiBase",api,"socketBase","http://127.0.0.1:"+port,"mode","flow");
            var process=new ProcessBuilder("node","src/test/probe/browser-exam-flow.mjs").redirectErrorStream(true).redirectOutput(Path.of("target/verification/T6_1_launcher_probe.log").toFile()).start();
            try{try(var input=process.getOutputStream()){input.write(new Gson().toJson(args).getBytes(StandardCharsets.UTF_8));}assertTrue(process.waitFor(80,TimeUnit.SECONDS));assertEquals(0,process.exitValue());var proof=StrictJson.parse(Files.readString(Path.of("target/verification/T4_2_browser.json"))).getAsJsonObject();assertEquals("PASS",proof.get("status").getAsString());proof.addProperty("productionPowerShellLauncher",true);proof.addProperty("healthExactContract",true);Files.writeString(Path.of("target/verification/T6_1_local.json"),proof.toString());}
            finally{if(process.isAlive()){process.destroyForcibly();process.waitFor();}}
        }
    }
}
