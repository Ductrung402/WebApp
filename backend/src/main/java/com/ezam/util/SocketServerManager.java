package com.ezam.util;

import com.corundumstudio.socketio.*;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.ezam.dto.*;
import com.ezam.service.SocketFlowService;
import com.ezam.service.ExamRecoveryService;
import java.util.*;
import java.util.concurrent.*;

/** One bounded ordered worker per JVM. Netty handlers enqueue work, never borrow JDBC connections. */
public final class SocketServerManager implements AutoCloseable {
    private final SocketIOServer server;private final SocketFlowService flow;
    private final ExamRecoveryService recovery;
    private final com.ezam.service.SubmissionService submissions;
    private final ThreadPoolExecutor worker;
    // Access only on the ordered worker. Never store JDBC Connections or JWTs in this map.
    private final Map<UUID,SocketDTO.Member> membership=new HashMap<>();
    // Disconnect notices cannot be dropped when the bounded request queue is full.
    private final ConcurrentHashMap<UUID,Long> disconnected=new ConcurrentHashMap<>();
    private volatile boolean closing;
    private final java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
    public SocketServerManager(String host,int port,SocketFlowService flow,JwtUtil jwt){
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid Socket configuration.");this.flow=Objects.requireNonNull(flow);this.recovery=flow.recoveryService();
        submissions=new com.ezam.service.SubmissionService(flow.gradingService(),this::notifySubmitted);
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->{var t=new Thread(r,"ezam-socket-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        Configuration cfg=new Configuration();cfg.setHostname(host);cfg.setPort(port);cfg.setBossThreads(1);cfg.setWorkerThreads(2);cfg.setTransports(Transport.WEBSOCKET,Transport.POLLING);cfg.setMaxFramePayloadLength(1_048_576);cfg.setMaxHttpContentLength(1_048_576);
        // AUTO would send an empty ACK when the Netty callback returns before JDBC finishes.
        cfg.setAckMode(AckMode.MANUAL);
        cfg.setJsonSupport(new JacksonJsonSupport(){@Override protected void init(ObjectMapper mapper){super.init(mapper);mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);}});
        server=new SocketIOServer(cfg);
        server.getNamespace("").addAuthTokenListener((auth,client)->{
            // Crypto-only validation here; account/owner SQL is deferred to the worker at join and every action.
            try{String token=tokenFromAuth(auth);if(token!=null)jwt.teacherId(token);return AuthTokenResult.AuthTokenResultSuccess;}
            catch(RuntimeException invalid){return new AuthTokenResult(false,"Invalid teacher authentication.");}
        });
        event("join_exam_room",(client,data,received)->{
            var join=SocketDTO.join(data);var verified=flow.authorize(join,token(client));
            if(!client.isChannelOpen())throw new AuthException(409,"Socket disconnected during admission.");
            var prior=membership.get(client.getSessionId());
            if(prior!=null&&!prior.equals(verified))throw new AuthException(409,"Socket already joined another membership.");
            membership.put(client.getSessionId(),verified);client.joinRoom(verified.maCodePhongThi());if("teacher".equals(verified.userType()))client.joinRoom(teachers(verified.maCodePhongThi()));
            roster(verified.maCodePhongThi());
            if("teacher".equals(verified.userType()))emit(teachers(verified.maCodePhongThi()),"student_states_updated",Map.of("maCodePhongThi",verified.maCodePhongThi(),"hocSinhList",flow.states(verified,token(client))));
            System.out.println("socket_joined sid="+client.getSessionId()+" room="+verified.maCodePhongThi()+" role="+verified.userType());
            return new SocketDTO.Joined("ok",verified.maCodePhongThi(),verified.userType(),verified.idHs(),verified.idPhongThi());
        });
        event("start_exam",(client,data,received)->{var member=member(client,SocketDTO.code(data));var run=flow.start(member,token(client));emit(member.maCodePhongThi(),"exam_started",run);return Map.of("status","ok","serverTime",flow.now(),"exam",run);});
        event("sync_exam",(client,data,received)->{var member=member(client,SocketDTO.code(data));var snapshot=flow.sync(member,token(client),received);if("teacher".equals(member.userType()))emit(teachers(member.maCodePhongThi()),"student_states_updated",Map.of("maCodePhongThi",member.maCodePhongThi(),"hocSinhList",flow.states(member,token(client))));return snapshot;});
        event("reconnect_exam",(client,data,received)->{var request=SocketDTO.reconnect(data);var member=member(client,request.maCodePhongThi());if(!"student".equals(member.userType())||!request.idHs().equals(member.idHs()))throw new AuthException(403,"Student does not match socket membership.");var restored=recovery.reconnect(member,token(client),received);emit(teachers(member.maCodePhongThi()),"student_reconnected",restored.studentState());return restored;});
        event("submit_exam",(client,data,received)->{var packet=SocketDTO.submit(data);var member=member(client,packet.maCodePhongThi());var request=flow.submission(member,token(client),packet);var outcome=submissions.submit(request,packet.runId(),packet.reason());return Map.of("status","ok","submission",outcome.receipt(),"result",outcome.result());});
        event("close_exam_room",(client,data,received)->{var member=member(client,SocketDTO.code(data));var closed=flow.lifecycleService().close(member,token(client));emit(member.maCodePhongThi(),"room_closed",closed);return Map.of("status","ok","idPhongThi",closed.idPhongThi(),"maCodePhongThi",closed.maCodePhongThi(),"trangThaiPhong",closed.trangThaiPhong());});
        server.addDisconnectListener(client->{if(!closing){disconnected.putIfAbsent(client.getSessionId(),flow.now());enqueue(this::drainDisconnects,null);}});
    }
    public static String teachers(String code){return "teachers:"+code;}
    public void notifySubmitted(SubmissionDTO.Submitted event){emit(teachers(event.maCodePhongThi()),"student_submitted",event);}
    private static String token(SocketIOClient client){return tokenFromAuth(client.getHandshakeData().getAuthToken());}
    private static String tokenFromAuth(Object auth){if(auth==null)return null;if(!(auth instanceof Map<?,?> map)||map.keySet().stream().anyMatch(key->!"token".equals(key)))throw AuthException.credentials();Object token=map.get("token");if(token==null)return null;if(!(token instanceof String text))throw AuthException.credentials();return DtoValidator.nonEmpty(text,"token");}
    private SocketDTO.Member member(SocketIOClient client,String code){var value=membership.get(client.getSessionId());if(value==null||!value.maCodePhongThi().equals(code))throw new AuthException(403,"Join this exam room first.");return value;}
    private void roster(String code){
        var distinct=new TreeMap<String,RoomDTO.Student>();for(var m:membership.values())if(m.maCodePhongThi().equals(code)&&m.student()!=null)distinct.put(m.idHs(),m.student());
        emit(teachers(code),"room_students_updated",Map.of("maCodePhongThi",code,"hocSinhList",List.copyOf(distinct.values())));
    }
    private void drainDisconnects(){
        for(var entry:disconnected.entrySet())if(disconnected.remove(entry.getKey(),entry.getValue())){
            var prior=membership.remove(entry.getKey());if(prior==null)continue;
            roster(prior.maCodePhongThi());
            if(prior.student()==null||membership.values().stream().anyMatch(m->m.maCodePhongThi().equals(prior.maCodePhongThi())&&Objects.equals(m.idHs(),prior.idHs())))continue;
            try{var state=recovery.markDisconnected(prior,entry.getValue());if(state!=null)emit(teachers(prior.maCodePhongThi()),"student_disconnected",state);}
            catch(RuntimeException failure){var packet=SocketDTO.error(503,"Unable to persist disconnected student state.");packet.put("maCodePhongThi",prior.maCodePhongThi());packet.put("idHs",prior.idHs());emit(teachers(prior.maCodePhongThi()),"student_connection_error",packet);}
        }
    }
    private void emit(String room,String event,Object data){try{server.getRoomOperations(room).sendEvent(event,SocketDTO.wire(data));}catch(RuntimeException failedDelivery){System.err.println("Socket broadcast unavailable after committed work.");}}
    @FunctionalInterface private interface Action{Object run(SocketIOClient client,Object data,long received)throws Exception;}
    private void event(String name,Action action){server.addEventListener(name,Object.class,(client,data,ack)->{long received=flow.now();enqueue(()->{
        Object response;try{if(!client.isChannelOpen())throw new AuthException(409,"Socket is disconnected.");response=action.run(client,data,received);}
        catch(AuthException error){response=SocketDTO.error(error.status(),error.status()==422?SocketDTO.validationMessage(name):Objects.toString(error.detail(),"Exam service unavailable."));}catch(Exception error){response=SocketDTO.error(503,"Exam service unavailable.");}
        if(ack.isAckRequested())ack.sendAckData(SocketDTO.wire(response));
    },ack);});}
    private void enqueue(Runnable task,AckRequest ack){if(closing){if(ack!=null&&ack.isAckRequested())ack.sendAckData(SocketDTO.error(503,"Socket server is stopping."));return;}try{worker.execute(()->{try{drainDisconnects();task.run();}finally{drainDisconnects();}});}catch(RejectedExecutionException busy){if(ack!=null&&ack.isAckRequested())ack.sendAckData(SocketDTO.error(503,"Socket server is busy. Please retry."));}}
    public void start(){try{server.start();}catch(RuntimeException failure){close();throw failure;}}
    @Override public void close(){
        if(!stopped.compareAndSet(false,true))return;closing=true;
        try{server.stop();}finally{
            worker.shutdown();try{if(!worker.awaitTermination(20,TimeUnit.SECONDS)){worker.shutdownNow();if(!worker.awaitTermination(5,TimeUnit.SECONDS))System.err.println("Socket worker shutdown is still waiting for JDBC cancellation.");}}
            catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
            if(worker.isTerminated()){membership.clear();disconnected.clear();}
            io.netty.util.concurrent.FastThreadLocal.removeAll();
        }
    }
}
