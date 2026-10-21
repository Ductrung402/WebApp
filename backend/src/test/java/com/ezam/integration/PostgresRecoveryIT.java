package com.ezam.integration;

import com.ezam.dao.*;
import com.ezam.dto.*;
import com.ezam.model.*;
import com.ezam.service.*;
import com.ezam.util.*;
import com.ezam.util.runtime.*;
import java.net.URI;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled Clock + actual row locks/COMMIT failures; no application database or T5 grading. */
class PostgresRecoveryIT {
    @TempDir Path directory;
    private static DBUtil pool;private static TransactionManager tx;private static String teacherId;
    private RuntimeStore store;private SocketFlowService flow;private ExamRecoveryService recovery;private MutableClock clock;private SocketDTO.Member teacher,student;private String token;private String code;
    private static final Map<String,Object> proof=new LinkedHashMap<>();
    static final class MutableClock extends Clock {
        final AtomicLong time=new AtomicLong(1_790_000_000_000L);
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(time.get());}public long millis(){return time.get();}void advance(long ms){time.addAndGet(ms);}
    }
    @BeforeAll static void setup()throws Exception{
        String url=System.getenv("TEST_DATABASE_URL");var u=URI.create(url==null?"":url);if(!"127.0.0.1".equals(u.getHost())||!"/ezam_t21_test".equals(u.getPath()))throw new IllegalStateException("Use isolated runner");
        pool=DBUtil.open(DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL",url)),4,3000);tx=new TransactionManager(pool::getConnection);
        try(var c=pool.getConnection();var q=c.prepareStatement("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");var r=q.executeQuery()){assertTrue(r.next());assertEquals("EZam isolated auth test T2.1",r.getString(1));}
        teacherId=UUID.randomUUID().toString();var g=new GiaoVien();g.setIdGv(teacherId);g.setEmail(teacherId+"@example.com");g.setHoTen("Recovery fixture");g.setMatKhau(PasswordUtil.hashPassword("synthetic-password"));tx.execute(c->{new TeacherDAO().insert(c,g);return null;});
    }
    @AfterAll static void done()throws Exception{if(pool!=null)pool.close();Files.writeString(Path.of("target/verification/T4_3_clock_postgres.json"),SocketDTO.JSON.toJson(proof));}
    @BeforeEach void seed(){
        clock=new MutableClock();var jwt=JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY","synthetic-recovery-jwt-key-at-least-32-bytes"),clock);token=jwt.createAccessToken(teacherId);
        store=new RuntimeStore(directory,new RuntimeDatabaseEvidence(tx));flow=new SocketFlowService(tx,store,jwt,clock);recovery=flow.recoveryService();
        var exam=new ExamService(tx,new ExamDAO(),clock).create(teacherId,new ExamDTO.Manual("Recovery exam",null,"TEXT",null,List.of(new ExamDTO.Question("Water?","H2O","CO2","O2","N2","A"))));
        var rooms=new RoomService(tx,clock);var room=rooms.create(teacherId,new RoomDTO.Create(exam.idDeThi(),"Recovery",1));code=room.maCodePhongThi();var joined=rooms.join(new RoomDTO.Join("Recovery student",code));
        teacher=flow.authorize(new SocketDTO.Join(code,"teacher",null),token);student=flow.authorize(new SocketDTO.Join(code,"student",joined.idHs()),null);
    }
    @AfterEach void close(){if(store!=null)store.close();}
    private TrangThaiBaiLam dbState()throws Exception{return tx.execute(c->new SubmissionDAO().findInRoom(c,student.idHs(),student.idPhongThi()).orElseThrow().getTrangThai());}
    @Test void waitingDisconnectAndRecoveryDoNotStartAnExam()throws Exception{
        var lost=recovery.markDisconnected(student,clock.millis());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());assertNull(lost.frozenRemainingSeconds());var r=recovery.reconnect(student,null,clock.millis());assertNull(r.exam());assertNull(r.remainingSeconds());assertFalse(r.examExpired());assertEquals(2,r.studentState().stateVersion());assertEquals(TrangThaiBaiLam.DANG_THI,dbState());proof.put("waitingRecovery",true);
    }
    @Test void controlledClockRestoresSameDeadlineAndRemainingDecreases()throws Exception{
        var run=flow.start(teacher,token);clock.advance(10_000);var lost=recovery.markDisconnected(student,clock.millis());assertEquals(50,lost.frozenRemainingSeconds());assertEquals(1,lost.stateVersion());clock.advance(15_000);var recovered=recovery.reconnect(student,null,clock.millis());assertEquals(35,recovered.remainingSeconds());assertEquals(run.runId(),recovered.exam().runId());assertEquals(run.endsAt(),recovered.exam().endsAt());assertEquals(2,recovered.studentState().stateVersion());assertEquals(TrangThaiBaiLam.DANG_THI,dbState());proof.put("50to35SecondsSameDeadline",true);
    }
    @Test void expiredDoesNotRestoreDisconnectedState()throws Exception{
        flow.start(teacher,token);clock.advance(10_000);recovery.markDisconnected(student,clock.millis());clock.advance(51_000);var r=recovery.reconnect(student,null,clock.millis());assertTrue(r.examExpired());assertEquals(0,r.remainingSeconds());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());assertEquals(1,r.studentState().stateVersion());proof.put("expiredNoExtension",true);
    }
    @Test void duplicateDisconnectKeepsOriginalFreezeAndVersion()throws Exception{
        flow.start(teacher,token);clock.advance(5_000);var first=recovery.markDisconnected(student,clock.millis());clock.advance(10_000);assertEquals(first,recovery.markDisconnected(student,clock.millis()));assertEquals(first,flow.state(student));
    }
    @Test void timeSpentWaitingForWriteConnectionCannotReopenExpiredWork()throws Exception{
        flow.start(teacher,token);clock.advance(10_000);recovery.markDisconnected(student,clock.millis());
        var reached=new CountDownLatch(1);var resume=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        var delayed=new TransactionManager(()->{reached.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))throw new SQLException("test coordination timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("interrupted");}return pool.getConnection();});
        var delayedRecovery=new ExamRecoveryService(flow,delayed,store);
        try{var pending=executor.submit(()->delayedRecovery.reconnect(student,null,clock.millis()));assertTrue(reached.await(5,TimeUnit.SECONDS));clock.advance(51_000);resume.countDown();var reply=pending.get(5,TimeUnit.SECONDS);assertTrue(reply.examExpired());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,reply.studentState().trangThai());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());}
        finally{resume.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}proof.put("deadlineRecheckedAfterConnectionWait",true);
    }
    @Test void missingTimingStillMarksLostButReconnectFailsClosed()throws Exception{
        flow.start(teacher,token);Files.delete(directory.resolve("run-"+code+".json"));var lost=recovery.markDisconnected(student,clock.millis());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());assertNull(lost.frozenRemainingSeconds());assertEquals(503,assertThrows(AuthException.class,()->recovery.reconnect(student,null,clock.millis())).status());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());proof.put("missingTimingDisconnectStillCommitted",true);
    }
    @Test void missingRecoveryMetadataCannotInventVersion()throws Exception{
        flow.start(teacher,token);recovery.markDisconnected(student,clock.millis());try(var paths=Files.list(directory)){Files.delete(paths.filter(p->p.getFileName().toString().startsWith("recovery-")&&p.toString().endsWith(".json")).findFirst().orElseThrow());}
        assertEquals(503,assertThrows(AuthException.class,()->recovery.reconnect(student,null,clock.millis())).status());assertEquals(TrangThaiBaiLam.MAT_KET_NOI,dbState());
    }
    @Test void actualDeferredCommitFailureDiscardsPreparedRecovery()throws Exception{
        tx.execute(c->{try(var s=c.prepareStatement("UPDATE hoc_sinh SET ho_va_ten='fail-disconnect-commit' WHERE id_hs=?")){s.setString(1,student.idHs());s.executeUpdate();}return null;});
        assertEquals(503,assertThrows(AuthException.class,()->recovery.markDisconnected(student,clock.millis())).status());assertEquals(TrangThaiBaiLam.DANG_THI,dbState());assertTrue(store.readRecovery(code,student.idHs()).isEmpty());proof.put("actualDeferredCommitRollback",true);
    }
    @Test void recoveryFileWriteFailureDoesNotChangeDb()throws Exception{
        store.close();store=new RuntimeStore(directory,new RuntimeDatabaseEvidence(tx),(step,path)->{if(path.getFileName().toString().startsWith("recovery-")&&step.equals("before-write"))throw new java.io.IOException("synthetic");});
        var jwt=JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY","synthetic-recovery-jwt-key-at-least-32-bytes"),clock);flow=new SocketFlowService(tx,store,jwt,clock);recovery=flow.recoveryService();assertEquals(503,assertThrows(AuthException.class,()->recovery.markDisconnected(student,clock.millis())).status());assertEquals(TrangThaiBaiLam.DANG_THI,dbState());proof.put("fileFailureRollback",true);
    }
    @Test void completedTransactionWinsRaceAndCannotBeDowngraded()throws Exception{
        flow.start(teacher,token);recovery.markDisconnected(student,clock.millis());var executor=Executors.newSingleThreadExecutor();
        try(var c=pool.getConnection()){
            c.setAutoCommit(false);new RoomDAO().lockByCode(c,code).orElseThrow();var k=new SubmissionDAO().lockInRoom(c,student.idHs(),student.idPhongThi()).orElseThrow();k.setTrangThai(TrangThaiBaiLam.HOAN_THANH);k.setDiemSo(8.5);assertTrue(new SubmissionDAO().updateUnfinished(c,k));
            var pending=executor.submit(()->recovery.markDisconnected(student,clock.millis()));Thread.sleep(150);assertFalse(pending.isDone());c.commit();assertNull(pending.get(5,TimeUnit.SECONDS));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(TrangThaiBaiLam.HOAN_THANH,dbState());var restored=recovery.reconnect(student,null,clock.millis());assertEquals(TrangThaiBaiLam.HOAN_THANH,restored.studentState().trangThai());assertEquals(8.5,tx.execute(c->new SubmissionDAO().findInRoom(c,student.idHs(),student.idPhongThi()).orElseThrow().getDiemSo()));proof.put("completionRowLockWinsNoDowngrade",true);
    }
    @Test void restartReadsCommittedRecoveryAndKeepsVersion()throws Exception{
        var run=flow.start(teacher,token);clock.advance(8_000);var lost=recovery.markDisconnected(student,clock.millis());store.close();store=new RuntimeStore(directory,new RuntimeDatabaseEvidence(tx));var jwt=JwtUtil.fromEnvironment(Map.of("JWT_SECRET_KEY","synthetic-recovery-jwt-key-at-least-32-bytes"),clock);flow=new SocketFlowService(tx,store,jwt,clock);recovery=flow.recoveryService();assertEquals(lost,flow.state(student));clock.advance(7_000);var r=recovery.reconnect(student,null,clock.millis());assertEquals(45,r.remainingSeconds());assertEquals(2,r.studentState().stateVersion());assertEquals(run.endsAt(),r.exam().endsAt());proof.put("restartRecoveryVersionDeadline",true);
    }
}
