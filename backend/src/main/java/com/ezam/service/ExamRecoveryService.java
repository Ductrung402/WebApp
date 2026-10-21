package com.ezam.service;

import com.ezam.dao.*;
import com.ezam.dto.SocketDTO;
import com.ezam.model.*;
import com.ezam.model.runtime.RuntimeData.*;
import com.ezam.util.*;
import com.ezam.util.runtime.*;

/** ERR_02: DB truth + prepared recovery sidecar, no deadline extension or new admission. */
public final class ExamRecoveryService {
    private final SocketFlowService flow;
    private final TransactionManager tx;
    private final RuntimeStore store;
    private final RuntimeTransactionService writes;
    public ExamRecoveryService(SocketFlowService flow,TransactionManager tx,RuntimeStore store){this.flow=java.util.Objects.requireNonNull(flow);this.tx=java.util.Objects.requireNonNull(tx);this.store=java.util.Objects.requireNonNull(store);this.writes=new RuntimeTransactionService(store,tx);}
    private record Target(PhongThi room,HocSinh student,KetQuaThi result){ }
    private Target target(SocketDTO.Member member)throws java.sql.SQLException{
        return tx.execute(c->{var room=new RoomDAO().lockByCode(c,member.maCodePhongThi());if(room.isEmpty()||!room.get().getIdPhongThi().equals(member.idPhongThi()))return null;
            var student=new StudentDAO().lockInRoom(c,member.idHs(),member.idPhongThi());if(student.isEmpty())return null;
            var result=new SubmissionDAO().lockInRoom(c,member.idHs(),member.idPhongThi());return result.isEmpty()?null:new Target(room.get(),student.get(),result.get());});
    }
    private static DbState after(DbState before,TrangThaiBaiLam state){return new DbState(before.idPhongThi(),before.maCodePhongThi(),before.idDeThi(),before.thoiGianLamBai(),before.roomState(),before.idHs(),before.idKq(),state,before.diemSo());}
    private static boolean finished(DbState before){return before.resultState()==TrangThaiBaiLam.HOAN_THANH||before.roomState()==TrangThaiPhongThi.DA_DONG;}
    /** Called only after membership confirms this student's last active socket has left. */
    public Recovery markDisconnected(SocketDTO.Member member,long disconnectedAt){
        if(!"student".equals(member.userType()))return null;
        synchronized(flow){try{
            var target=target(member);if(target==null||target.result().getTrangThai()==TrangThaiBaiLam.HOAN_THANH||target.room().getTrangThaiPhong()==TrangThaiPhongThi.DA_DONG)return null;
            var prior=flow.state(member);if(prior.trangThai()==TrangThaiBaiLam.MAT_KET_NOI)return prior;
            Long remaining=null;
            if(target.room().getTrangThaiPhong()==TrangThaiPhongThi.DANG_THI)try{var run=store.readRun(member.maCodePhongThi()).orElseThrow(RuntimeUnavailableException::new);remaining=SocketFlowService.remaining(run,disconnectedAt);}
                catch(RuntimeUnavailableException missingTiming){/* Mandatory disconnect persists, even if timing was lost. Reconnect will fail closed. */}
            long changedAt=Math.max(disconnectedAt,prior.stateChangedAt());
            var state=new Recovery(member.idHs(),target.student().getHoVaTen(),member.maCodePhongThi(),member.idPhongThi(),TrangThaiBaiLam.MAT_KET_NOI,Math.addExact(prior.stateVersion(),1),changedAt,disconnectedAt,remaining);
            return writes.execute(member.maCodePhongThi(),member.idHs(),(c,unit,before)->{
                if(finished(before))return null; // concurrent completion/close won the row lock
                if(before.resultState()==TrangThaiBaiLam.MAT_KET_NOI)throw new RuntimeUnavailableException();
                unit.prepareRecovery(before,after(before,TrangThaiBaiLam.MAT_KET_NOI),state);
                var result=new SubmissionDAO().lockInRoom(c,member.idHs(),member.idPhongThi()).orElseThrow(RuntimeUnavailableException::new);result.setTrangThai(TrangThaiBaiLam.MAT_KET_NOI);
                if(!new SubmissionDAO().updateUnfinished(c,result))throw new RuntimeUnavailableException();return state;
            }); // RuntimeTransactionService commits/reconciles before this returns; caller then emits.
        }catch(Exception failure){throw new AuthException(503,"Unable to persist disconnected student state.");}}
    }
    public SocketDTO.Reconnected reconnect(SocketDTO.Member member,String token,long received){
        if(!"student".equals(member.userType()))throw new AuthException(403,"Student membership required.");
        synchronized(flow){try{
            flow.verify(member,token);var prior=flow.state(member);
            var run=store.readRun(member.maCodePhongThi()).orElse(null);Long remaining=run==null?null:SocketFlowService.remaining(run,flow.now());boolean expired=remaining!=null&&remaining==0;
            if(prior.trangThai()==TrangThaiBaiLam.MAT_KET_NOI&&!expired){
                var restored=new Recovery(prior.idHs(),prior.hoVaTen(),prior.maCodePhongThi(),prior.idPhongThi(),TrangThaiBaiLam.DANG_THI,Math.addExact(prior.stateVersion(),1),Math.max(flow.now(),prior.stateChangedAt()),prior.disconnectedAt(),prior.frozenRemainingSeconds());
                writes.execute(member.maCodePhongThi(),member.idHs(),(c,unit,before)->{
                    if(finished(before))return null;
                    // Recheck AFTER acquiring the row locks; waiting for a competing transaction consumes exam time.
                    if(run!=null&&SocketFlowService.remaining(run,flow.now())==0)return null;
                    if(before.resultState()!=TrangThaiBaiLam.MAT_KET_NOI)throw new RuntimeUnavailableException();
                    unit.prepareRecovery(before,after(before,TrangThaiBaiLam.DANG_THI),restored);
                    var result=new SubmissionDAO().lockInRoom(c,member.idHs(),member.idPhongThi()).orElseThrow(RuntimeUnavailableException::new);result.setTrangThai(TrangThaiBaiLam.DANG_THI);
                    if(!new SubmissionDAO().updateUnfinished(c,result))throw new RuntimeUnavailableException();return restored;
                });
            }
            var snapshot=flow.sync(member,token,received);Long left=snapshot.exam()==null?null:SocketFlowService.remaining(snapshot.exam(),flow.now());
            return new SocketDTO.Reconnected("ok",flow.now(),received,snapshot.exam(),snapshot.submission(),snapshot.studentState(),left,left!=null&&left==0);
        }catch(AuthException failure){throw failure;}catch(Exception failure){throw new AuthException(503,"Unable to recover the exam state.");}}
    }
}
