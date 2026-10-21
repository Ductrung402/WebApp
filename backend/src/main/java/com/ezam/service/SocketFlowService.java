package com.ezam.service;

import com.ezam.dao.*;
import com.ezam.dto.*;
import com.ezam.model.*;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.*;
import com.ezam.util.runtime.*;
import java.time.Clock;
import java.util.*;

/** Scoped synchronous work called exclusively from a bounded Socket worker, never Netty IO. */
public final class SocketFlowService {
    private final TransactionManager tx;private final RuntimeStore store;private final RuntimeTransactionService writes;private final JwtUtil jwt;private final Clock clock;
    private final RoomDAO rooms=new RoomDAO();private final StudentDAO students=new StudentDAO();private final SubmissionDAO results=new SubmissionDAO();
    public SocketFlowService(TransactionManager tx,RuntimeStore store,JwtUtil jwt,Clock clock){this.tx=Objects.requireNonNull(tx);this.store=Objects.requireNonNull(store);this.writes=new RuntimeTransactionService(store,tx);this.jwt=Objects.requireNonNull(jwt);this.clock=Objects.requireNonNull(clock);}
    public long now(){return clock.millis();}
    public ExamRecoveryService recoveryService(){return new ExamRecoveryService(this,tx,store);}
    public GradingService gradingService(){return new GradingService(tx,store,clock);}
    public RoomLifecycleService lifecycleService(){return new RoomLifecycleService(tx,store,jwt);}
    public synchronized com.ezam.dto.SubmissionDTO.Request submission(SocketDTO.Member member,String token,SocketDTO.Submit packet){
        verify(member,token);if(!"student".equals(member.userType()))throw new AuthException(403,"Only students can submit answers.");
        return new com.ezam.dto.SubmissionDTO.Request(member.idHs(),member.idPhongThi(),packet.answers().entrySet().stream().map(e->new com.ezam.dto.SubmissionDTO.Answer(e.getKey(),e.getValue())).toList());
    }
    public static long remaining(Run run,long now){return Math.max(0,(run.endsAt()-now+999)/1000);}
    @FunctionalInterface private interface Work<T>{T run()throws Exception;}
    private <T>T guarded(Work<T> work){try{return work.run();}catch(AuthException error){throw error;}catch(Exception error){throw new AuthException(503,"Exam synchronization unavailable.");}}
    public synchronized SocketDTO.Member authorize(SocketDTO.Join join,String token){return guarded(()->{
        String teacher="teacher".equals(join.userType())?jwt.teacherId(token):null;
        return tx.execute(c->{
            var room=rooms.lockByCode(c,join.maCodePhongThi()).orElseThrow(()->new AuthException(404,"Room not found."));
            if(room.getTrangThaiPhong()!=TrangThaiPhongThi.DANG_CHO&&room.getTrangThaiPhong()!=TrangThaiPhongThi.DANG_THI)throw new AuthException(409,"Room is closed or unavailable.");
            RoomDTO.Student student=null;
            if(teacher!=null){if(new TeacherDAO().findById(c,teacher).isEmpty())throw AuthException.credentials();if(!teacher.equals(room.getIdGv()))throw new AuthException(403,"Teacher does not own this room.");}
            else{var h=students.findInRoom(c,join.idHs(),room.getIdPhongThi()).orElseThrow(()->new AuthException(403,"Student is not admitted to this room."));if(results.findInRoom(c,join.idHs(),room.getIdPhongThi()).isEmpty())throw new AuthException(403,"Student is not admitted to this room.");student=RoomDTO.student(h);}
            return new SocketDTO.Member(join.maCodePhongThi(),join.userType(),join.idHs(),room.getIdPhongThi(),student);
        });
    });}
    public synchronized SocketDTO.Member verify(SocketDTO.Member member,String token){var current=authorize(new SocketDTO.Join(member.maCodePhongThi(),member.userType(),member.idHs()),token);if(!current.idPhongThi().equals(member.idPhongThi()))throw new AuthException(403,"Room membership changed.");return current;}
    private Run refreshed(Run r){return new Run(r.runId(),r.idPhongThi(),r.idDeThi(),r.maCodePhongThi(),r.tenDeThi(),r.tenLop(),r.thoiGianLamBai(),r.trangThaiPhong(),r.startsAt(),r.endsAt(),now(),r.cauHoiList());}
    public synchronized Run start(SocketDTO.Member member,String token){return guarded(()->{
        if(!"teacher".equals(member.userType()))throw new AuthException(403,"Only teachers can start exams.");verify(member,token);
        var active=store.readRun(member.maCodePhongThi());if(active.isPresent())return refreshed(active.get());
        String teacher=jwt.teacherId(token);
        writes.execute(member.maCodePhongThi(),null,(c,unit,before)->{
            var room=rooms.findOwned(c,teacher,member.idPhongThi()).orElseThrow(()->new AuthException(403,"Teacher does not own this room."));
            if(before.roomState()!=TrangThaiPhongThi.DANG_CHO)throw new AuthException(409,"Room cannot be started.");
            var exam=new ExamDAO().findOwned(c,teacher,room.getIdDeThi()).orElseThrow(()->new AuthException(404,"Exam not found."));
            // Whitelist SQL projection contains no dap_an_dung; DTO Question has no key either.
            var questions=new ExamDAO().listPublicQuestions(c,room.getIdDeThi()).stream().map(q->new Question(q.idCauHoi(),q.noiDung(),q.dapAnA(),q.dapAnB(),q.dapAnC(),q.dapAnD())).toList();
            if(questions.isEmpty())throw new AuthException(409,"Exam has no questions.");long started=now();
            var run=new Run(UUID.randomUUID().toString(),room.getIdPhongThi(),room.getIdDeThi(),room.getMaCodePhongThi(),exam.getTenDeThi(),room.getTenLop(),room.getThoiGianLamBai(),"DANG_THI",started,Math.addExact(started,Math.multiplyExact((long)room.getThoiGianLamBai(),60000)),started,questions);
            var after=new DbState(before.idPhongThi(),before.maCodePhongThi(),before.idDeThi(),before.thoiGianLamBai(),TrangThaiPhongThi.DANG_THI,null,null,null,null);
            unit.prepareRun(before,after,run);
            if(!rooms.updateStateOwned(c,teacher,room.getIdPhongThi(),TrangThaiPhongThi.DANG_CHO,TrangThaiPhongThi.DANG_THI))throw new AuthException(409,"Room cannot be started.");return run;
        });
        return refreshed(store.readRun(member.maCodePhongThi()).orElseThrow(RuntimeUnavailableException::new));
    });}
    public synchronized Recovery state(SocketDTO.Member member){return guarded(()->{
        if(!"student".equals(member.userType()))throw new AuthException(403,"Student membership required.");
        var row=tx.execute(c->{rooms.lockByCode(c,member.maCodePhongThi()).orElseThrow(()->new AuthException(404,"Room not found."));students.lockInRoom(c,member.idHs(),member.idPhongThi()).orElseThrow(()->new AuthException(404,"Student not found."));return results.lockInRoom(c,member.idHs(),member.idPhongThi()).orElseThrow(()->new AuthException(404,"Student result not found."));});
        // Completed work is never interpreted as a new active attempt, even without recovery metadata.
        if(row.getTrangThai()==TrangThaiBaiLam.HOAN_THANH)return new Recovery(member.idHs(),member.student().hoVaTen(),member.maCodePhongThi(),member.idPhongThi(),row.getTrangThai(),now(),now(),null,null);
        var stored=store.readRecovery(member.maCodePhongThi(),member.idHs());
        if(stored.isEmpty()&&row.getTrangThai()==TrangThaiBaiLam.MAT_KET_NOI)throw new AuthException(503,"Student recovery metadata is unavailable.");
        return stored.orElseGet(()->new Recovery(member.idHs(),member.student().hoVaTen(),member.maCodePhongThi(),member.idPhongThi(),row.getTrangThai(),0,0,null,null));
    });}
    public synchronized List<Recovery> states(SocketDTO.Member member,String token){return guarded(()->{
        verify(member,token);if(!"teacher".equals(member.userType()))throw new AuthException(403,"Teacher membership required.");
        var roster=tx.execute(c->students.listByOwnedRoom(c,jwt.teacherId(token),member.idPhongThi()));var list=new ArrayList<Recovery>();
        for(var h:roster)list.add(state(new SocketDTO.Member(member.maCodePhongThi(),"student",h.getIdHs(),member.idPhongThi(),RoomDTO.student(h))));return List.copyOf(list);
    });}
    public synchronized SocketDTO.Sync sync(SocketDTO.Member member,String token,long received){return guarded(()->{
        var verified=verify(member,token);var run=store.readRun(verified.maCodePhongThi()).map(this::refreshed).orElse(null);Recovery student="student".equals(verified.userType())?state(verified):null;Receipt receipt=null;
        if(student!=null&&student.trangThai()==TrangThaiBaiLam.HOAN_THANH&&run!=null){receipt=store.readFirst(verified.maCodePhongThi(),verified.idHs()).map(s->s.receipt(true)).orElse(null);}
        return new SocketDTO.Sync("ok",now(),received,run,receipt,student);
    });}
}
