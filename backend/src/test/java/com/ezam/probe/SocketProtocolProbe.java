package com.ezam.probe;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Opt-in localhost protocol probe. No Servlet, DB, JWT or exam implementation. */
public final class SocketProtocolProbe {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String FIXTURE_TOKEN = "synthetic-t03-auth-only-not-a-jwt";
    private static final String CODE = "AB12CD";
    private static final Path OUTPUT = Path.of("target/verification/socket-probe");

    private SocketProtocolProbe() { }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        Files.deleteIfExists(OUTPUT.resolve("client-result.json"));
        Files.deleteIfExists(OUTPUT.resolve("server-result.json"));
        List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();
        Map<String, String> before = protectedHashes();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> membership = new ConcurrentHashMap<>();
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        Configuration config = new Configuration();
        config.setHostname("127.0.0.1");
        config.setPort(port);
        config.setBossThreads(1);
        config.setWorkerThreads(2);
        // The existing ACK contract requires idHs:null for teachers. The library's
        // NON_NULL default drops it; preserve explicit nulls instead of weakening assertions.
        config.setJsonSupport(new JacksonJsonSupport() {
            @Override
            protected void init(ObjectMapper mapper) {
                super.init(mapper);
                mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
            }
        });
        config.setTransports(Transport.WEBSOCKET);
        config.setPingInterval(1000);
        config.setPingTimeout(3000);
        SocketIOServer server = new SocketIOServer(config);
        Process child = null;
        boolean started = false;
        int exit = 2;
        try {
            boolean hasAuthAccessor = java.util.Arrays.stream(HandshakeData.class.getMethods())
                    .anyMatch(method -> method.getName().equals("getAuthToken"));
            observations.add(Map.of("event", "public_api_inspection",
                    "handshakeAuthAccessor", hasAuthAccessor));
            // netty-socketio names its default namespace "" internally.
            server.getNamespace("").addAuthTokenListener((auth, client) -> {
                boolean supplied = auth instanceof Map<?, ?> map && map.containsKey("token");
                boolean matches = auth instanceof Map<?, ?> map && FIXTURE_TOKEN.equals(map.get("token"));
                observations.add(Map.of("event", "server_auth_payload", "sid", client.getSessionId().toString(),
                        "authTokenSupplied", supplied, "authTokenRead", matches));
                return supplied && !matches
                        ? new AuthTokenResult(false, "Invalid synthetic authentication.")
                        : AuthTokenResult.AuthTokenResultSuccess;
            });
            server.addConnectListener(client -> observations.add(Map.of(
                    "event", "server_connect", "sid", client.getSessionId().toString(),
                    "transport", client.getTransport().name(),
                    "engineProtocol", String.valueOf(client.getHandshakeData().getSingleUrlParam("EIO")),
                    "authTokenRead", fixtureAuthMatches(client))));
            server.addDisconnectListener(client -> {
                membership.remove(client.getSessionId().toString());
                observations.add(Map.of("event", "server_disconnect", "sid", client.getSessionId().toString()));
            });
            server.addEventListener("join_exam_room", Map.class, (client, payload, ack) -> {
                String sid = client.getSessionId().toString();
                observations.add(Map.of("event", "server_join_received", "sid", sid,
                        "authTokenRead", fixtureAuthMatches(client)));
                String role = String.valueOf(payload.get("userType"));
                Object idHs = payload.get("idHs");
                if (!CODE.equals(payload.get("maCodePhongThi"))
                        || !("teacher".equals(role) || "student".equals(role))
                        || ("student".equals(role) && !"fixture-student-t03".equals(idHs))
                        || ("teacher".equals(role) && idHs != null)) {
                    ack.sendAckData(Map.of("status", "error", "statusCode", 422,
                            "detail", "Invalid room join payload."));
                    return;
                }
                // Do not bypass the auth requirement to make the probe green.
                if ("teacher".equals(role) && !fixtureAuthMatches(client)) {
                    ack.sendAckData(Map.of("status", "error", "statusCode", 401,
                            "detail", "Synthetic handshake auth was not available."));
                    observations.add(Map.of("event", "teacher_auth_unavailable", "sid", sid));
                    return;
                }
                client.joinRoom(CODE);
                if ("teacher".equals(role)) client.joinRoom("teachers:" + CODE);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "ok");
                response.put("maCodePhongThi", CODE);
                response.put("userType", role);
                response.put("idHs", idHs);
                response.put("idPhongThi", "fixture-room-t03");
                membership.put(sid, response);
                ack.sendAckData(response);
                observations.add(Map.of("event", "server_join_acked", "sid", sid,
                        "rooms", new ArrayList<>(client.getAllRooms())));
            });
            var start = server.startAsync();
            if (!start.await(10, TimeUnit.SECONDS) || !start.isSuccess()) {
                throw new IllegalStateException("Local probe server could not start.");
            }
            started = true;
            System.out.println("PROBE server_started transport=websocket host=127.0.0.1 port=" + port);
            child = new ProcessBuilder("node", "src/test/probe/socket-client-probe.mjs",
                    "http://127.0.0.1:" + port, OUTPUT.toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(OUTPUT.resolve("client.log").toFile()).start();
            if (!child.waitFor(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Node probe exceeded its bounded execution time.");
            }
            result.put("clientExitCode", child.exitValue());
            Path clientResult = OUTPUT.resolve("client-result.json");
            if (Files.isRegularFile(clientResult)) {
                Map<?, ?> clientData = JSON.fromJson(Files.readString(clientResult), Map.class);
                result.put("client", clientData);
                if (child.exitValue() == 0 && Boolean.TRUE.equals(clientData.get("allPassed"))) exit = 0;
            }
        } catch (Exception | LinkageError exception) {
            result.put("infrastructureError", exception.getClass().getSimpleName());
            result.put("infrastructureDetail", String.valueOf(exception.getMessage())
                    .replace(FIXTURE_TOKEN, "[redacted]"));
        } finally {
            if (child != null && child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            try {
                server.stop();
            } catch (RuntimeException cleanupError) {
                result.put("serverCleanupError", cleanupError.getClass().getSimpleName());
            }
            boolean portClosed;
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                portClosed = false;
            } catch (java.io.IOException expected) {
                portClosed = true;
            }
            result.put("task", "T0.3");
            result.put("serverVersion", "2.0.13");
            result.put("serverStarted", started);
            result.put("serverObservations", observations);
            result.put("serverStopped", portClosed);
            result.put("clientStopped", child == null || !child.isAlive());
            result.put("protectedSourcesUnchanged", before.equals(protectedHashes()));
            result.put("protectedSourceSha256", before);
            result.put("databaseAccess", false);
            result.put("syntheticOnly", true);
            if (!portClosed || child != null && child.isAlive() || !before.equals(protectedHashes())) exit = 2;
            result.put("gate", exit == 0 ? "PASS" : started && result.containsKey("client")
                    && !result.containsKey("infrastructureError")
                    ? "INCOMPATIBLE" : "NOT_VERIFIED");
            Files.writeString(OUTPUT.resolve("server-result.json"), JSON.toJson(result), StandardCharsets.UTF_8);
            System.out.println("PROBE gate=" + result.get("gate") + " serverStopped=" + portClosed
                    + " clientStopped=" + result.get("clientStopped"));
        }
        // A compatibility failure remains a nonzero exit, never an expected-green test.
        System.exit(exit);
    }

    private static boolean fixtureAuthMatches(SocketIOClient client) {
        // Native CONNECT auth in 2.0.13, not a query/header fallback or parser patch.
        Object auth = client.getHandshakeData().getAuthToken();
        return auth instanceof Map<?, ?> map && FIXTURE_TOKEN.equals(map.get("token"));
    }

    private static Map<String, String> protectedHashes() throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String name : List.of("pom.xml", "src/main/webapp/WEB-INF/web.xml",
                "src/main/java/com/ezam/util/PasswordUtil.java", "../frontend/package.json",
                "../frontend/package-lock.json", "../frontend/src/services/socket.js")) {
            byte[] bytes = Files.readAllBytes(Path.of(name));
            hashes.put(name, java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        return hashes;
    }
}
